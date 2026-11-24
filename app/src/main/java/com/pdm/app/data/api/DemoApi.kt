package com.pdm.app.data.api

import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import com.pdm.app.data.DemoFleet
import com.pdm.app.data.DemoHistory
import com.pdm.app.data.FleetMapper
import com.pdm.app.data.model.ByteStatisticsDto
import com.pdm.app.data.model.CountTriple
import com.pdm.app.data.model.CpuStatisticsDto
import com.pdm.app.data.model.AptUpdateDto
import com.pdm.app.data.model.SyslogLineDto
import com.pdm.app.data.model.ShellTicketDto
import com.pdm.app.data.repo.PdmException
import com.pdm.app.data.model.ClusterStatusDto
import com.pdm.app.data.model.FirewallRemoteDto
import com.pdm.app.data.model.FirewallStateDto
import com.pdm.app.data.model.MetricCollectionDto
import com.pdm.app.data.model.GuestStatusDto
import com.pdm.app.data.model.NodeUpdatesDto
import com.pdm.app.data.model.PackageVersionDto
import com.pdm.app.data.model.RemoteUpdatesDto
import com.pdm.app.data.model.UpdateSummaryDto
import com.pdm.app.data.model.PbsDatastoreCounts
import com.pdm.app.data.model.MigratePreflightDto
import com.pdm.app.data.model.NetIfaceDto
import com.pdm.app.data.model.NodeStorageDto
import com.pdm.app.data.model.HostCpuInfoDto
import com.pdm.app.data.model.HostDiskDto
import com.pdm.app.data.model.HostKernelDto
import com.pdm.app.data.model.HostMemoryDto
import com.pdm.app.data.model.HostStatusDto
import com.pdm.app.data.model.HostSubscriptionDto
import com.pdm.app.data.model.PdmNodeRef
import com.pdm.app.data.model.PdmRemote
import com.pdm.app.data.model.TaskCountsDto
import com.pdm.app.data.model.TaskStatisticsDto
import com.pdm.app.data.model.RealmDto
import com.pdm.app.data.model.PdmResource
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.RemoteInfoStatus
import com.pdm.app.data.model.RemoteResourcesGroup
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.model.RemoteTaskDto
import com.pdm.app.data.model.SnapshotDto
import com.pdm.app.data.model.TaskPhase
import com.pdm.app.data.model.ResourcesStatus
import com.pdm.app.data.model.TaskLogLine
import com.pdm.app.data.model.TaskStatus
import com.pdm.app.data.model.TicketInfo
import com.pdm.app.data.model.TopEntities
import com.pdm.app.data.model.TopEntityDto
import com.pdm.app.data.model.VersionInfo
import com.pdm.app.data.model.VmEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** In-memory PDM. Host `demo` never opens a socket. */
class DemoApi(private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) : PdmApi {
    private val lock = Any()
    private val guests = DemoFleet.guests().toMutableList()

    override suspend fun version(): PdmResponse<VersionInfo> =
        PdmResponse(data = VersionInfo(version = "0.1", release = "demo", repoid = "pdm"))

    override suspend fun createTicket(
        username: String,
        password: String,
        tfaChallenge: String?,
    ): Response<PdmResponse<TicketInfo>> {
        // User "tfa": answers like a PDM account with a second factor (TOTP 123456 or any well-formed
        // recovery key). Drives TfaLoginTest; the Demo button signs in directly and never gets here.
        if (username.substringBefore('@').equals("tfa", ignoreCase = true)) {
            val accepted = !tfaChallenge.isNullOrBlank() &&
                (password == "totp:123456" || Regex("^recovery:[0-9a-f]{4}(-[0-9a-f]{4}){3}$").matches(password))
            if (!accepted) {
                val challenge = java.net.URLEncoder.encode("""{"totp":true,"recovery":[0,4,9]}""", "UTF-8")
                return Response.success(
                    PdmResponse(
                        data = TicketInfo(ticket = "PDM:!tfa!$challenge:6716A0B1::demo", csrfPreventionToken = "invalid", username = username),
                    ),
                )
            }
        }
        val body = PdmResponse(
            data = TicketInfo(
                ticket = "PDM:demo",
                csrfPreventionToken = "demo-csrf",
                username = username,
            ),
        )
        return Response.success(body)
    }

    override suspend fun listRemotes(): PdmResponse<List<PdmRemote>> =
        PdmResponse(
            data = DemoFleet.remotes.map { remote ->
                PdmRemote(
                    id = remote.id,
                    type = remote.kind,
                    nodes = remote.endpoints.map { ep ->
                        JsonPrimitive("${ep.host}:${ep.port ?: 8006}")
                    },
                )
            },
        )

