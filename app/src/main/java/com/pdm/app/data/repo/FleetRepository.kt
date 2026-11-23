package com.pdm.app.data.repo

import com.pdm.app.data.FleetMapper
import com.pdm.app.data.GuestHardware
import com.pdm.app.data.HostProbe
import com.pdm.app.data.TaskPoller
import com.pdm.app.data.isTaskExitOk
import com.pdm.app.data.api.PdmApi
import com.pdm.app.data.api.PdmClientFactory
import com.pdm.app.data.model.DashboardMetrics
import com.pdm.app.data.model.FleetDatastore
import com.pdm.app.data.model.FleetNetwork
import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.FleetStorage
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.GuestSnapshot
import com.pdm.app.data.model.GuestStatusDto
import com.pdm.app.data.model.GuestAction
import com.pdm.app.data.model.AptUpdateDto
import com.pdm.app.data.model.MigratePreflightDto
import com.pdm.app.data.model.MigrateRequest
import com.pdm.app.data.model.NodeInventory
import com.pdm.app.data.model.PdmHost
import com.pdm.app.data.model.TaskStatisticsDto
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.pdm.app.data.model.RemoteDetail
import com.pdm.app.data.model.asFlag
import com.pdm.app.data.model.Leaderboards
import com.pdm.app.data.model.NodeUpdates
import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.model.RemoteTask
import com.pdm.app.data.model.TaskOrigin
import com.pdm.app.data.model.HostStatusDto
import com.pdm.app.data.model.RrdPoint
import com.pdm.app.data.model.RrdTimeframe
import com.pdm.app.data.model.TaskLogLine
import com.pdm.app.data.model.TaskStatus
import com.pdm.app.data.model.ReadAvailability
import com.pdm.app.data.model.TaskFilters
import com.pdm.app.data.model.SyslogLineDto
import com.pdm.app.data.model.SyslogFilter
import com.pdm.app.data.model.JournalFilter
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.session.SessionSnapshot
import com.pdm.app.data.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class MigrateTargets(val storages: List<String>, val bridges: List<String>, val nextVmid: Int?)

data class TaskDetail(
    val status: TaskStatus?,
    val log: List<TaskLogLine>,
    val total: Long? = null,
)

/**
 * How much of the fleet one load reads. [QUICK] is what moves on a 30 s scale (inventory, health,
 * leaders, tasks, PDM host load); [FULL] adds what changes on a scale of minutes or days
 * (permissions, subscriptions, update summary, task statistics, PDM host version and packages).
 */
enum class LoadDepth { QUICK, FULL }

data class FleetSnapshot(
    val remotes: List<RemoteNode>,
    val guests: List<Guest>,
    val nodes: List<FleetNode> = emptyList(),
    val storages: List<FleetStorage> = emptyList(),
    val datastores: List<FleetDatastore> = emptyList(),
    val networks: List<FleetNetwork> = emptyList(),
    val metrics: DashboardMetrics = DashboardMetrics(),
    val leaders: Leaderboards = Leaderboards(),
    /** `null` when the read failed or a [LoadDepth.QUICK] load skipped it. */
    val subscriptions: List<RemoteSubscription>? = null,
    /** `null` when `remotes/tasks/list` failed. Empty means the cache had nothing. */
    val tasks: List<RemoteTask>? = null,
    /** `null` when `access/permissions` failed (or was skipped); the UI then leaves controls visible. */
    val permissions: Permissions? = null,
    /** `null` when `remotes/updates/summary` failed. */
    val updates: List<NodeUpdates>? = null,
    /** The PDM machine itself; null when its reads failed or the user lacks System.Audit. */
    val host: PdmHost? = null,
    /**
     * How each optional section read went (Q4). Only sections this load actually read are present;
     * a quick load leaves the slow ones out, and the caller keeps their earlier status.
     */
    val reads: Map<com.pdm.app.data.ops.FleetSection, com.pdm.app.data.ops.SectionStatus> = emptyMap(),
    /** Raw read results for the sections that carry values, so callers can keep old values. */
    val sectionReads: SectionReads = SectionReads(),
)

/** The typed result of each optional read in one load; null means this load didn't read it. */
data class SectionReads(
    val tasks: ReadAvailability<List<RemoteTask>>? = null,
    val subscriptions: ReadAvailability<List<RemoteSubscription>>? = null,
    val updates: ReadAvailability<List<NodeUpdates>>? = null,
    val permissions: ReadAvailability<Permissions>? = null,
    val leaders: ReadAvailability<Leaderboards>? = null,
    val hostStatus: ReadAvailability<HostStatusDto>? = null,
    val hostUpdates: ReadAvailability<List<AptUpdateDto>>? = null,
)

