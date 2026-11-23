package com.pdm.app.data

import com.pdm.app.data.api.PdmAuth
import com.pdm.app.data.model.DashboardMetrics
import com.pdm.app.data.model.FleetDatastore
import com.pdm.app.data.model.FleetNetwork
import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.FleetStorage
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.LeaderEntry
import com.pdm.app.data.model.Leaderboards
import com.pdm.app.data.model.ClusterNodeInfo
import com.pdm.app.data.model.ClusterStatusDto
import com.pdm.app.data.model.GuestSnapshot
import com.pdm.app.data.model.asFlag
import com.pdm.app.data.model.PdmRemote
import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.RemoteEndpoint
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.data.model.RemoteResourcesGroup
import com.pdm.app.data.model.RemoteTask
import com.pdm.app.data.model.RemoteTaskDto
import com.pdm.app.data.model.TaskOrigin
import com.pdm.app.data.model.ResourcesStatus
import com.pdm.app.data.model.NodeUpdates
import com.pdm.app.data.model.RrdPoint
import com.pdm.app.data.model.UpdateSummaryDto
import com.pdm.app.data.model.SnapshotDto
import com.pdm.app.data.model.TaskPhase
import com.pdm.app.data.model.TopEntities
import com.pdm.app.data.model.TopEntityDto
import com.pdm.app.data.model.VmEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

object FleetMapper {
    fun remotesFromApi(list: List<PdmRemote>): List<RemoteNode> =
        list.map { remote ->
            RemoteNode(
                id = remote.id,
                name = remote.id,
                kind = remote.kind,
                endpoints = remote.nodeStrings.map(::parseRemoteNode),
            )
        }