    override suspend fun resourcesList(maxAge: Int): PdmResponse<List<RemoteResourcesGroup>> {
        val groups = DemoFleet.remotes.map { remote ->
            val list = mutableListOf<PdmResource>()
            DemoFleet.nodes().filter { it.remoteId == remote.id }.forEach { node ->
                list += PdmResource(
                    id = "${remote.id}/node/${node.name}",
                    type = "node",
                    node = node.name,
                    name = node.name,
                    status = "online",
                    cpu = node.cpu ?: 0.1,
                    maxcpu = node.maxcpu ?: 4.0,
                    mem = node.mem ?: (1L shl 30),
                    maxmem = node.maxmem ?: (16L shl 30),
                    uptime = 86400L,
                )
            }
            val snapshot = synchronized(lock) { guests.toList() }
            snapshot.filter { it.remoteId == remote.id }.forEach { guest ->
                val (memUsed, memMax) = when (guest.vmid) {
                    100 -> (1536L shl 20) to (4096L shl 20)
                    101 -> (800L shl 20) to (2048L shl 20)
                    200 -> (512L shl 20) to (1024L shl 20)
                    201 -> (400L shl 20) to (512L shl 20)
                    else -> (1024L shl 20) to (2048L shl 20)
                }
                list += PdmResource(
                    id = guest.id,
                    type = if (guest.type == "lxc") "lxc" else "qemu",
                    node = guest.node,
                    name = guest.name,
                    vmid = guest.vmid.toLong(),
                    status = guest.status,
                    cpu = if (guest.status == "running") 0.05 else 0.0,
                    maxcpu = 2.0,
                    mem = if (guest.status == "running") memUsed else 0L,
                    maxmem = memMax,
                    uptime = if (guest.status == "running") 43200L else 0L,
                )
            }
            list += PdmResource(
                id = "${remote.id}/storage/local-lvm",
                type = "storage",
                name = "local-lvm",
                disk = 40L shl 30,
                maxdisk = 200L shl 30,
                status = "available",
            )
            RemoteResourcesGroup(
                remote = remote.id,
                resources = list,
            )
        }
        return PdmResponse(data = groups)
    }

    override suspend fun resourcesStatus(maxAge: Int): PdmResponse<ResourcesStatus> {
        return PdmResponse(
            data = ResourcesStatus(
                remotes = DemoFleet.remotes.size.toLong(),
                failedRemotes = 0L,
                pveNodes = CountTriple(online = 2L),
                pbsNodes = CountTriple(online = 1L),
                qemu = CountTriple(running = 1L, stopped = 2L),
                lxc = CountTriple(running = 1L),
                storages = CountTriple(available = 3L),
                pveCpuStats = CpuStatisticsDto(
                    used = 0.42,
                    max = 24.0,
                ),
                pveMemoryStats = ByteStatisticsDto(
                    total = 32L shl 30,
                    used = 12L shl 30,
                ),
                pveStorageStats = ByteStatisticsDto(
                    total = 200L shl 30,
                    used = 40L shl 30,
                ),
                remoteList = DemoFleet.remotes.map { RemoteInfoStatus(name = it.id, ty = it.kind, status = "ok") },
                pbsDatastores = PbsDatastoreCounts(
                    online = 1L,
                ),
            ),
        )
    }

    override suspend fun topEntities(timeframe: String): PdmResponse<TopEntities> {
        val window = DemoHistory.window(timeframe, nowSeconds())
        val snapshot = synchronized(lock) { guests.toList() }
        val guestLeaders = snapshot.take(5).map { guest ->
            TopEntityDto(
                remote = guest.remoteId,
                resource = PdmResource(
                    type = guest.type,
                    vmid = guest.vmid.toLong(),
                    name = guest.name,
                    node = guest.node,
                ),
                rrdData = DemoHistory.leaderSeries(window, guest.vmid),
            )
        }
        val nodeLeaders = DemoFleet.nodes().map { node ->
            TopEntityDto(
                remote = node.remoteId,
                resource = PdmResource(type = "pve-node", node = node.name, name = node.name),
                rrdData = DemoHistory.leaderSeries(window, node.name.hashCode()),
            )
        }
        val memLeaders = DemoFleet.nodes().map { node ->
            TopEntityDto(
                remote = node.remoteId,
                resource = PdmResource(type = "pve-node", node = node.name, name = node.name),
                rrdData = DemoHistory.leaderSeries(window, node.name.hashCode(), memory = true),
            )
        }
        return PdmResponse(
            data = TopEntities(
                guestCpu = guestLeaders,
                nodeCpu = nodeLeaders,
                nodeMemory = memLeaders,
            ),
        )
    }