class FleetRepository(
    private val sessionStore: SessionStore,
    private val resolveApi: (SessionSnapshot) -> PdmApi,
    private val poller: TaskPoller = TaskPoller(),
) {
    constructor(
        sessionStore: SessionStore,
        clients: PdmClientFactory,
        poller: TaskPoller = TaskPoller(),
    ) : this(sessionStore, { clients.apiForSession(it) }, poller)

    /**
     * One fleet read. A [LoadDepth.QUICK] load leaves the slow-tier fields `null` and refreshes only
     * the load figures of [previousHost]; the caller keeps what it already had for the rest.
     */
    suspend fun load(
        timeframe: String = "day",
        depth: LoadDepth = LoadDepth.FULL,
        previousHost: PdmHost? = null,
    ): FleetSnapshot = coroutineScope {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        val full = depth == LoadDepth.FULL
        val remotesDeferred = async { api.listRemotes().data.orEmpty() }
        val groupsDeferred = async { api.resourcesList().data.orEmpty() }
        val statusDeferred = async { section { api.resourcesStatus() } }
        val topDeferred = async { leadersRead(api, timeframe) }
        val tasksDeferred = async { tasksRead(api) }
        val hostDeferred = async {
            if (full || previousHost == null) {
                loadHost(api)
            } else {
                refreshHostStatus(api, previousHost)
            }
        }
        val subsDeferred = async { if (full) subscriptionsRead(api) else null }
        val updatesDeferred = async { if (full) updatesRead(api) else null }
        val permissionsDeferred = async { if (full) permissionsRead(api) else null }

        val remotes = FleetMapper.remotesFromApi(remotesDeferred.await())
        val resourceGroups = groupsDeferred.await()
        val statusRead = statusDeferred.await()
        val status = statusRead.valueOrNull
        val leadersRead = topDeferred.await()
        val subsRead = subsDeferred.await()
        val tasksRead = tasksDeferred.await()
        val updatesRead = updatesDeferred.await()
        val permissionsRead = permissionsDeferred.await()
        val hostRead = hostDeferred.await()

        val fromResources = FleetMapper.guestsFromResources(resourceGroups)
        val guests = if (fromResources.isNotEmpty()) {
            fromResources
        } else {
            remotes.filter { it.kind == "pve" }.flatMap { remote ->
                val qemu = listGuestsOrEmpty(api, remote.id, qemu = true)
                val lxc = listGuestsOrEmpty(api, remote.id, qemu = false)
                FleetMapper.guestsFromVmList(remote.id, "qemu", qemu) +
                    FleetMapper.guestsFromVmList(remote.id, "lxc", lxc)
            }
        }
        val nodes = FleetMapper.nodesFromResources(resourceGroups)
        val storages = FleetMapper.storagesFromResources(resourceGroups)
        val datastores = FleetMapper.datastoresFromResources(resourceGroups)
        val networks = FleetMapper.networksFromResources(resourceGroups)
        val groupErrors = resourceGroups.mapNotNull { group ->
            group.error?.let { err -> group.remote to redactSecrets(err).orEmpty() }
        }.toMap()
        val remotesWithHealth = FleetMapper.applyRemoteHealth(remotes, status, groupErrors).ifEmpty {
            guests.map { it.remoteId }.distinct().map { id ->
                RemoteNode(id = id, name = id, kind = "pve")
            }
        }
        val metrics = FleetMapper.dashboard(
            remotes = remotesWithHealth,
            guests = guests,
            nodes = nodes,
            storages = storages,
            datastores = datastores,
            networks = networks,
            status = status,
        )
        FleetSnapshot(
            remotes = remotesWithHealth,
            guests = guests,
            nodes = nodes,
            storages = storages,
            datastores = datastores,
            networks = networks,
            metrics = metrics,
            leaders = leadersRead.valueOrNull ?: Leaderboards(),
            subscriptions = subsRead?.valueOrNull,
            tasks = tasksRead.valueOrNull,
            permissions = permissionsRead?.valueOrNull,
            updates = updatesRead?.valueOrNull,
            host = hostRead.host,
            reads = buildMap {
                put(com.pdm.app.data.ops.FleetSection.REMOTE_HEALTH, com.pdm.app.data.ops.SectionStatus.of(statusRead))
                put(com.pdm.app.data.ops.FleetSection.LEADERS, com.pdm.app.data.ops.SectionStatus.of(leadersRead))
                put(com.pdm.app.data.ops.FleetSection.TASKS, com.pdm.app.data.ops.SectionStatus.of(tasksRead))
                subsRead?.let { put(com.pdm.app.data.ops.FleetSection.SUBSCRIPTIONS, com.pdm.app.data.ops.SectionStatus.of(it)) }
                updatesRead?.let { put(com.pdm.app.data.ops.FleetSection.UPDATES, com.pdm.app.data.ops.SectionStatus.of(it)) }
                permissionsRead?.let { put(com.pdm.app.data.ops.FleetSection.PERMISSIONS, com.pdm.app.data.ops.SectionStatus.of(it)) }
                putAll(hostRead.reads)
            },
            sectionReads = SectionReads(
                tasks = tasksRead,
                subscriptions = subsRead,
                updates = updatesRead,
                permissions = permissionsRead,
                leaders = leadersRead,
                hostStatus = hostRead.status,
                hostUpdates = hostRead.updates,
            ),
        )
    }

    /**
     * One optional read (Q4): live, a saved copy with its age, or why it didn't load. Fatal causes
     * (sign-in, certificate, session change, cancellation) are not swallowed.
     */
    private suspend fun <R> section(call: suspend () -> PdmResponse<R>): ReadAvailability<R> =
        sectionOf(call) { it }

    private suspend fun <R, T> sectionOf(call: suspend () -> PdmResponse<R>, map: (R) -> T): ReadAvailability<T> {
        var cachedAt: Long? = null
        val read = safeAuditRead {
            val resp = call()
            cachedAt = resp.cachedAt
            // A missing data field is not an empty answer: it didn't say anything.
            map(resp.data ?: throw PdmException("The server answered without data."))
        }
        val at = cachedAt
        return if (read is ReadAvailability.Fresh && at != null) ReadAvailability.Stale(read.value, cachedAt = at) else read
    }

    private suspend fun tasksRead(api: PdmApi): ReadAvailability<List<RemoteTask>> = coroutineScope {
        val recent = async { section { api.listTasks(limit = 30) } }
        val running = async { section { api.listTasks(limit = 20, running = true) } }
        val r = recent.await()
        val n = running.await()
        val merged = FleetMapper.tasksFromApi(r.valueOrNull, n.valueOrNull)
        val cachedAt = listOfNotNull((r as? ReadAvailability.Stale)?.cachedAt, (n as? ReadAvailability.Stale)?.cachedAt).minOrNull()
        when {
            merged == null -> if (r !is ReadAvailability.Fresh && r !is ReadAvailability.Stale) r.retype() else n.retype()
            // One of the two lists failed: what is shown is incomplete, so it is not current.
            r.valueOrNull == null || n.valueOrNull == null ->
                ReadAvailability.Stale(merged, error = "Part of the task list didn't load.", cachedAt = cachedAt)
            cachedAt != null -> ReadAvailability.Stale(merged, cachedAt = cachedAt)
            else -> ReadAvailability.Fresh(merged)
        }
    }

    private suspend fun leadersRead(api: PdmApi, timeframe: String) = sectionOf({ api.topEntities(timeframe) }) { FleetMapper.leaderboards(it) }
    private suspend fun subscriptionsRead(api: PdmApi) = section { api.subscriptions() }
    private suspend fun updatesRead(api: PdmApi) = sectionOf({ api.updateSummary() }) { FleetMapper.updatesFromApi(it) }
    private suspend fun permissionsRead(api: PdmApi) = sectionOf({ api.permissions() }) { FleetMapper.permissionsFromApi(it) }

    // Targeted re-reads for one section's Retry (Q4). Reads only; nothing else is reloaded.
    suspend fun readTasks(): ReadAvailability<List<RemoteTask>> = tasksRead(currentApi())
    suspend fun readSubscriptions(): ReadAvailability<List<RemoteSubscription>> = subscriptionsRead(currentApi())
    suspend fun readUpdates(): ReadAvailability<List<NodeUpdates>> = updatesRead(currentApi())
    /** Result counts for the Tasks screen's filters; see [PdmApi.taskStatistics] for why limit is 0. */
    suspend fun taskStatistics(filters: TaskFilters): TaskStatisticsDto {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val resp = resolveApi(session).taskStatistics(
            limit = 0,
            remote = filters.remote,
            since = filters.since,
            until = filters.until,
            userfilter = filters.userfilter,
            typefilter = filters.typefilter,
            statusfilter = filters.statusfilter,
            running = filters.running,
        )
        if (!sessionStore.isCurrent(session)) throw PdmException("Session changed")
        return resp.data ?: throw PdmException("The server answered without counts.")
    }
    suspend fun readPermissions(): ReadAvailability<Permissions> = permissionsRead(currentApi())
    suspend fun readLeaders(timeframe: String): ReadAvailability<Leaderboards> = leadersRead(currentApi(), timeframe)
    suspend fun readHostStatus(node: String): ReadAvailability<HostStatusDto> = currentApi().let { api -> section { api.hostStatus(node) } }
    suspend fun readHostUpdates(node: String): ReadAvailability<List<AptUpdateDto>> =
        currentApi().let { api -> sectionOf({ api.hostAptUpdates(node) }) { it.sortedBy { u -> u.name } } }

    suspend fun <T> safeAuditRead(block: suspend () -> T): ReadAvailability<T> = try {
        ReadAvailability.Fresh(block())
    } catch (e: Exception) {
        e.rethrowIfFleetFatal()
        val httpCode = when (e) {
            is retrofit2.HttpException -> e.code()
            is PdmHttpException -> e.code
            else -> null
        }
        val message = e.message ?: "Failed to read"
        when {
            httpCode == 403 ->
                ReadAvailability.Forbidden("Access forbidden (missing privilege)")
            httpCode == 404 ->
                ReadAvailability.NotFound("Not found on target")
            httpCode == 405 || httpCode == 501 ->
                ReadAvailability.Unsupported("Endpoint not supported by server version")
            e is java.io.IOException ->
                ReadAvailability.TransportError(message, e)
            else ->
                ReadAvailability.Failed(message, e)
        }
    }

    private suspend fun <T> optionalAudit(block: suspend () -> T): T? =
        safeAuditRead(block).valueOrNull

    private suspend fun listGuestsOrEmpty(
        api: PdmApi,
        remoteId: String,
        qemu: Boolean,
    ) = try {
        if (qemu) api.listQemu(remoteId).data.orEmpty() else api.listLxc(remoteId).data.orEmpty()
    } catch (e: Exception) {
        e.rethrowIfFleetFatal()
        emptyList()
    }

    /** Status plus log lines of one remote or host task. */
    suspend fun taskDetail(task: RemoteTask, start: Int = 0, limit: Int = 500, liveOnly: Boolean = false): TaskDetail = coroutineScope {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        val isHost = task.origin is TaskOrigin.Host
        val statusDeferred = async {
            if (isHost) {
                api.hostTaskStatus(task.node, task.upid, liveOnly = if (liveOnly) "1" else null).data
            } else {
                api.remoteTaskStatus(task.kind, task.remoteId, task.upid, liveOnly = if (liveOnly) "1" else null).data
            }
        }
        val logDeferred = async {
            if (isHost) {
                api.hostTaskLog(task.node, task.upid, start = start, limit = limit)
            } else {
                api.remoteTaskLog(task.kind, task.remoteId, task.upid, start = start, limit = limit)
            }
        }
        val status = statusDeferred.await()
        val logResp = logDeferred.await()
        TaskDetail(
            status = status,
            log = logResp.data.orEmpty(),
            total = logResp.total,
        )
    }

    suspend fun taskStatus(task: RemoteTask, liveOnly: Boolean = false): TaskStatus? {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        val isHost = task.origin is TaskOrigin.Host
        val liveHeader = if (liveOnly) "1" else null
        return if (isHost) {
            api.hostTaskStatus(task.node, task.upid, liveOnly = liveHeader).data
        } else {
            api.remoteTaskStatus(task.kind, task.remoteId, task.upid, liveOnly = liveHeader).data
        }
    }

    suspend fun taskLogPage(task: RemoteTask, start: Int = 0, limit: Int = 500): Pair<List<TaskLogLine>, Long?> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        val isHost = task.origin is TaskOrigin.Host
        val resp = if (isHost) {
            api.hostTaskLog(task.node, task.upid, start = start, limit = limit)
        } else {
            api.remoteTaskLog(task.kind, task.remoteId, task.upid, start = start, limit = limit)
        }
        return resp.data.orEmpty() to resp.total
    }

    /**
     * Complete bounded task log drainage (Q2):
     * Repeatedly fetches log pages from [startOffset] until all lines are retrieved or [maxLines]
     * (default 2000) is reached. Ensures completed tasks or finishing tail fetches capture
     * the full log and terminal status lines (e.g. TASK OK) without unbounded buffering.
     */
    suspend fun drainTaskLogTail(
        task: RemoteTask,
        startOffset: Int = 0,
        maxLines: Int = 2000,
    ): Pair<List<TaskLogLine>, Long?> {
        if (maxLines <= 0) return emptyList<TaskLogLine>() to null

        val isHost = task.origin is TaskOrigin.Host
        val pageSize = minOf(500, maxLines)
        val retained = ArrayDeque<TaskLogLine>(minOf(maxLines, 2048))
        val seenNumbers = mutableSetOf<Long>()

        fun addLines(page: List<TaskLogLine>) {
            for (line in page) {
                if (line.n > 0L && line.n in seenNumbers) continue
                if (line.n > 0L) seenNumbers.add(line.n)
                if (retained.size >= maxLines) {
                    val dropped = retained.removeFirst()
                    if (dropped.n > 0L) seenNumbers.remove(dropped.n)
                }
                retained.addLast(line)
            }
        }

        // Initial probe to learn total and inspect first page
        val probeLimit = minOf(pageSize, maxLines)
        val (probePage, probeTotal) = taskLogPage(task, start = startOffset, limit = probeLimit)
        var totalLines = probeTotal

        if (totalLines != null && (totalLines - startOffset) > maxLines) {
            // Log total is known and extends beyond the retention window.
            // Jump directly near the tail instead of streaming the entire history.
            val jumpStart = maxOf(startOffset, (totalLines - maxLines).toInt().coerceAtLeast(0))
            var currentStart = jumpStart
            while (true) {
                val limit = minOf(pageSize, maxLines).coerceAtLeast(1)
                val (page, total) = taskLogPage(task, start = currentStart, limit = limit)
                if (total != null) totalLines = total
                if (page.isEmpty()) break
                addLines(page)
                if (page.size < limit) break

                val advance = maxOf(currentStart + 1, currentStart + page.size)
                currentStart = advance

                if (totalLines != null && currentStart >= totalLines) break
            }
        } else {
            // Total <= maxLines or total is null: consume starting from probePage and page forward
            if (probePage.isNotEmpty()) {
                addLines(probePage)
            }
            if (probePage.size == probeLimit) {
                var currentStart = startOffset + probePage.size
                while (true) {
                    val limit = minOf(pageSize, maxLines).coerceAtLeast(1)
                    val (page, total) = taskLogPage(task, start = currentStart, limit = limit)
                    if (total != null) totalLines = total
                    if (page.isEmpty()) break
                    addLines(page)
                    if (page.size < limit) break

                    val advance = maxOf(currentStart + 1, currentStart + page.size)
                    currentStart = advance

                    if (totalLines != null && currentStart >= totalLines) break
                }
            }
        }

        return retained.toList() to totalLines
    }

    suspend fun stopTask(task: RemoteTask) {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        val isHost = task.origin is TaskOrigin.Host
        if (isHost) {
            api.stopHostTask(task.node, task.upid)
        } else {
            api.stopRemoteTask(task.kind, task.remoteId, task.upid)
        }
    }

    /** Query task history with structured filters and paging (F1). */
    /** One page of remote task history (F1), and when it was saved if the offline cache answered. */
    suspend fun taskHistoryPage(filters: TaskFilters): com.pdm.app.data.ops.HistoryPage {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val http = resolveApi(session).listFilteredTasks(
            limit = filters.limit,
            running = filters.running,
            remote = filters.remote,
            start = filters.start,
            errors = filters.errors,
            userfilter = filters.userfilter,
            since = filters.since,
            until = filters.until,
            typefilter = filters.typefilter,
            statusfilter = filters.statusfilter,
            view = filters.view,
        )
        val body = http.bodyOrThrow()
        return com.pdm.app.data.ops.HistoryPage(body.data.orEmpty().map { FleetMapper.taskFromDto(it) }, http.cachedAt())
    }

    /** One page of the PDM host's own task history (F2), same filters. */
    suspend fun hostTaskHistoryPage(node: String, filters: TaskFilters): com.pdm.app.data.ops.HistoryPage {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val http = resolveApi(session).hostTasks(
            node = node,
            start = filters.start,
            limit = filters.limit,
            errors = filters.errors,
            running = filters.running,
            userfilter = filters.userfilter,
            since = filters.since,
            until = filters.until,
            typefilter = filters.typefilter,
            statusfilter = filters.statusfilter,
        )
        val body = http.bodyOrThrow()
        return com.pdm.app.data.ops.HistoryPage(body.data.orEmpty().map { FleetMapper.hostTaskFromDto(node, it) }, http.cachedAt())
    }

    suspend fun taskHistory(filters: TaskFilters): Pair<List<RemoteTask>, Long?> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        val resp = api.listFilteredTasks(
            limit = filters.limit,
            running = filters.running,
            remote = filters.remote,
            start = filters.start,
            errors = filters.errors,
            userfilter = filters.userfilter,
            since = filters.since,
            until = filters.until,
            typefilter = filters.typefilter,
            statusfilter = filters.statusfilter,
            view = filters.view,
        ).bodyOrThrow()
        val tasks = resp.data.orEmpty().map { FleetMapper.taskFromDto(it) }
        return tasks to resp.total
    }

    /** Trigger apt update on the PDM host. Returns the task UPID (F2). */
    suspend fun checkHostUpdates(node: String): String {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        return api.hostAptUpdate(node).data ?: throw PdmException("Host did not return task UPID")
    }

    /** Debian changelog for a package on the PDM host (F2). */
    suspend fun hostPackageChangelog(node: String, pkgName: String): String {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val text = resolveApi(session).hostAptChangelog(node, pkgName).data.orEmpty()
        if (!sessionStore.isCurrent(session)) throw PdmException("Session changed")
        return checkedChangelog(text, pkgName)
    }

    /** Read syslog lines from the PDM host (F2). */
    suspend fun hostSyslog(node: String, filter: SyslogFilter = SyslogFilter()): Pair<List<SyslogLineDto>, Long?> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        val resp = api.hostSyslog(
            node = node,
            start = filter.start,
            limit = filter.limit ?: 50,
            service = filter.service,
            since = filter.since,
            until = filter.until,
        )
        return resp.data.orEmpty() to resp.total
    }

    /** The newest journal lines from the PDM host (F2). */
    suspend fun hostJournal(node: String, filter: JournalFilter = JournalFilter()): List<String> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val body = resolveApi(session).hostJournal(
            node = node,
            lastentries = filter.lastentries ?: 100,
            since = filter.since,
            until = filter.until,
            service = filter.service,
            priority = filter.priority,
        )
        return com.pdm.app.data.ops.JournalDecoder.decode(body.readBounded()).lines
    }

    /** One cursor-anchored page of the PDM host's journal (F2). */
    suspend fun hostJournalPage(node: String, query: com.pdm.app.data.ops.JournalQuery): com.pdm.app.data.ops.JournalPage {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val body = resolveApi(session).hostJournal(
            node = node,
            // mini-journalreader refuses a count together with a start point.
            lastentries = if (query.startCursor != null) null else query.lastEntries,
            startcursor = query.startCursor,
            endcursor = query.endCursor,
            service = query.service,
            unit = query.unit,
        )
        if (!sessionStore.isCurrent(session)) throw PdmException("Session changed")
        return com.pdm.app.data.ops.JournalDecoder.decode(body.readBounded())
    }

    /** Historical RRD metrics for the PDM host itself (F2). */
    suspend fun hostRrd(node: String, timeframe: RrdTimeframe, cf: String = "AVERAGE"): List<RrdPoint> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val rows = resolveApi(session).hostRrd(node, timeframe.apiName, cf).data.orEmpty()
        return FleetMapper.rrdFromJson(rows)
    }

    /** Tasks running directly on the PDM host (F2). */
    suspend fun hostTasks(
        node: String,
        limit: Int = 30,
        start: Long? = null,
        errors: Boolean? = null,
        running: Boolean? = null,
    ): Pair<List<RemoteTask>, Long?> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val resp = resolveApi(session).hostTasks(
            node = node,
            limit = limit,
            start = start,
            errors = errors,
            running = running,
        ).bodyOrThrow()
        val rows = resp.data.orEmpty().map { FleetMapper.hostTaskFromDto(node, it) }
        return rows to resp.total
    }

    /** Fetch shared PDM notes. Returns text and digest. Live only, never cached (F3). */
    suspend fun getSharedNotes(): Pair<String, String?> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val resp = resolveApi(session).getNotes()
        return resp.data.orEmpty() to resp.digest
    }

    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /** Save shared PDM notes with digest for conflict detection (F3). Returns fresh text and digest. */
    suspend fun putSharedNotes(notes: String, digest: String?): Pair<String, String?> {
        if (digest.isNullOrBlank() || !SHA256_HEX_REGEX.matches(digest)) {
            throw IllegalArgumentException(
                "A valid 64-character SHA-256 hex digest is required before updating shared notes; got: $digest"
            )
        }
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val api = resolveApi(session)
        api.putNotes(notes, digest)
        val fresh = api.getNotes()
        return fresh.data.orEmpty() to fresh.digest
    }

    /** The shared notes and the digest that guards writes, read live (F3). No digest, no copy. */
    suspend fun readSharedNotes(): com.pdm.app.data.ops.NotesCopy {
        val resp = currentApi().getNotes()
        val digest = resp.digest?.takeIf { SHA256_HEX_REGEX.matches(it) }
            ?: throw PdmException("The server didn't send a valid notes digest.")
        return com.pdm.app.data.ops.NotesCopy(resp.data.orEmpty(), digest)
    }

    /**
     * Save the shared notes against [digest] (F3). PDM rejects a stale digest with HTTP 400
     * "detected modified configuration"; that is a conflict, not an error to retry.
     */
    suspend fun writeSharedNotes(text: String, digest: String): com.pdm.app.data.ops.NotesWriteResult {
        require(SHA256_HEX_REGEX.matches(digest)) { "A valid SHA-256 digest is required" }
        return try {
            currentApi().putNotes(text, digest)
            com.pdm.app.data.ops.NotesWriteResult.Saved
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (isDigestConflict(e)) return com.pdm.app.data.ops.NotesWriteResult.Conflict
            when (val f = com.pdm.app.data.ops.Submission.classify(e)) {
                com.pdm.app.data.ops.SubmitFailure.Unknown -> com.pdm.app.data.ops.NotesWriteResult.Unknown
                com.pdm.app.data.ops.SubmitFailure.Fatal -> throw e
                is com.pdm.app.data.ops.SubmitFailure.Rejected ->
                    com.pdm.app.data.ops.NotesWriteResult.Rejected(f.reason, notesTrouble(e))
                is com.pdm.app.data.ops.SubmitFailure.NotSent ->
                    com.pdm.app.data.ops.NotesWriteResult.Rejected("Nothing was sent: ${f.reason}", com.pdm.app.data.ops.NotesTrouble.UNREACHABLE)
            }
        }
    }

    /** Notes/description for a remote PVE cluster (F3). */
    suspend fun getRemoteClusterNotes(remote: String): String? {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val json = resolveApi(session).remoteClusterOptions(remote).data ?: return null
        return json["description"]?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }
    }

    /** Notes/description for a remote PVE node (F3). */
    suspend fun getRemoteNodeNotes(remote: String, node: String): String? {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val json = resolveApi(session).remoteNodeConfig(remote, node).data ?: return null
        return json["description"]?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }
    }

    suspend fun guestRrd(guest: Guest, timeframe: RrdTimeframe, cf: String = "AVERAGE"): List<RrdPoint> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val rows = resolveApi(session)
            .guestRrd(guest.remoteId, guest.type, guest.vmid.toLong(), timeframe.apiName, cf)
            .data.orEmpty()
        return FleetMapper.rrdFromJson(rows)
    }

    suspend fun nodeRrd(node: FleetNode, timeframe: RrdTimeframe, cf: String = "AVERAGE"): List<RrdPoint> {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        val rows = resolveApi(session).nodeRrd(node.remoteId, node.name, timeframe.apiName, cf).data.orEmpty()
        return FleetMapper.rrdFromJson(rows)
    }

    /**
     * Send a power action. Returns the task id (UPID) the server started, or null when it gave
     * none. It does not wait for the task: the operation observer follows it from its receipt.
     */
    suspend fun guestPower(guest: Guest, action: GuestAction): String? {
        val api = currentApi()
        val vmid = guest.vmid.toLong()
        val data = when (guest.type) {
            "lxc" -> when (action) {
                GuestAction.START -> api.lxcStart(guest.remoteId, vmid, guest.node)
                GuestAction.STOP -> api.lxcStop(guest.remoteId, vmid, guest.node)
                GuestAction.SHUTDOWN -> api.lxcShutdown(guest.remoteId, vmid, guest.node)
                GuestAction.RESUME -> throw PdmException("Containers cannot be resumed")
            }
            else -> when (action) {
                GuestAction.START -> api.qemuStart(guest.remoteId, vmid, guest.node)
                GuestAction.STOP -> api.qemuStop(guest.remoteId, vmid, guest.node)
                GuestAction.SHUTDOWN -> api.qemuShutdown(guest.remoteId, vmid, guest.node)
                GuestAction.RESUME -> api.qemuResume(guest.remoteId, vmid, guest.node)
            }
        }.data
        return FleetMapper.asUpidString(data).ifBlank { null }
    }

    /** Cluster status, version, firewall and metric freshness for one remote; each part fails on its own. */
    suspend fun remoteDetail(remote: RemoteNode): RemoteDetail = coroutineScope {
        val api = currentApi()
        val pve = remote.kind == "pve"
        val cluster = async { if (pve) optionalAudit { api.clusterStatus(remote.id).data.orEmpty() } else null }
        val version = async { optionalAudit { api.remoteVersion(remote.id).data } }
        val firewall = async {
            if (pve) optionalAudit { api.firewallStatus().data.orEmpty().firstOrNull { it.remote == remote.id } } else null
        }
        val metrics = async {
            optionalAudit { api.metricCollectionStatus().data.orEmpty().firstOrNull { it.remote == remote.id } }
        }
        val rows = cluster.await()
        val header = rows?.firstOrNull { it.type == "cluster" }
        RemoteDetail(
            clusterName = header?.name,
            // A single-node remote has no cluster row and no quorum to lose.
            quorate = header?.quorate.asFlag(),
            nodes = rows?.let(FleetMapper::clusterNodes),
            version = version.await(),
            firewall = firewall.await(),
            lastMetricCollection = metrics.await()?.lastCollection,
        )
    }

    suspend fun migratePreflight(guest: Guest, target: String): MigratePreflightDto? =
        if (guest.type != "qemu") null else currentApi().qemuMigratePreflight(guest.remoteId, guest.vmid.toLong(), target).data

    /** Storages on [node] that can hold this guest type's disks, plus its bridges and the remote's next free VMID. */
    suspend fun migrateTargets(guest: Guest, remote: String, node: String): MigrateTargets = coroutineScope {
        val api = currentApi()
        val content = if (guest.type == "lxc") "rootdir" else "images"
        val storages = async { api.nodeStorage(remote, node, content).data.orEmpty().filter { it.active.asFlag() != false } }
        val bridges = async { api.nodeBridges(remote, node).data.orEmpty() }
        val nextId = async {
            optionalAudit { (api.clusterNextId(remote).data as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        }
        MigrateTargets(storages.await().map { it.storage }, bridges.await().map { it.iface }.sorted(), nextId.await())
    }

    /** Starts the migration and returns its task id; the caller follows it in the task view. */
    suspend fun startMigration(guest: Guest, req: MigrateRequest): String {
        val api = currentApi()
        val vmid = guest.vmid.toLong()
        val qemu = guest.type == "qemu"
        val running = guest.status == "running"
        val data = if (!req.crossRemote) {
            api.migrate(
                remote = guest.remoteId,
                type = guest.type,
                vmid = vmid,
                target = req.targetNode ?: throw PdmException("Pick a target node"),
                online = (req.online && running).takeIf { qemu },
                restart = (req.online && running).takeIf { !qemu },
                withLocalDisks = req.withLocalDisks.takeIf { qemu && it },
            ).data
        } else {
            api.remoteMigrate(
                remote = guest.remoteId,
                type = guest.type,
                vmid = vmid,
                target = req.targetRemote ?: throw PdmException("Pick a target remote"),
                targetVmid = req.targetVmid,
                targetStorage = req.targetStorage ?: throw PdmException("Pick a target storage"),
                targetBridge = req.targetBridge ?: throw PdmException("Pick a target bridge"),
                delete = req.deleteSource,
                online = (req.online && running).takeIf { qemu },
                restart = (req.online && running).takeIf { !qemu },
            ).data
        }
        return FleetMapper.asUpidString(data).ifBlank { throw PdmException("Migration did not return a task id") }
    }

    /** The PDM machine read, with how each of its parts went. */
    data class HostRead(
        val host: PdmHost?,
        val reads: Map<com.pdm.app.data.ops.FleetSection, com.pdm.app.data.ops.SectionStatus>,
        val status: ReadAvailability<HostStatusDto>? = null,
        val updates: ReadAvailability<List<AptUpdateDto>>? = null,
    )

    /** Each part of the host read fails on its own; only a missing node name drops the whole host. */
    private suspend fun loadHost(api: PdmApi): HostRead = coroutineScope {
        val nodeRead = section { api.pdmNodes() }
        val node = nodeRead.valueOrNull?.firstOrNull()?.node?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope HostRead(
                host = null,
                reads = mapOf(
                    com.pdm.app.data.ops.FleetSection.HOST to
                        if (nodeRead.valueOrNull != null) com.pdm.app.data.ops.SectionStatus(com.pdm.app.data.ops.SectionStatus.Kind.MISSING)
                        else com.pdm.app.data.ops.SectionStatus.of(nodeRead),
                ),
            )
        val version = async { optionalAudit { api.version().data } }
        val status = async { section { api.hostStatus(node) } }
        val updates = async { sectionOf({ api.hostAptUpdates(node) }) { it.sortedBy { u -> u.name } } }
        val sub = async { section { api.hostSubscription(node) } }
        val versions = async { optionalAudit { api.hostAptVersions(node).data } }
        val st = status.await()
        val up = updates.await()
        val su = sub.await()
        HostRead(
            host = PdmHost(
                node, version.await(), st.valueOrNull, up.valueOrNull, su.valueOrNull,
                pendingKernel = com.pdm.app.data.KernelCheck.pendingKernel(st.valueOrNull?.kernel?.release, versions.await()),
            ),
            reads = mapOf(
                com.pdm.app.data.ops.FleetSection.HOST to com.pdm.app.data.ops.SectionStatus.of(nodeRead),
                com.pdm.app.data.ops.FleetSection.HOST_STATUS to com.pdm.app.data.ops.SectionStatus.of(st),
                com.pdm.app.data.ops.FleetSection.HOST_UPDATES to com.pdm.app.data.ops.SectionStatus.of(up),
                com.pdm.app.data.ops.FleetSection.HOST_SUBSCRIPTION to com.pdm.app.data.ops.SectionStatus.of(su),
            ),
            status = st,
            updates = up,
        )
    }

    /** Quick tier: only the host's load moves; version, packages and subscription are kept. */
    private suspend fun refreshHostStatus(api: PdmApi, previous: PdmHost): HostRead {
        val read = section { api.hostStatus(previous.node) }
        val status = read.valueOrNull ?: previous.status
        // Booted into the waiting kernel (or a newer one): no restart pending any more.
        val running = com.pdm.app.data.KernelCheck.build(status?.kernel?.release)
        val pending = previous.pendingKernel?.takeUnless {
            running != null && com.pdm.app.data.KernelCheck.compareBuilds(running, it) >= 0
        }
        return HostRead(
            host = previous.copy(status = status, pendingKernel = pending),
            reads = mapOf(com.pdm.app.data.ops.FleetSection.HOST_STATUS to com.pdm.app.data.ops.SectionStatus.of(read)),
            status = read,
        )
    }

    suspend fun nodeInventory(node: FleetNode): NodeInventory = coroutineScope {
        val api = currentApi()
        val storages = async { optionalAudit { api.nodeStorage(node.remoteId, node.name, content = null, enabled = null).data.orEmpty() } }
        val interfaces = async { optionalAudit { api.nodeNetwork(node.remoteId, node.name).data.orEmpty() } }
        NodeInventory(storages.await()?.sortedBy { it.storage }, interfaces.await())
    }

    /** Restart the PDM machine. It answers first, then goes down. */
    suspend fun hostStatusLive(node: String): HostStatusDto? = currentApi().hostStatusLive(node).data

    suspend fun restartHost(node: String) {
        currentApi().hostPower(node, "reboot")
    }

    /** One live look at the PDM machine: its uptime, an answer of some other kind, or silence. */
    suspend fun probeHost(node: String): HostProbe = try {
        currentApi().hostStatusLive(node).data?.uptime
            ?.let { HostProbe.Up(it) }
            ?: HostProbe.Answering
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        hostProbeFor(e)
    }

    suspend fun refreshUpdates() {
        currentApi().refreshUpdateSummary()
    }

    /** Run `apt update` on the node and wait for it, so the next package read is current. */
    suspend fun checkNodeUpdates(remote: String, node: String) {
        val api = currentApi()
        awaitTask(api, remote, api.nodeAptRefresh(remote, node).data)
    }

    suspend fun nodeUpdates(remote: String, node: String): List<AptUpdateDto> =
        currentApi().nodeAptUpdates(remote, node).data.orEmpty().sortedBy { it.name }

    suspend fun changelog(remote: String, node: String, pkg: String): String {
        val text = currentApi().aptChangelog(remote, node, pkg).data.orEmpty()
        return checkedChangelog(text, pkg)
    }

    suspend fun guestStatus(guest: Guest): GuestStatusDto? =
        currentApi().guestStatus(guest.remoteId, guest.type, guest.vmid.toLong()).data

    suspend fun hardware(guest: Guest): List<com.pdm.app.data.HardwareLine> =
        currentApi().guestConfig(guest.remoteId, guest.type, guest.vmid.toLong()).data
            ?.let { GuestHardware.lines(guest.type, it) }.orEmpty()

    suspend fun snapshots(guest: Guest): List<GuestSnapshot> =
        FleetMapper.snapshotsFromApi(
            currentApi().snapshots(guest.remoteId, guest.type, guest.vmid.toLong()).data.orEmpty(),
        )

    suspend fun createSnapshot(
        guest: Guest,
        name: String,
        description: String,
        withRam: Boolean,
    ): String? {
        val api = currentApi()
        val data = api.createSnapshot(
            remote = guest.remoteId,
            type = guest.type,
            vmid = guest.vmid.toLong(),
            name = name,
            description = description.ifBlank { null },
            vmstate = withRam.takeIf { guest.type == "qemu" && it },
        ).data
        return FleetMapper.asUpidString(data).ifBlank { null }
    }

    suspend fun rollbackSnapshot(guest: Guest, name: String): String? =
        FleetMapper.asUpidString(currentApi().rollbackSnapshot(guest.remoteId, guest.type, guest.vmid.toLong(), name).data)
            .ifBlank { null }

    suspend fun deleteSnapshot(guest: Guest, name: String): String? =
        FleetMapper.asUpidString(currentApi().deleteSnapshot(guest.remoteId, guest.type, guest.vmid.toLong(), name).data)
            .ifBlank { null }

    /**
     * Tasks that started since [receipt] was sent, read live, to find a request whose answer was
     * lost (Q5). Host operations look on the PDM host; guest ones on their remote.
     */
    suspend fun recentTaskCandidates(receipt: com.pdm.app.data.model.OperationReceipt): List<com.pdm.app.data.ops.OperationReconcile.Candidate> {
        val api = currentApi()
        val since = receipt.submittedAt / 1000 - 30
        val rows = when (val origin = receipt.origin) {
            is TaskOrigin.Host -> api.hostTasks(origin.node, limit = 50, since = since, liveOnly = "1").bodyOrThrow().data.orEmpty()
            is TaskOrigin.Remote -> api.listFilteredTasks(limit = 50, remote = origin.remoteId, since = since, liveOnly = "1").bodyOrThrow().data.orEmpty()
            null -> emptyList()
        }
        return rows.map {
            com.pdm.app.data.ops.OperationReconcile.Candidate(it.upid, it.workerType, it.workerId, it.startTime, it.node.ifBlank { null })
        }
    }

    /**
     * Fleet CPU / memory / network history for [timeframe], from every given node's own history.
     * A node whose history does not load is left out rather than failing the whole graph.
     */
    suspend fun fleetTrend(nodes: List<FleetNode>, timeframe: RrdTimeframe): com.pdm.app.data.FleetTrendData =
        coroutineScope {
            val api = currentApi()
            val perNode = nodes.map { node ->
                async {
                    optionalAudit {
                        FleetMapper.rrdFromJson(
                            api.nodeRrd(node.remoteId, node.name, timeframe.apiName, "AVERAGE").data.orEmpty(),
                        ) to node.maxcpu
                    }
                }
            }.map { it.await() }.filterNotNull()
            com.pdm.app.data.FleetTrendMath.aggregate(perNode)
        }

    private fun currentApi(): PdmApi {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        return resolveApi(session)
    }

    /** Follow the UPID a write returned until the worker ends; a non-OK exit becomes an error. */
    private suspend fun awaitTask(api: PdmApi, remote: String, data: kotlinx.serialization.json.JsonElement?) {
        val upid = FleetMapper.asUpidString(data)
        if (upid.isBlank()) return
        // Live answers only; an answer without a status is not "finished OK", so keep looking.
        val status = poller.awaitStop {
            api.remoteTaskStatus("pve", remote, upid, liveOnly = "1").data ?: TaskStatus(status = "running")
        }
        if (!isTaskExitOk(status) && !status.exitstatus.isNullOrBlank()) {
            throw PdmException("Task ${status.exitstatus}")
        }
    }

}

/**
 * What a failed host probe means. Auth and TLS are distinct answers. A changed session or
 * "Not signed in" is rethrown so the restart watch stops instead of looking like silence.
 */
internal fun hostProbeFor(error: Exception): HostProbe {
    if (error.isAuthFailure()) return HostProbe.SignInNeeded
    if (error.isTlsFailure()) return HostProbe.CertificateRefused
    val notSignedIn = generateSequence<Throwable>(error) { it.cause }
        .any { it.message?.contains("Not signed in", ignoreCase = true) == true }
    if (error.isSessionChanged() || notSignedIn) throw error
    val http = generateSequence<Throwable>(error) { it.cause }
        .any { it is retrofit2.HttpException || it is PdmHttpException }
    return if (http) HostProbe.Answering else HostProbe.Silent
}

internal fun Exception.rethrowIfFleetFatal() {
    if (this is CancellationException) throw this
    if (isAuthFailure() || isTlsFailure() || isSessionChanged()) throw this
}

/** The body of a successful answer; an HTTP error becomes the same exception Retrofit would throw. */
internal fun <T> retrofit2.Response<T>.bodyOrThrow(): T {
    if (!isSuccessful) throw retrofit2.HttpException(this)
    return body() ?: throw PdmException("The server sent an empty answer.")
}

/** When the offline cache saved this answer; null when it came live from the server. */
internal fun retrofit2.Response<*>.cachedAt(): Long? =
    headers()[com.pdm.app.data.cache.CACHE_HEADER]?.toLongOrNull()

/** PDM's answer to a write made against an outdated copy (proxmox ConfigDigest). */
internal fun isDigestConflict(e: Throwable): Boolean {
    val http = generateSequence(e) { it.cause }.firstOrNull { it is retrofit2.HttpException || it is PdmHttpException }
    val code = (http as? retrofit2.HttpException)?.code() ?: (http as? PdmHttpException)?.code
    val body = when (http) {
        is retrofit2.HttpException -> runCatching { http.response()?.errorBody()?.string() }.getOrNull()
        is PdmHttpException -> http.body ?: http.message
        else -> null
    }.orEmpty() + " " + (e.message ?: "")
    return code == 400 && body.contains("modified", ignoreCase = true) && body.contains("configuration", ignoreCase = true)
}

/** Forbidden / unreachable / other, for notes reads and writes. */
internal fun notesTrouble(e: Throwable): com.pdm.app.data.ops.NotesTrouble {
    val http = generateSequence(e) { it.cause }.firstOrNull { it is retrofit2.HttpException || it is PdmHttpException }
    val code = (http as? retrofit2.HttpException)?.code() ?: (http as? PdmHttpException)?.code
    return when {
        code == 403 -> com.pdm.app.data.ops.NotesTrouble.FORBIDDEN
        http == null && e.isNetworkFailure() -> com.pdm.app.data.ops.NotesTrouble.UNREACHABLE
        else -> com.pdm.app.data.ops.NotesTrouble.OTHER
    }
}

/** Reads a streamed body, refusing anything past [com.pdm.app.data.ops.JournalDecoder.MAX_BYTES]. */
internal fun okhttp3.ResponseBody.readBounded(max: Int = com.pdm.app.data.ops.JournalDecoder.MAX_BYTES): String = use { body ->
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    body.byteStream().use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw PdmException("The log answer was too large to show.")
        }
    }
    out.toString(Charsets.UTF_8.name())
}

/**
 * apt answers a missing changelog with HTTP 200 and its own error text, so the text is checked
 * before it is shown as a changelog.
 */
internal fun checkedChangelog(text: String, pkg: String): String {
    if (text.contains("Changelog unavailable", ignoreCase = true) || text.lineSequence().any { it.startsWith("E:") }) {
        throw PdmException("Changelog fetch failed for $pkg. " +
            if (text.contains("404") || text.contains("Changelog unavailable", true)) {
                "The repository has not published it for this version. The update is still available."
            } else {
                "The package repository could not supply it. The update is still available; try again later."
            })
    }
    if (text.isBlank()) throw PdmException("The repository returned an empty changelog for $pkg.")
    return text
}

/** A failed read carries no value, so it can stand for any type. */
@Suppress("UNCHECKED_CAST")
private fun <T> ReadAvailability<*>.retype(): ReadAvailability<T> = this as ReadAvailability<T>