    /** `host[:port][,fingerprint=..]`, the keyed `hostname=..` form, and bracketed IPv6. */
    fun parseRemoteNode(raw: String): RemoteEndpoint {
        var hostPort = ""
        var fingerprint: String? = null
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, part ->
            val key = part.substringBefore('=', missingDelimiterValue = "").trim()
            val value = part.substringAfter('=').trim()
            when {
                key.equals("hostname", ignoreCase = true) -> hostPort = value
                key.equals("fingerprint", ignoreCase = true) -> fingerprint = value.ifBlank { null }
                key.isEmpty() && index == 0 -> hostPort = part
            }
        }
        val parsed = PdmAuth.parseHostPort(hostPort, defaultPort = NO_PORT)
        return RemoteEndpoint(
            host = parsed.host,
            port = parsed.port.takeIf { it != NO_PORT },
            fingerprint = fingerprint,
        )
    }

    private const val NO_PORT = -1

    fun guestsFromResources(groups: List<RemoteResourcesGroup>): List<Guest> =
        groups.flatMap { group ->
            group.resources.mapNotNull { resource ->
                if (!resource.isGuest || resource.isTemplate) return@mapNotNull null
                val vmid = resource.vmid?.toInt() ?: return@mapNotNull null
                val type = resource.kind
                Guest(
                    id = "${group.remote}/$type/$vmid",
                    remoteId = group.remote,
                    name = resource.name?.takeIf { it.isNotBlank() } ?: "$type $vmid",
                    type = type,
                    vmid = vmid,
                    status = resource.status ?: "unknown",
                    node = resource.node?.takeIf { it.isNotBlank() },
                    cpu = resource.cpu,
                    maxcpu = resource.maxcpu,
                    mem = resource.mem,
                    maxmem = resource.maxmem,
                    disk = resource.disk,
                    maxdisk = resource.maxdisk,
                    tags = parseTags(resource.tags),
                )
            }
        }

    /** PDM sends a JSON list; PVE's own API sends `a;b`. Accept both, keep the operator's spelling. */
    fun parseTags(raw: JsonElement?): List<String> = when (raw) {
        is kotlinx.serialization.json.JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> raw.contentOrNull.orEmpty().split(';', ',', ' ')
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    fun nodesFromResources(groups: List<RemoteResourcesGroup>): List<FleetNode> =
        groups.flatMap { group ->
            group.resources.mapNotNull { resource ->
                if (!resource.isNode) return@mapNotNull null
                val name = resource.node?.takeIf { it.isNotBlank() }
                    ?: resource.name?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                FleetNode(
                    id = "${group.remote}/node/$name",
                    remoteId = group.remote,
                    name = name,
                    status = resource.status ?: "unknown",
                    cpu = resource.cpu,
                    maxcpu = resource.maxcpu,
                    mem = resource.mem,
                    maxmem = resource.maxmem,
                    uptime = resource.uptime,
                    haState = resource.haState,
                )
            }
        }

    fun storagesFromResources(groups: List<RemoteResourcesGroup>): List<FleetStorage> =
        groups.flatMap { group ->
            group.resources.mapNotNull { resource ->
                if (!resource.isStorage) return@mapNotNull null
                val name = resource.storage?.takeIf { it.isNotBlank() }
                    ?: resource.name
                    ?: return@mapNotNull null
                FleetStorage(
                    id = resource.id ?: "${group.remote}/storage/${resource.node}/$name",
                    remoteId = group.remote,
                    name = name,
                    node = resource.node,
                    status = resource.status ?: "unknown",
                    disk = resource.disk,
                    maxdisk = resource.maxdisk,
                    shared = resource.shared == true,
                )
            }
        }

    fun datastoresFromResources(groups: List<RemoteResourcesGroup>): List<FleetDatastore> =
        groups.flatMap { group ->
            group.resources.mapNotNull { resource ->
                if (!resource.isDatastore) return@mapNotNull null
                val name = resource.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FleetDatastore(
                    id = resource.id ?: "${group.remote}/datastore/$name",
                    remoteId = group.remote,
                    name = name,
                    disk = resource.disk,
                    maxdisk = resource.maxdisk,
                    usage = resource.usage,
                    maintenance = resource.maintenance,
                )
            }
        }

    fun networksFromResources(groups: List<RemoteResourcesGroup>): List<FleetNetwork> =
        groups.flatMap { group ->
            group.resources.mapNotNull { resource ->
                if (!resource.isNetwork) return@mapNotNull null
                val name = resource.network?.takeIf { it.isNotBlank() }
                    ?: resource.name
                    ?: return@mapNotNull null
                FleetNetwork(
                    id = resource.id ?: "${group.remote}/network/$name",
                    remoteId = group.remote,
                    name = name,
                    node = resource.node,
                    status = resource.status ?: "unknown",
                    zoneType = resource.zoneType ?: resource.networkType,
                )
            }
        }

    fun guestsFromVmList(remoteId: String, type: String, list: List<VmEntry>): List<Guest> =
        list.mapNotNull { entry ->
            if (entry.isTemplate) return@mapNotNull null
            Guest(
                id = "$remoteId/$type/${entry.vmid}",
                remoteId = remoteId,
                name = entry.name?.takeIf { it.isNotBlank() } ?: "$type ${entry.vmid}",
                type = type,
                vmid = entry.vmid.toInt(),
                status = entry.status ?: "unknown",
                node = entry.node,
            )
        }

    fun applyRemoteHealth(
        remotes: List<RemoteNode>,
        status: ResourcesStatus?,
        groupErrors: Map<String, String>,
    ): List<RemoteNode> {
        val healthByName = status?.remoteList.orEmpty().associateBy { it.name }
        val failed = status?.failedRemotesList.orEmpty().associateBy { it.name }
        val ids = remotes.map { it.id }.toSet()
        val merged = remotes.map { remote ->
            val info = healthByName[remote.id]
            val fail = failed[remote.id]
            remote.copy(
                error = groupErrors[remote.id] ?: remote.error ?: fail?.error ?: info?.messages?.firstOrNull(),
                health = info?.status ?: if (fail != null) "Error" else remote.health,
            )
        }
        val extras = failed.values.filter { it.name !in ids }.map { fail ->
            RemoteNode(
                id = fail.name,
                name = fail.name,
                kind = fail.remoteType ?: "pve",
                error = fail.error,
                health = "Error",
            )
        }
        return merged + extras
    }

    fun dashboard(
        remotes: List<RemoteNode>,
        guests: List<Guest>,
        nodes: List<FleetNode> = emptyList(),
        storages: List<FleetStorage> = emptyList(),
        datastores: List<FleetDatastore> = emptyList(),
        networks: List<FleetNetwork> = emptyList(),
        status: ResourcesStatus? = null,
    ): DashboardMetrics {
        val nodeNames = nodes.map { it.name }.toSet().ifEmpty {
            guests.mapNotNull { it.node?.takeIf { n -> n.isNotBlank() } }.toSet()
        }
        val fromLists = DashboardMetrics(
            pveRemotes = remotes.count { it.kind == "pve" },
            pbsRemotes = remotes.count { it.kind == "pbs" },
            remoteErrors = remotes.count { !it.error.isNullOrBlank() || it.health.equals("Error", true) },
            guestsRunning = guests.count { it.status.equals("running", ignoreCase = true) },
            guestsStopped = guests.count { it.status.equals("stopped", ignoreCase = true) },
            qemu = guests.count { it.type == "qemu" },
            lxc = guests.count { it.type == "lxc" },
            nodes = nodeNames.size,
            nodesOnline = nodes.count { it.status.equals("online", ignoreCase = true) },
            nodesOffline = nodes.count { it.status.equals("offline", ignoreCase = true) },
            pveCpuUsed = nodes.mapNotNull { n ->
                val cpu = n.cpu ?: return@mapNotNull null
                val max = n.maxcpu ?: 1.0
                cpu * max
            }.takeIf { it.isNotEmpty() }?.sum(),
            pveCpuMax = nodes.mapNotNull { it.maxcpu }.takeIf { it.isNotEmpty() }?.sum(),
            pveMemUsed = nodes.mapNotNull { it.mem }.takeIf { it.isNotEmpty() }?.sum(),
            pveMemTotal = nodes.mapNotNull { it.maxmem }.takeIf { it.isNotEmpty() }?.sum(),
            pveStorageUsed = storages.mapNotNull { it.disk }.takeIf { it.isNotEmpty() }?.sum(),
            pveStorageTotal = storages.mapNotNull { it.maxdisk }.takeIf { it.isNotEmpty() }?.sum(),
            storages = storages.size,
            sdnAvailable = networks.count { it.status.equals("available", ignoreCase = true) },
            sdnError = networks.count { it.status.equals("error", ignoreCase = true) },
            datastores = datastores.size,
        )
        if (status == null) return fromLists
        return fromLists.copy(
            remoteErrors = status.failedRemotes.toInt().coerceAtLeast(fromLists.remoteErrors),
            guestsRunning = (status.qemu.running + status.lxc.running).toInt(),
            guestsStopped = (status.qemu.stopped + status.lxc.stopped).toInt(),
            qemu = (status.qemu.running + status.qemu.stopped + status.qemu.unknown).toInt(),
            lxc = (status.lxc.running + status.lxc.stopped + status.lxc.unknown).toInt(),
            nodes = (
                status.pveNodes.online + status.pveNodes.offline + status.pveNodes.unknown +
                    status.pbsNodes.online + status.pbsNodes.offline + status.pbsNodes.unknown
                ).toInt(),
            nodesOnline = (status.pveNodes.online + status.pbsNodes.online).toInt(),
            nodesOffline = (status.pveNodes.offline + status.pbsNodes.offline).toInt(),
            pveCpuUsed = status.pveCpuStats.used,
            pveCpuMax = status.pveCpuStats.max,
            pveCpuAllocated = status.pveCpuStats.allocated,
            pveMemUsed = status.pveMemoryStats.used,
            pveMemTotal = status.pveMemoryStats.total,
            pveStorageUsed = status.pveStorageStats.used,
            pveStorageTotal = status.pveStorageStats.total,
            storages = (status.storages.available + status.storages.unknown).toInt(),
            sdnAvailable = status.sdnZones.available.toInt(),
            sdnError = status.sdnZones.error.toInt(),
            datastores = status.pbsDatastores.online.toInt(),
        )
    }

    /** `null` when both task reads failed. Running rows stay even if they fell out of the recent page. */
    fun tasksFromApi(recent: List<RemoteTaskDto>?, running: List<RemoteTaskDto>?): List<RemoteTask>? {
        if (recent == null && running == null) return null
        return (running.orEmpty() + recent.orEmpty())
            .map(::taskFromDto)
            .distinctBy { it.upid }
            .sortedWith(
                compareByDescending<RemoteTask> { it.phase == TaskPhase.Running }
                    .thenByDescending { it.startTime },
            )
    }

    fun originFromTaskUpid(upid: String, node: String): TaskOrigin {
        val head = upid.substringBefore('!', missingDelimiterValue = "")
        if (head.isEmpty()) return TaskOrigin.Host(node)
        val kind = if (head.startsWith("pbs:")) "pbs" else "pve"
        val rId = if (head.contains(':')) head.substringAfter(':') else head
        return TaskOrigin.Remote(kind, rId)
    }

    fun taskFromDto(dto: RemoteTaskDto): RemoteTask {
        val origin = originFromTaskUpid(dto.upid, dto.node)
        val rId = if (origin is TaskOrigin.Remote) origin.remoteId else ""
        return RemoteTask(
            upid = dto.upid,
            remoteId = rId,
            node = dto.node,
            workerType = dto.workerType.ifBlank { "task" },
            workerId = dto.workerId?.takeIf { it.isNotBlank() },
            user = dto.user,
            startTime = dto.startTime,
            endTime = dto.endTime,
            status = dto.status?.takeIf { it.isNotBlank() },
            origin = origin,
        )
    }

    fun hostTaskFromDto(node: String, dto: RemoteTaskDto): RemoteTask = RemoteTask(
        upid = dto.upid,
        remoteId = "",
        node = node.ifBlank { dto.node },
        workerType = dto.workerType.ifBlank { "task" },
        workerId = dto.workerId?.takeIf { it.isNotBlank() },
        user = dto.user,
        startTime = dto.startTime,
        endTime = dto.endTime,
        status = dto.status?.takeIf { it.isNotBlank() },
        origin = TaskOrigin.Host(node.ifBlank { dto.node }),
    )

    /** `pve:name!UPID…` (current) and `name!UPID…` (older cache rows). */
    fun remoteIdFromTaskUpid(upid: String): String {
        val head = upid.substringBefore('!', missingDelimiterValue = "")
        if (head.isEmpty()) return ""
        return if (head.startsWith("pve:") || head.startsWith("pbs:")) {
            head.substringAfter(':')
        } else {
            head
        }
    }

    fun leaderboards(top: TopEntities?): Leaderboards {
        if (top == null) return Leaderboards()
        // PDM 1.1 returns top-entities lowest-first; rank here so "top 5" really is the busiest five.
        fun rank(list: List<TopEntityDto>) = list.map { it.toEntry() }
            .sortedByDescending { it.average ?: it.last ?: -1.0 }
        return Leaderboards(
            guestCpu = rank(top.guestCpu),
            nodeCpu = rank(top.nodeCpu),
            nodeMemory = rank(top.nodeMemory),
        )
    }

    private fun TopEntityDto.toEntry(): LeaderEntry {
        val kind = resource.kind.ifBlank { resource.type.orEmpty() }
        val label = resource.name?.takeIf { it.isNotBlank() }
            ?: resource.node?.takeIf { it.isNotBlank() }
            ?: resource.id.orEmpty()
        val entityId = when {
            resource.isGuest && resource.vmid != null -> "$remote/${resource.kind}/${resource.vmid}"
            resource.isNode -> {
                val nodeName = resource.node?.takeIf { it.isNotBlank() }
                    ?: resource.name?.takeIf { it.isNotBlank() }
                nodeName?.let { "$remote/node/$it" }
            }
            else -> resource.id
        }
        return LeaderEntry(
            remoteId = remote,
            name = label,
            kind = kind,
            last = rrdData.data.asReversed().firstOrNull { it != null },
            series = downsample(rrdData.data),
            entityId = entityId,
            average = rrdData.data.filterNotNull().takeIf { it.isNotEmpty() }?.average(),
        )
    }

    fun downsample(values: List<Double?>, target: Int = 72): List<Float?> {
        if (values.isEmpty()) return emptyList()
        if (values.size <= target) return values.map { it?.toFloat() }
        val bucket = values.size.toDouble() / target
        return List(target) { i ->
            val start = (i * bucket).roundToInt().coerceIn(0, values.lastIndex)
            val end = (((i + 1) * bucket).roundToInt().coerceAtMost(values.size)).coerceAtLeast(start + 1)
            val slice = values.subList(start, end).mapNotNull { it }
            if (slice.isEmpty()) null else slice.average().toFloat()
        }
    }

    fun clusterNodes(rows: List<ClusterStatusDto>): List<ClusterNodeInfo> =
        rows.filter { it.type == "node" }.map {
            ClusterNodeInfo(
                name = it.name,
                ip = it.ip?.takeIf { ip -> ip.isNotBlank() },
                online = it.online.asFlag() == true,
                local = it.local.asFlag() == true,
            )
        }.sortedBy { it.name }

    /** Flatten the per-remote map; busiest nodes first, then by name. */
    fun updatesFromApi(summary: UpdateSummaryDto): List<NodeUpdates> =
        summary.remotes.flatMap { (remote, entry) ->
            entry.nodes.map { (node, n) ->
                NodeUpdates(
                    remoteId = remote,
                    node = node,
                    pending = n.numberOfUpdates,
                    repositoryStatus = n.repositoryStatus,
                    lastRefresh = n.lastRefresh,
                    version = n.versions.firstOrNull {
                        it.`package` == "pve-manager" || it.`package` == "proxmox-backup-server"
                    }?.version,
                    error = (n.statusMessage ?: entry.statusMessage)
                        ?.takeIf { n.status.equals("error", true) || entry.status.equals("error", true) },
                )
            }
        }.sortedWith(compareByDescending<NodeUpdates> { it.pending }.thenBy { it.remoteId }.thenBy { it.node })

    fun permissionsFromApi(raw: Map<String, JsonObject>): Permissions =
        Permissions(
            raw.mapValues { (_, privs) ->
                privs.mapValues { (_, v) ->
                    val p = v as? JsonPrimitive
                    p?.booleanOrNull ?: (p?.intOrNull?.let { it != 0 }) ?: false
                }
            },
        )

    /** Drops the `current` pseudo entry and marks the snapshot it hangs off. Newest first. */
    fun snapshotsFromApi(list: List<SnapshotDto>): List<GuestSnapshot> {
        val currentParent = list.firstOrNull { it.name == "current" }?.parent
        return list.filter { it.name.isNotBlank() && it.name != "current" }.map { dto ->
            val vm = dto.vmstate as? JsonPrimitive
            GuestSnapshot(
                name = dto.name,
                description = dto.description.orEmpty().trim(),
                time = dto.snaptime,
                parent = dto.parent,
                withRam = vm?.booleanOrNull == true || (vm?.intOrNull ?: 0) != 0,
                isCurrentBase = dto.name == currentParent,
            )
        }.sortedByDescending { it.time ?: 0L }
    }

    /** `rrddata` rows to points. Rows without `time` are dropped; non-numeric fields are ignored. */
    fun rrdFromJson(rows: List<JsonObject>): List<RrdPoint> =
        rows.mapNotNull { row ->
            val time = (row["time"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
                ?: return@mapNotNull null
            val values = row.mapNotNull { (key, value) ->
                if (key == "time") return@mapNotNull null
                val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toDoubleOrNull()
                number?.let { key to it }
            }.toMap()
            RrdPoint(time, values)
        }.sortedBy { it.time }

    fun asUpidString(element: JsonElement?): String {
        if (element == null || element is JsonNull) return ""
        if (element is JsonPrimitive) return element.content
        if (element is JsonObject) {
            return element["upid"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: element["data"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: ""
        }
        // Anything else is not a task id; polling it would only 400 and mask a successful action.
        return ""
    }
}