    override suspend fun subscriptions(): PdmResponse<List<RemoteSubscription>> =
        PdmResponse(data = DemoFleet.subscriptions)

    override suspend fun listTasks(
        limit: Int,
        running: Boolean?,
        remote: String?,
    ): PdmResponse<List<RemoteTaskDto>> = demoFilteredTasks(limit, running, remote, null, null, null, null, null, null, null)

    override suspend fun listFilteredTasks(
        limit: Int?,
        running: Boolean?,
        remote: String?,
        start: Long?,
        errors: Boolean?,
        userfilter: String?,
        since: Long?,
        until: Long?,
        typefilter: String?,
        statusfilter: String?,
        view: String?,
        liveOnly: String?,
    ): Response<PdmResponse<List<RemoteTaskDto>>> = Response.success(demoFilteredTasks(limit, running, remote, start, errors, userfilter, since, until, typefilter, statusfilter))

    private fun demoFilteredTasks(
        limit: Int?,
        running: Boolean?,
        remote: String?,
        start: Long?,
        errors: Boolean?,
        userfilter: String?,
        since: Long?,
        until: Long?,
        typefilter: String?,
        statusfilter: String?,
    ): PdmResponse<List<RemoteTaskDto>> {
        val filtered = DemoFleet.taskDtos().filter { dto ->
            val task = FleetMapper.taskFromDto(dto)
            (remote.isNullOrBlank() || task.remoteId == remote) &&
                (running != true || task.phase == TaskPhase.Running) &&
                (errors != true || task.phase == TaskPhase.Error) &&
                (userfilter.isNullOrBlank() || task.user.contains(userfilter, ignoreCase = true)) &&
                (typefilter.isNullOrBlank() || task.workerType.contains(typefilter, ignoreCase = true)) &&
                (since == null || task.startTime >= since) &&
                (until == null || task.startTime <= until) &&
                demoStatusMatches(dto.status, statusfilter)
        }
        val dropCount = (start ?: 0L).toInt().coerceAtLeast(0)
        val takeLimit = (limit ?: 30).coerceAtLeast(0)
        val sliced = filtered.drop(dropCount).take(takeLimit)
        return PdmResponse(data = sliced, total = filtered.size.toLong())
    }

    /** PDM's statusfilter: ok, warning, error or unknown; a running task matches none. */
    private fun demoStatusMatches(status: String?, filter: String?): Boolean {
        if (filter.isNullOrBlank()) return true
        status ?: return false
        val kind = when {
            status == "OK" -> "ok"
            status.startsWith("WARNINGS: ") -> "warning"
            status == "unknown" || status.isEmpty() -> "unknown"
            else -> "error"
        }
        return kind == filter
    }

    override suspend fun listQemu(remote: String): PdmResponse<List<VmEntry>> =
        PdmResponse(data = vmEntries(remote, "qemu"))

    override suspend fun listLxc(remote: String): PdmResponse<List<VmEntry>> =
        PdmResponse(data = vmEntries(remote, "lxc"))

    override suspend fun qemuStart(remote: String, vmid: Long, node: String?) =
        power(remote, "qemu", vmid, "running", "start")

    override suspend fun qemuStop(remote: String, vmid: Long, node: String?) =
        power(remote, "qemu", vmid, "stopped", "stop")

    override suspend fun qemuShutdown(remote: String, vmid: Long, node: String?) =
        power(remote, "qemu", vmid, "stopped", "shutdown")

    override suspend fun lxcStart(remote: String, vmid: Long, node: String?) =
        power(remote, "lxc", vmid, "running", "start")

    override suspend fun lxcStop(remote: String, vmid: Long, node: String?) =
        power(remote, "lxc", vmid, "stopped", "stop")

    override suspend fun lxcShutdown(remote: String, vmid: Long, node: String?) =
        power(remote, "lxc", vmid, "stopped", "shutdown")

    override suspend fun taskStatus(remote: String, upid: String, wait: Boolean): PdmResponse<TaskStatus> =
        PdmResponse(data = TaskStatus(status = "stopped", exitstatus = "OK", upid = upid, node = remote))

    override suspend fun remoteTaskStatus(kind: String, remote: String, upid: String, liveOnly: String?): PdmResponse<TaskStatus> =
        PdmResponse(data = TaskStatus(status = "stopped", exitstatus = "OK", upid = upid, node = remote))

    override suspend fun remoteTaskLog(
        kind: String,
        remote: String,
        upid: String,
        start: Int,
        limit: Int,
    ): PdmResponse<List<TaskLogLine>> =
        PdmResponse(
            data = listOf(
                TaskLogLine(1, "demo task on $remote"),
                TaskLogLine(2, "no network was used"),
                TaskLogLine(3, "TASK OK"),
            ).drop(start).take(limit),
            total = 3L,
        )

    override suspend fun stopRemoteTask(kind: String, remote: String, upid: String): PdmResponse<JsonElement> =
        PdmResponse(data = JsonNull)

    override suspend fun guestRrd(
        remote: String,
        type: String,
        vmid: Long,
        timeframe: String,
        cf: String,
    ): PdmResponse<List<JsonObject>> = PdmResponse(data = demoRrd(seed = vmid.toInt(), timeframe = timeframe))

    override suspend fun nodeRrd(
        remote: String,
        node: String,
        timeframe: String,
        cf: String,
    ): PdmResponse<List<JsonObject>> = PdmResponse(data = demoRrd(seed = node.hashCode(), timeframe = timeframe))

    private fun demoRrd(seed: Int, timeframe: String): List<JsonObject> {
        val window = DemoHistory.window(timeframe, nowSeconds())
        return List(window.samples) { i ->
            val time = window.timeAt(i)
            val wave = DemoHistory.cpu(seed, time)
            JsonObject(
                mapOf(
                    "time" to JsonPrimitive(time),
                    "cpu-current" to JsonPrimitive(wave),
                    "cpu-max" to JsonPrimitive(4.0),
                    "cpu-avg1" to JsonPrimitive(0.4 + wave * 1.2),
                    "mem-used" to JsonPrimitive((DemoHistory.memoryRatio(seed, time) * 8_589_934_592).toLong()),
                    "mem-total" to JsonPrimitive(8_589_934_592L),
                    "net-in" to JsonPrimitive(2_000.0 + wave * 40_000.0),
                    "net-out" to JsonPrimitive(500.0 + wave * 9_000.0),
                    "disk-read" to JsonPrimitive(wave * 120_000.0),
                    "disk-write" to JsonPrimitive(wave * 60_000.0),
                ),
            )
        }
    }

    private fun vmEntries(remote: String, type: String): List<VmEntry> {
        val snapshot = synchronized(lock) { guests.toList() }
        return snapshot.filter { it.remoteId == remote && it.type == type }.map { guest ->
            VmEntry(
                vmid = guest.vmid.toLong(),
                name = guest.name,
                status = guest.status,
                node = guest.node,
            )
        }
    }

    override suspend fun qemuMigratePreflight(remote: String, vmid: Long, target: String): PdmResponse<MigratePreflightDto> =
        PdmResponse(data = MigratePreflightDto())

    override suspend fun migrate(
        remote: String,
        type: String,
        vmid: Long,
        target: String,
        online: Boolean?,
        restart: Boolean?,
        withLocalDisks: Boolean?,
    ): PdmResponse<JsonElement> {
        synchronized(lock) {
            val idx = guests.indexOfFirst { it.remoteId == remote && it.type == type && it.vmid.toLong() == vmid }
            if (idx >= 0) guests[idx] = guests[idx].copy(node = target)
        }
        return PdmResponse(data = JsonPrimitive("pve:$remote!UPID:$target:1:1:1:qmigrate:$vmid:root@pam:"))
    }

    override suspend fun remoteMigrate(
        remote: String,
        type: String,
        vmid: Long,
        target: String,
        targetVmid: Int?,
        targetStorage: String,
        targetBridge: String,
        delete: Boolean?,
        online: Boolean?,
        restart: Boolean?,
    ): PdmResponse<JsonElement> {
        synchronized(lock) {
            val idx = guests.indexOfFirst { it.remoteId == remote && it.type == type && it.vmid.toLong() == vmid }
            if (idx >= 0) {
                val moved = guests[idx].copy(remoteId = target, vmid = targetVmid ?: guests[idx].vmid)
                guests[idx] = moved.copy(id = "${moved.remoteId}/${moved.type}/${moved.vmid}")
            }
        }
        return PdmResponse(data = JsonPrimitive("pve:$remote!UPID:demo:1:1:1:qmigrate:$vmid:root@pam:"))
    }

    override suspend fun nodeStorage(remote: String, node: String, content: String?, enabled: Boolean?): PdmResponse<List<NodeStorageDto>> =
        PdmResponse(
            data = listOf(
                NodeStorageDto("local-lvm", "lvmthin", "images,rootdir", JsonPrimitive(true), used = 40L shl 30, total = 200L shl 30),
                NodeStorageDto("tank", "zfspool", "images,rootdir", JsonPrimitive(true), used = 900L shl 30, total = 1000L shl 30),
            ),
        )

    override suspend fun nodeNetwork(remote: String, node: String): PdmResponse<List<NetIfaceDto>> =
        PdmResponse(
            data = listOf(
                NetIfaceDto("vmbr0", "bridge", JsonPrimitive(true), cidr = "192.0.2.10/24", gateway = "192.0.2.1", bridgePorts = "eno1"),
                NetIfaceDto("eno1", "eth", JsonPrimitive(true)),
                NetIfaceDto("eno2", "eth"),
            ),
        )

    override suspend fun nodeBridges(remote: String, node: String, type: String): PdmResponse<List<NetIfaceDto>> =
        PdmResponse(data = listOf(NetIfaceDto("vmbr0", "bridge")))

    override suspend fun clusterNextId(remote: String): PdmResponse<JsonElement> = PdmResponse(data = JsonPrimitive(900))

    override suspend fun guestConfig(remote: String, type: String, vmid: Long, state: String): PdmResponse<JsonObject> =
        PdmResponse(
            data = JsonObject(
                mapOf(
                    "cores" to JsonPrimitive(2),
                    "memory" to JsonPrimitive("2048"),
                    (if (type == "lxc") "rootfs" else "scsi0") to JsonPrimitive("local-lvm:vm-$vmid-disk-0,size=16G"),
                    "net0" to JsonPrimitive(if (type == "lxc") "name=eth0,bridge=vmbr0,ip=dhcp" else "virtio=02:00:00:00:00:01,bridge=vmbr0"),
                    "onboot" to JsonPrimitive(true),
                ),
            ),
        )

    override suspend fun pdmNodes(): PdmResponse<List<PdmNodeRef>> = PdmResponse(data = listOf(PdmNodeRef("pdm-demo")))

    override suspend fun hostStatusLive(node: String, liveOnly: String): PdmResponse<HostStatusDto> = hostStatus(node)

    override suspend fun hostAptVersions(node: String): PdmResponse<List<AptUpdateDto>> = PdmResponse(data = emptyList())

    override suspend fun hostPower(node: String, command: String): PdmResponse<JsonElement> =
        throw PdmException("The demo can't restart.")

    override suspend fun hostStatus(node: String): PdmResponse<HostStatusDto> =
        PdmResponse(
            data = HostStatusDto(
                cpu = 0.02,
                loadavg = listOf(0.2, 0.1, 0.1),
                memory = HostMemoryDto(total = 8L shl 30, used = 1L shl 30),
                root = HostDiskDto(total = 50L shl 30, used = 4L shl 30),
                uptime = 86_400,
                cpuinfo = HostCpuInfoDto(cpus = 4, model = "demo"),
                kernel = HostKernelDto("demo"),
            ),
        )

    override suspend fun hostAptUpdates(node: String): PdmResponse<List<AptUpdateDto>> =
        PdmResponse(data = listOf(AptUpdateDto("proxmox-datacenter-manager", "1.1.6", "1.1.7", "Proxmox", "Proxmox Datacenter Manager")))

    override suspend fun hostSubscription(node: String): PdmResponse<HostSubscriptionDto> =
        PdmResponse(data = HostSubscriptionDto(status = "invalid", message = "demo"))

    override suspend fun taskStatistics(
        limit: Int,
        remote: String?,
        since: Long?,
        until: Long?,
        userfilter: String?,
        typefilter: String?,
        statusfilter: String?,
        running: Boolean?,
    ): PdmResponse<TaskStatisticsDto> {
        // Counted from the same demo tasks the list shows, so the card and the list agree.
        val tasks = demoFilteredTasks(
            limit = if (limit == 0) Int.MAX_VALUE else limit, running = running, remote = remote, start = 0,
            errors = null, userfilter = userfilter, since = since, until = until, typefilter = typefilter,
            statusfilter = statusfilter,
        ).data.orEmpty().filter { it.status != null }
        fun count(rows: List<RemoteTaskDto>) = TaskCountsDto(
            ok = rows.count { it.status == "OK" },
            warning = rows.count { it.status!!.startsWith("WARNINGS: ") },
            error = rows.count { it.status != "OK" && !it.status!!.startsWith("WARNINGS: ") && it.status != "unknown" },
            unknown = rows.count { it.status == "unknown" },
        )
        return PdmResponse(
            data = TaskStatisticsDto(
                byRemote = tasks.groupBy { it.upid.substringBefore('!') }.mapValues { count(it.value) },
                byType = tasks.groupBy { it.workerType }.mapValues { count(it.value) },
            ),
        )
    }

    override suspend fun realms(): PdmResponse<List<RealmDto>> =
        PdmResponse(data = listOf(RealmDto("pam", "pam", "Linux PAM standard authentication"), RealmDto("pdm", "pdm", "PDM users")))

    override suspend fun clusterStatus(remote: String): PdmResponse<List<ClusterStatusDto>> =
        PdmResponse(
            data = DemoFleet.remotes.firstOrNull { it.id == remote }?.let { r ->
                listOf(ClusterStatusDto(id = "node/${r.id}", type = "node", name = r.id, ip = "192.0.2.10", online = JsonPrimitive(true), local = JsonPrimitive(true)))
            }.orEmpty(),
        )

    override suspend fun remoteVersion(id: String): PdmResponse<VersionInfo> =
        PdmResponse(data = VersionInfo(version = "9.2.21", release = "9.2"))

    override suspend fun firewallStatus(): PdmResponse<List<FirewallRemoteDto>> =
        PdmResponse(data = DemoFleet.remotes.map { FirewallRemoteDto(remote = it.id, status = FirewallStateDto(enabled = false)) })

    override suspend fun metricCollectionStatus(): PdmResponse<List<MetricCollectionDto>> =
        PdmResponse(data = DemoFleet.remotes.map { MetricCollectionDto(it.id, System.currentTimeMillis() / 1000 - 120) }?.toList().orEmpty())

    override suspend fun updateSummary(): PdmResponse<UpdateSummaryDto> =
        PdmResponse(
            data = UpdateSummaryDto(
                remotes = mapOf(
                    "edge-a" to RemoteUpdatesDto(
                        status = "success",
                        remoteType = "pve",
                        nodes = mapOf(
                            "pve-a" to NodeUpdatesDto(
                                status = "success",
                                numberOfUpdates = 2,
                                repositoryStatus = "ok",
                                lastRefresh = System.currentTimeMillis() / 1000 - 3600,
                                versions = listOf(PackageVersionDto("pve-manager", "9.2.21")),
                            ),
                        ),
                    ),
                ),
            ),
        )

    override suspend fun refreshUpdateSummary(): PdmResponse<JsonElement> = PdmResponse(data = JsonNull)

    override suspend fun remoteShellTicket(remote: String, node: String): PdmResponse<ShellTicketDto> =
        throw PdmException("The demo has no terminal.")

    override suspend fun hostShellTicket(node: String, cmd: String): PdmResponse<ShellTicketDto> =
        throw PdmException("The demo has no terminal.")

    override suspend fun nodeAptRefresh(remote: String, node: String): PdmResponse<JsonElement> = PdmResponse(data = JsonNull)

    override suspend fun nodeAptUpdates(remote: String, node: String): PdmResponse<List<AptUpdateDto>> =
        PdmResponse(
            data = listOf(
                AptUpdateDto("bind9-host", "1:9.20.29-1", "1:9.20.30-1", "Debian", "DNS lookup utility"),
                AptUpdateDto("pve-manager", "9.2.20", "9.2.21", "Proxmox", "Proxmox Virtual Environment"),
            ),
        )

    override suspend fun aptChangelog(remote: String, node: String, name: String): PdmResponse<String> =
        PdmResponse(data = "$name (demo) unstable; urgency=medium\n\n  * Demo changelog, no network used.\n")

    override suspend fun permissions(): PdmResponse<Map<String, JsonObject>> =
        PdmResponse(
            data = mapOf(
                "/" to JsonObject(
                    listOf("Resource.Audit", "Resource.Manage", "Resource.Migrate", "Sys.Audit")
                        .associateWith { JsonPrimitive(true) },
                ),
            ),
        )

    override suspend fun guestStatus(remote: String, type: String, vmid: Long): PdmResponse<GuestStatusDto> {
        val guest = synchronized(lock) {
            guests.firstOrNull { it.remoteId == remote && it.type == type && it.vmid.toLong() == vmid }
        } ?: throw IllegalStateException("no such guest $remote/$type/$vmid")
        return PdmResponse(data = GuestStatusDto(status = if (guest.status == "paused") "running" else guest.status, qmpstatus = guest.status))
    }

    override suspend fun qemuResume(remote: String, vmid: Long, node: String?) =
        power(remote, "qemu", vmid, "running", "resume")

    private val demoSnapshots = mutableMapOf<String, MutableList<SnapshotDto>>()

    override suspend fun snapshots(remote: String, type: String, vmid: Long): PdmResponse<List<SnapshotDto>> {
        val list = synchronized(lock) { demoSnapshots["$remote/$type/$vmid"].orEmpty().toList() }
        return PdmResponse(data = list + SnapshotDto(name = "current", parent = list.lastOrNull()?.name))
    }

    override suspend fun createSnapshot(
        remote: String,
        type: String,
        vmid: Long,
        name: String,
        description: String?,
        vmstate: Boolean?,
    ): PdmResponse<JsonElement> {
        synchronized(lock) {
            val list = demoSnapshots.getOrPut("$remote/$type/$vmid") { mutableListOf() }
            list += SnapshotDto(
                name = name,
                description = description,
                snaptime = System.currentTimeMillis() / 1000,
                parent = list.lastOrNull()?.name,
                vmstate = JsonPrimitive(vmstate == true),
            )
        }
        return PdmResponse(data = JsonPrimitive("demo:$remote:UPID:snapshot:$vmid"))
    }

    override suspend fun rollbackSnapshot(
        remote: String,
        type: String,
        vmid: Long,
        name: String,
        start: Boolean?,
    ): PdmResponse<JsonElement> = PdmResponse(data = JsonPrimitive("demo:$remote:UPID:rollback:$vmid"))

    override suspend fun deleteSnapshot(remote: String, type: String, vmid: Long, name: String): PdmResponse<JsonElement> {
        synchronized(lock) { demoSnapshots["$remote/$type/$vmid"]?.removeAll { it.name == name } }
        return PdmResponse(data = JsonPrimitive("demo:$remote:UPID:delsnapshot:$vmid"))
    }

    private fun power(
        remote: String,
        type: String,
        vmid: Long,
        newStatus: String,
        action: String,
    ): PdmResponse<JsonElement> {
        synchronized(lock) {
            val idx = guests.indexOfFirst { it.remoteId == remote && it.type == type && it.vmid.toLong() == vmid }
            if (idx < 0) {
                throw IllegalStateException("no such guest $remote/$type/$vmid")
            }
            guests[idx] = guests[idx].copy(status = newStatus)
        }
        return PdmResponse(data = JsonPrimitive("demo:$remote:UPID:$action:$vmid"))
    }

    override suspend fun hostAptUpdate(node: String): PdmResponse<String> =
        PdmResponse(data = "UPID:$node:00000001:00000001:66000000:aptupdate::root@pam:")

    override suspend fun hostAptChangelog(node: String, name: String): PdmResponse<String> =
        PdmResponse(data = "$name (demo) unstable; urgency=medium\n\n  * Demo changelog for $name on $node.\n")

    override suspend fun hostTasks(
        node: String,
        start: Long?,
        limit: Int?,
        errors: Boolean?,
        running: Boolean?,
        userfilter: String?,
        since: Long?,
        until: Long?,
        typefilter: String?,
        statusfilter: String?,
        liveOnly: String?,
    ): Response<PdmResponse<List<RemoteTaskDto>>> = Response.success(demoHostTasks(node, start, limit, errors, running, userfilter, typefilter))

    private fun demoHostTasks(
        node: String,
        start: Long?,
        limit: Int?,
        errors: Boolean?,
        running: Boolean?,
        userfilter: String?,
        typefilter: String?,
    ): PdmResponse<List<RemoteTaskDto>> {
        val now = System.currentTimeMillis() / 1000
        val hostTasks = listOf(
            RemoteTaskDto(
                upid = "UPID:$node:00000001:00000001:66000000:aptupdate::root@pam:",
                node = node,
                workerType = "aptupdate",
                workerId = "",
                user = "root@pam",
                startTime = now - 600,
                endTime = now - 580,
                status = "OK",
            ),
            RemoteTaskDto(
                upid = "UPID:$node:00000002:00000002:66000010:srvreload:pdm-api:root@pam:",
                node = node,
                workerType = "srvreload",
                workerId = "pdm-api",
                user = "root@pam",
                startTime = now - 3600,
                endTime = now - 3590,
                status = "OK",
            ),
        )
        val filtered = hostTasks.filter { task ->
            (running != true || task.status == null) &&
                (errors != true || (task.status != null && task.status != "OK")) &&
                (userfilter.isNullOrBlank() || task.user.contains(userfilter, ignoreCase = true)) &&
                (typefilter.isNullOrBlank() || task.workerType.contains(typefilter, ignoreCase = true))
        }
        val dropCount = (start ?: 0L).toInt().coerceAtLeast(0)
        val takeLimit = (limit ?: 30).coerceAtLeast(0)
        val sliced = filtered.drop(dropCount).take(takeLimit)
        return PdmResponse(data = sliced, total = filtered.size.toLong())
    }

    override suspend fun hostTaskStatus(node: String, upid: String, liveOnly: String?): PdmResponse<TaskStatus> =
        PdmResponse(data = TaskStatus(status = "stopped", exitstatus = "OK", upid = upid, node = node))

    override suspend fun hostTaskLog(
        node: String,
        upid: String,
        start: Int,
        limit: Int,
    ): PdmResponse<List<TaskLogLine>> =
        PdmResponse(
            data = listOf(
                TaskLogLine(1, "Starting host task $upid on $node"),
                TaskLogLine(2, "Reading package lists... Done"),
                TaskLogLine(3, "Building dependency tree... Done"),
                TaskLogLine(4, "All packages are up to date."),
                TaskLogLine(5, "TASK OK"),
            ).drop(start).take(limit),
            total = 5L,
        )

    override suspend fun stopHostTask(node: String, upid: String): PdmResponse<JsonElement> =
        PdmResponse(data = JsonNull)

    override suspend fun hostSyslog(
        node: String,
        start: Int?,
        limit: Int?,
        service: String?,
        since: String?,
        until: String?,
    ): PdmResponse<List<SyslogLineDto>> =
        PdmResponse(
            data = listOf(
                SyslogLineDto(1, "systemd[1]: Started Proxmox Datacenter Manager API Server."),
                SyslogLineDto(2, "pdm-api[1234]: server listening on 0.0.0.0:8443"),
                SyslogLineDto(3, "pdm-api[1234]: successful auth for root@pam"),
            ),
            total = 3L,
        )

    override suspend fun hostJournal(
        node: String,
        lastentries: Int?,
        since: Long?,
        until: Long?,
        service: String?,
        priority: String?,
        startcursor: String?,
        endcursor: String?,
        unit: String?,
    ): ResponseBody {
        // The same shape mini-journalreader streams: first cursor, lines, last cursor.
        val lines = if (startcursor != null) {
            emptyList()
        } else {
            listOf(
                "Oct 24 09:00:01 demo systemd[1]: Started proxmox-datacenter-api.service - Proxmox Datacenter Manager API Server.",
                "Oct 24 09:00:02 demo proxmox-datacenter-api[1234]: server listening on [::]:8443",
                "Oct 24 09:00:05 demo proxmox-datacenter-api[1234]: successful auth for user 'root@pam'",
            )
        }
        val cursor = startcursor ?: "s=demo;i=1"
        val data = buildList {
            add(cursor)
            addAll(lines)
            add(if (lines.isEmpty()) cursor else "s=demo;i=3")
        }
        val body = kotlinx.serialization.json.buildJsonObject {
            put("data", kotlinx.serialization.json.JsonArray(data.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("success", kotlinx.serialization.json.JsonPrimitive(1))
        }
        return body.toString().toResponseBody("application/json".toMediaType())
    }

    override suspend fun hostRrd(
        node: String,
        timeframe: String,
        cf: String,
    ): PdmResponse<List<JsonObject>> =
        PdmResponse(data = demoRrd(seed = 42, timeframe = timeframe))

    private fun sha256Hex(text: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private var currentNotes = "# Datacenter Operations\n\n- Primary cluster: `pve-cluster-a`\n- Backup window: Daily 02:00-04:00 UTC\n- Contact: ops-team@example.invalid"
    private var currentNotesDigest = sha256Hex(currentNotes)

    override suspend fun getNotes(): PdmResponse<String> =
        PdmResponse(
            data = currentNotes,
            digest = currentNotesDigest,
        )

    override suspend fun putNotes(notes: String, digest: String?): PdmResponse<JsonElement> {
        if (digest != null && digest != currentNotesDigest) {
            val response = okhttp3.Response.Builder()
                .code(409)
                .message("Conflict (digest mismatch)")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("https://localhost/api2/json/config/notes").build())
                .build()
            throw HttpException(retrofit2.Response.error<Any>("{\"message\":\"digest mismatch\"}".toResponseBody(null), response))
        }
        currentNotes = notes
        currentNotesDigest = sha256Hex(notes)
        return PdmResponse(data = JsonNull, digest = currentNotesDigest)
    }

    override suspend fun remoteClusterOptions(remote: String): PdmResponse<JsonObject> =
        PdmResponse(data = JsonObject(mapOf("description" to JsonPrimitive("Production cluster $remote (demo)"))))

    override suspend fun remoteNodeConfig(remote: String, node: String): PdmResponse<JsonObject> =
        PdmResponse(data = JsonObject(mapOf("description" to JsonPrimitive("Virtualization hypervisor node $node on $remote"))))
}
