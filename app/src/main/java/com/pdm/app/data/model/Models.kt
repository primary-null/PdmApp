package com.pdm.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Standard Proxmox JSON envelope used by PDM as well as PVE. */
@Serializable
data class PdmResponse<T>(
    val data: T? = null,
    val errors: Map<String, String>? = null,
    val message: String? = null,
    val total: Long? = null,
    val digest: String? = null,
    /**
     * Set only by the app's offline cache when it answered instead of the server: when that answer
     * was saved (epoch ms). Null on every live answer. Lets each read say for itself how old it is.
     */
    @SerialName(com.pdm.app.data.cache.CACHED_AT_FIELD) val cachedAt: Long? = null,
)

@Serializable
data class TicketInfo(
    val ticket: String? = null,
    @SerialName("ticket-info") val ticketInfo: String? = null,
    @SerialName("ticket_info") val ticketInfoSnake: String? = null,
    @SerialName("CSRFPreventionToken") val csrfPreventionToken: String? = null,
    @SerialName("csrfprevention-token") val csrfKebab: String? = null,
    val username: String? = null,
    @SerialName("NeedTFA") val needTfa: Int? = null,
) {
    val csrf: String?
        get() = csrfPreventionToken ?: csrfKebab

    val infoTicket: String?
        get() = ticketInfo ?: ticketInfoSnake
}

@Serializable
data class VersionInfo(
    val version: String? = null,
    val release: String? = null,
    val repoid: String? = null,
) {
    val display: String
        get() = listOfNotNull(version, release).joinToString("-").ifBlank { "unknown" }
}

@Serializable
data class PdmRemote(
    val id: String = "",
    @SerialName("type") val type: String? = null,
    val ty: String? = null,
    /** Property strings (`host:port,fingerprint=..`) on 1.1; tolerate `{hostname, fingerprint}` objects too. */
    val nodes: List<JsonElement> = emptyList(),
) {
    val kind: String
        get() = (type ?: ty ?: "pve").lowercase()

    val nodeStrings: List<String>
        get() = nodes.mapNotNull { node ->
            when (node) {
                is JsonPrimitive -> node.contentOrNull
                is JsonObject -> listOfNotNull(
                    (node["hostname"] as? JsonPrimitive)?.contentOrNull?.let { "hostname=$it" },
                    (node["fingerprint"] as? JsonPrimitive)?.contentOrNull?.let { "fingerprint=$it" },
                ).joinToString(",").ifBlank { null }
                else -> null
            }
        }
}

@Serializable
data class RemoteResourcesGroup(
    val remote: String,
    val error: String? = null,
    val resources: List<PdmResource> = emptyList(),
)

@Serializable
data class PdmResource(
    val type: String? = null,
    val id: String? = null,
    val vmid: Long? = null,
    val name: String? = null,
    val node: String? = null,
    val status: String? = null,
    val tags: JsonElement? = null,
    val template: JsonElement? = null,
    val cpu: Double? = null,
    val maxcpu: Double? = null,
    val mem: Long? = null,
    val maxmem: Long? = null,
    val disk: Long? = null,
    val maxdisk: Long? = null,
    val uptime: Long? = null,
    val pool: String? = null,
    val storage: String? = null,
    val shared: Boolean? = null,
    val network: String? = null,
    @SerialName("network_type") val networkType: String? = null,
    @SerialName("zone-type") val zoneType: String? = null,
    val level: String? = null,
    val usage: Double? = null,
    val maintenance: String? = null,
    @SerialName("ha-state") val haState: String? = null,
    @SerialName("backing-device") val backingDevice: String? = null,
    @SerialName("backend-type") val backendType: String? = null,
) {
    val kind: String get() = resourceKind(type)

    val isGuest: Boolean get() = kind == "qemu" || kind == "lxc"

    val isNode: Boolean get() = kind == "node"

    val isStorage: Boolean get() = kind == "storage"

    val isDatastore: Boolean get() = kind == "datastore"

    val isNetwork: Boolean get() = kind == "network"

    val isTemplate: Boolean
        get() = when (val t = template) {
            is JsonPrimitive -> t.booleanOrNull == true || t.intOrNull == 1 || t.contentOrNull == "1"
            else -> false
        }
}

fun resourceKind(type: String?): String = when (type?.lowercase()) {
    "qemu", "pve-qemu" -> "qemu"
    "lxc", "pve-lxc" -> "lxc"
    "node", "pve-node", "pbs-node" -> "node"
    "storage", "pve-storage" -> "storage"
    "datastore", "pbs-datastore" -> "datastore"
    "network", "pve-network" -> "network"
    else -> type?.lowercase().orEmpty()
}

@Serializable
data class VmEntry(
    val vmid: Long,
    val name: String? = null,
    val status: String? = null,
    val node: String? = null,
    val tags: JsonElement? = null,
    val template: JsonElement? = null,
) {
    val isTemplate: Boolean
        get() = when (val t = template) {
            is JsonPrimitive -> t.booleanOrNull == true || t.intOrNull == 1 || t.contentOrNull == "1"
            else -> false
        }
}

@Serializable
data class TaskStatus(
    val status: String? = null,
    val exitstatus: String? = null,
    val type: String? = null,
    val id: String? = null,
    val user: String? = null,
    val node: String? = null,
    val upid: String? = null,
) {
    val isRunning: Boolean get() = status.equals("running", ignoreCase = true)
    val isOk: Boolean get() = exitstatus.equals("OK", ignoreCase = true)
}

/** One task log line from `.../tasks/{upid}/log`. */
@Serializable
data class TaskLogLine(
    val n: Long = 0,
    val t: String = "",
)

/** One `rrddata` sample. `values` keeps whatever numeric fields the endpoint returned. */
data class RrdPoint(
    val time: Long,
    val values: Map<String, Double>,
)

fun List<RrdPoint>.series(field: String): List<Float?> = map { it.values[field]?.toFloat() }

/** `rrddata` field names as PDM 1.1 sends them (kebab-case). */
object RrdField {
    const val CPU = "cpu-current"
    const val CPU_MAX = "cpu-max"
    const val LOAD1 = "cpu-avg1"
    const val MEM_USED = "mem-used"
    const val MEM_TOTAL = "mem-total"
    const val NET_IN = "net-in"
    const val NET_OUT = "net-out"
    const val DISK_READ = "disk-read"
    const val DISK_WRITE = "disk-write"
    const val DISK_USED = "disk-used"
    const val DISK_TOTAL = "disk-total"
    const val SWAP_USED = "swap-used"
    const val SWAP_TOTAL = "swap-total"
    const val IO_WAIT = "cpu-iowait"
}

/** used/total per point; null where either side is missing. */
fun List<RrdPoint>.ratio(used: String, total: String): List<Float?> = map { p ->
    val u = p.values[used]
    val t = p.values[total]
    if (u == null || t == null || t <= 0.0) null else (u / t).toFloat()
}

/** Last non-null, mean and max of a series, for chart captions. */
data class SeriesStats(val last: Float?, val avg: Float?, val max: Float?)

fun List<Float?>.stats(): SeriesStats {
    val nums = filterNotNull()
    return SeriesStats(lastOrNull { it != null }, nums.takeIf { it.isNotEmpty() }?.average()?.toFloat(), nums.maxOrNull())
}

/** `rrddata` timeframes PDM accepts, with the short labels the dashboard uses. */
enum class RrdTimeframe(val apiName: String, val label: String) {
    HOUR("hour", "1h"),
    DAY("day", "1d"),
    WEEK("week", "1w"),
    MONTH("month", "1m"),
    YEAR("year", "1y"),
}

enum class AuthMode {
    PASSWORD,
    API_TOKEN,
}

enum class GuestAction(val apiName: String, val label: String) {
    START("start", "Start"),
    STOP("stop", "Stop"),
    SHUTDOWN("shutdown", "Shutdown"),
    /** QEMU only: continue a paused or suspended VM. */
    RESUME("resume", "Resume"),
}

/** Live `.../{qemu|lxc}/{vmid}/status`. `qmpstatus` is the only place a paused VM shows up. */
@Serializable
data class GuestStatusDto(
    val status: String? = null,
    val qmpstatus: String? = null,
    val lock: String? = null,
    val uptime: Long? = null,
) {
    /** Paused / suspended to RAM: the VM process exists but does not run. */
    val isPaused: Boolean
        get() = qmpstatus?.lowercase() in setOf("paused", "suspended", "prelaunch")
}

@Serializable
data class SnapshotDto(
    val name: String = "",
    val description: String? = null,
    val snaptime: Long? = null,
    val parent: String? = null,
    val vmstate: JsonElement? = null,
)

data class GuestSnapshot(
    val name: String,
    val description: String,
    val time: Long?,
    val parent: String?,
    val withRam: Boolean,
    /** The snapshot the guest currently runs on top of (parent of the `current` pseudo entry). */
    val isCurrentBase: Boolean,
)

/** Snapshot names PVE accepts: a letter, then letters, digits, `-` or `_`, 2-40 chars. */
fun isValidSnapshotName(name: String): Boolean =
    Regex("^[A-Za-z][A-Za-z0-9_-]{1,39}$").matches(name) && !name.equals("current", ignoreCase = true)

/**
 * `GET access/permissions`: path -> privilege -> propagates. A privilege applies on its own path,
 * and on every child path when it propagates.
 */
class Permissions(private val paths: Map<String, Map<String, Boolean>>) {
    fun allows(privilege: String, path: String): Boolean {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        paths[canonical(parts)]?.let { if (privilege in it) return true }
        for (depth in parts.size - 1 downTo 0) {
            if (paths[canonical(parts.take(depth))]?.get(privilege) == true) return true
        }
        return false
    }

    private fun canonical(parts: List<String>) = "/" + parts.joinToString("/")

    companion object {
        fun of(paths: Map<String, Set<String>>) = Permissions(
            paths.mapValues { (_, privs) -> privs.associateWith { true } }
        )

        const val RESOURCE_MANAGE = "Resource.Manage"
        const val RESOURCE_MIGRATE = "Resource.Migrate"
        const val RESOURCE_MODIFY = "Resource.Modify"
        const val SYS_CONSOLE = "Sys.Console"
        const val SYSTEM_MODIFY = "System.Modify"
        const val SYSTEM_AUDIT = "System.Audit"

        fun guestPath(remote: String, vmid: Int) = "/resource/$remote/guest/$vmid"

        fun remotePath(remote: String) = "/resource/$remote"

        fun nodeSystemPath(remote: String, node: String) = "/resource/$remote/node/$node/system"

        fun nodePath(remote: String, node: String) = "/resource/$remote/node/$node"
    }
}

/** Unknown permissions (read failed) leave controls visible; PDM still enforces on the call. */
fun Permissions?.canManageGuest(remote: String, vmid: Int): Boolean =
    this?.allows(Permissions.RESOURCE_MANAGE, Permissions.guestPath(remote, vmid)) ?: true

fun Permissions?.canMigrateGuest(remote: String, vmid: Int): Boolean =
    this?.allows(Permissions.RESOURCE_MIGRATE, Permissions.guestPath(remote, vmid)) ?: true

fun Permissions?.canManageRemote(remote: String): Boolean =
    this?.allows(Permissions.RESOURCE_MANAGE, Permissions.remotePath(remote)) ?: true

/** PDM's apt routes (list, refresh, changelog) all ask for Resource.Modify on the node's system path. */
fun Permissions?.canManageNodeUpdates(remote: String, node: String): Boolean =
    this?.allows(Permissions.RESOURCE_MODIFY, Permissions.nodeSystemPath(remote, node)) ?: true

/** PDM relays a node shell only with Sys.Console on the node. */
fun Permissions?.canOpenNodeShell(remote: String, node: String): Boolean =
    this?.allows(Permissions.SYS_CONSOLE, Permissions.nodePath(remote, node)) ?: true

/** Restarting the PDM machine needs Sys.PowerManagement on /system/status. */
fun Permissions?.canRestartHost(): Boolean =
    this?.allows("Sys.PowerManagement", "/system/status") ?: true

/** The PDM host's own shell needs Sys.Console on /system. */
fun Permissions?.canOpenHostShell(): Boolean =
    this?.allows(Permissions.SYS_CONSOLE, "/system") ?: true

/** Stopping a remote task requires Resource.Manage on the remote. Stopping a host task requires System.Modify on /system/tasks (or being the task owner/token-owner). */
fun Permissions?.canStopTask(task: RemoteTask, currentUser: String? = null): Boolean = when (task.origin) {
    is TaskOrigin.Host -> {
        val taskOwner = task.user.ifBlank {
            task.upid.split(':').getOrNull(7).orEmpty()
        }
        val isOwner = if (currentUser.isNullOrBlank() || taskOwner.isBlank()) {
            false
        } else {
            val user = currentUser.trim()
            val owner = taskOwner.trim()
            user.equals(owner, ignoreCase = true) ||
                (owner.contains('!') && owner.substringBefore('!').equals(user, ignoreCase = true)) ||
                (user.contains('!') && user.substringBefore('!').equals(owner, ignoreCase = true))
        }
        if (isOwner) true else (this?.allows(Permissions.SYSTEM_MODIFY, "/system/tasks") ?: true)
    }
    is TaskOrigin.Remote -> this?.allows(Permissions.RESOURCE_MANAGE, Permissions.remotePath(task.remoteId)) ?: true
}

/** PDM host package check needs System.Modify at API root /. */
fun Permissions?.canCheckHostUpdates(): Boolean =
    this?.allows(Permissions.SYSTEM_MODIFY, "/") ?: true

/** The PDM machine's journal and syslog need System.Audit on /system/log. */
fun Permissions?.canReadHostLogs(): Boolean =
    this?.allows(Permissions.SYSTEM_AUDIT, "/system/log") ?: true

/** The PDM machine's own task list needs System.Audit on /system/tasks. */
fun Permissions?.canReadHostTasks(): Boolean =
    this?.allows(Permissions.SYSTEM_AUDIT, "/system/tasks") ?: true

/** Editing shared PDM notes needs System.Modify on /system/notes. */
fun Permissions?.canEditSharedNotes(): Boolean =
    this?.allows(Permissions.SYSTEM_MODIFY, "/system/notes") ?: true

/** `termproxy` answer: a one-use terminal ticket bound to the user and the shell path. */
@Serializable
data class ShellTicketDto(
    val ticket: String,
    val port: Int = 0,
    val user: String? = null,
)

data class ServerConfig(
    val host: String,
    val port: Int = 8443,
    val authMode: AuthMode = AuthMode.PASSWORD,
    val username: String = "",
    val password: String = "",
    val apiToken: String = "",
    val realm: String = "pam",
) {
    val baseUrl: String
        get() {
            val h = host.trim().removePrefix("https://").removePrefix("http://")
                .substringBefore('/').removeSurrounding("[", "]")
            val authority = if (':' in h) "[$h]" else h
            return "https://$authority:$port/api2/json/"
        }

    val displayHost: String
        get() {
            val h = host.trim().removePrefix("https://").removePrefix("http://")
                .substringBefore('/')
            return if (h.contains(":")) h else "$h:$port"
        }

    fun withoutEphemeralSecrets(): ServerConfig = when (authMode) {
        AuthMode.PASSWORD -> copy(password = "")
        AuthMode.API_TOKEN -> this
    }
}

data class SessionState(
    val config: ServerConfig,
    val ticket: String? = null,
    val csrf: String? = null,
    val username: String? = null,
    val version: VersionInfo? = null,
    val cookieName: String = "PDMAuthCookie",
    /** Wall-clock time the password ticket was issued or last renewed. 0 for tokens and demo. */
    val ticketIssuedAtMs: Long = 0L,
    /** Offline view: no ticket, every read comes from the response cache, writes are refused. */
    val cachedOnly: Boolean = false,
)

sealed interface LoginOutcome {
    data class Success(val session: SessionState) : LoginOutcome
    data class NeedsTfa(
        val partialTicket: String,
        val config: ServerConfig,
        /** What the account offers, read from the partial ticket's challenge. */
        val methods: com.pdm.app.data.api.TfaMethods = com.pdm.app.data.api.TfaChallenge.methods(partialTicket),
    ) : LoginOutcome
    /**
     * The server presented a certificate this device cannot verify (or a CA pin's authority
     * changed). Nothing was sent: the handshake was refused before any credential. [fingerprint]
     * is the pin to save if the user accepts; [knownServer]: a saved connection already points here.
     */
    data class NeedsCertConfirmation(
        val fingerprint: String,
        val config: ServerConfig,
        val saveSecret: Boolean,
        val attemptGeneration: Long,
        val knownServer: Boolean = false,
    ) : LoginOutcome
    data class Failed(val error: Throwable) : LoginOutcome
}

data class RemoteEndpoint(
    val host: String,
    val port: Int? = null,
    val fingerprint: String? = null,
) {
    val display: String
        get() = if (port != null) "$host:$port" else host
}

data class RemoteNode(
    val id: String,
    val name: String,
    val kind: String,
    val error: String? = null,
    val endpoints: List<RemoteEndpoint> = emptyList(),
    val health: String? = null,
) {
    /**
     * The node's own web UI, where Upgrade lives (PDM cannot install packages). Prefers the endpoint
     * named after the node; any cluster member's UI reaches every node, so the first one is fine too.
     */
    fun webUiUrl(node: String): String? {
        val endpoint = endpoints.firstOrNull { it.host.substringBefore('.').equals(node, ignoreCase = true) }
            ?: endpoints.firstOrNull()
            ?: return null
        val host = if (':' in endpoint.host) "[${endpoint.host}]" else endpoint.host
        val port = endpoint.port ?: if (kind == "pbs") 8007 else 8006
        return "https://$host:$port/#v1:0:=node%2F${java.net.URLEncoder.encode(node, "UTF-8")}"
    }
}

data class Guest(
    val id: String,
    val remoteId: String,
    val name: String,
    val type: String,
    val vmid: Int,
    val status: String,
    val node: String? = null,
    val cpu: Double? = null,
    val maxcpu: Double? = null,
    val mem: Long? = null,
    val maxmem: Long? = null,
    val disk: Long? = null,
    val maxdisk: Long? = null,
    val tags: List<String> = emptyList(),
)

/** `GET pve/remotes/{r}/qemu/{vmid}/migrate?target=`: PVE's own preflight for an in-cluster move. */
@Serializable
data class MigratePreflightDto(
    val running: JsonElement? = null,
    @SerialName("local_disks") val localDisks: List<LocalDiskDto> = emptyList(),
    @SerialName("local_resources") val localResources: List<String> = emptyList(),
    @SerialName("not_allowed_nodes") val notAllowedNodes: JsonObject? = null,
)

@Serializable
data class LocalDiskDto(
    val volid: String = "",
    val cdrom: JsonElement? = null,
    val size: Long? = null,
)

@Serializable
data class NodeStorageDto(
    val storage: String = "",
    val type: String? = null,
    val content: String? = null,
    val active: JsonElement? = null,
    val enabled: JsonElement? = null,
    val shared: JsonElement? = null,
    val used: Long? = null,
    val avail: Long? = null,
    val total: Long? = null,
)

@Serializable
data class NetIfaceDto(
    val iface: String = "",
    val type: String? = null,
    val active: JsonElement? = null,
    val cidr: String? = null,
    val cidr6: String? = null,
    val gateway: String? = null,
    @SerialName("bridge_ports") val bridgePorts: String? = null,
    val slaves: String? = null,
    @SerialName("vlan-raw-device") val vlanRawDevice: String? = null,
    @SerialName("vlan-id") val vlanId: JsonElement? = null,
    @SerialName("bridge_vlan_aware") val bridgeVlanAware: JsonElement? = null,
    val comments: String? = null,
)

/** Storages and interfaces of one node, for node detail. Each list is null when its read failed. */
data class NodeInventory(
    val storages: List<NodeStorageDto>?,
    val interfaces: List<NetIfaceDto>?,
    /** Why the whole read failed. Null lists still mean that one read failed on its own. */
    val error: String? = null,
) {
    /** Bridges, bonds, VLANs and anything carrying an address: the interfaces worth a row. */
    val logical: List<NetIfaceDto>
        get() = interfaces.orEmpty()
            .filter { it.type != "eth" || it.cidr != null || it.cidr6 != null }
            .sortedWith(compareBy({ ifaceRank(it.type) }, { it.iface }))

    /** Plain physical ports, summarised as up/down instead of one row each. */
    val physical: List<NetIfaceDto>
        get() = interfaces.orEmpty().filter { it.type == "eth" && it.cidr == null && it.cidr6 == null }.sortedBy { it.iface }

    private fun ifaceRank(type: String?) = when (type) {
        "bridge", "OVSBridge" -> 0
        "bond", "OVSBond" -> 1
        "vlan", "OVSIntPort" -> 2
        else -> 3
    }
}

/** What a preflight means for the operator. `blockers` must be empty to start. */
data class MigrateCheck(
    val localDisks: List<String>,
    val blockers: List<String>,
    /** Blockers that come from the guest itself (ISO, passthrough), so they stop any destination. */
    val guestBlockers: List<String> = emptyList(),
) {
    val needsLocalDisks: Boolean get() = localDisks.isNotEmpty()
}

fun MigratePreflightDto.check(target: String): MigrateCheck {
    val cdroms = localDisks.filter { it.cdrom.asFlag() == true }.map { it.volid }
    val disks = localDisks.filter { it.cdrom.asFlag() != true }.map { it.volid }
    val nodeReason = (notAllowedNodes?.get(target) as? JsonObject)?.let { reason ->
        reason.entries.joinToString(", ") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.contentOrNull ?: v}" }
    }
    val guest = cdroms.map { "Local ISO attached ($it): eject it first" } +
        localResources.map { "Local resource $it pins the VM to this node" }
    return MigrateCheck(
        localDisks = disks,
        blockers = guest + listOfNotNull(nodeReason?.let { "$target not allowed: $it" }),
        guestBlockers = guest,
    )
}

/** The form behind the migrate dialog. In-cluster uses node; cross-remote uses remote, storage, bridge, VMID. */
data class MigrateRequest(
    val crossRemote: Boolean,
    val targetNode: String? = null,
    val targetRemote: String? = null,
    val targetStorage: String? = null,
    val targetBridge: String? = null,
    val targetVmid: Int? = null,
    /** QEMU: live migration. LXC: restart migration (stop, move, start). */
    val online: Boolean = true,
    val withLocalDisks: Boolean = false,
    val deleteSource: Boolean = true,
)

@Serializable
data class PdmNodeRef(val node: String = "")

@Serializable
data class HostMemoryDto(val total: Long? = null, val used: Long? = null, val free: Long? = null)

@Serializable
data class HostDiskDto(val total: Long? = null, val used: Long? = null, val avail: Long? = null)

@Serializable
data class HostCpuInfoDto(val cpus: Int? = null, val model: String? = null, val sockets: Int? = null)

@Serializable
data class HostKernelDto(val release: String? = null)

/** `GET nodes/{node}/status` on the PDM machine itself. */
@Serializable
data class HostStatusDto(
    val cpu: Double? = null,
    val wait: Double? = null,
    val loadavg: List<Double> = emptyList(),
    val memory: HostMemoryDto? = null,
    val root: HostDiskDto? = null,
    val swap: HostMemoryDto? = null,
    val uptime: Long? = null,
    val cpuinfo: HostCpuInfoDto? = null,
    @SerialName("current-kernel") val kernel: HostKernelDto? = null,
)

/** `GET nodes/{node}/subscription`: PDM's own licence verdict for the managed fleet. */
@Serializable
data class HostSubscriptionDto(
    val status: String? = null,
    val message: String? = null,
)

/** The PDM machine: identity, health, its own pending packages, licence verdict. */
data class PdmHost(
    val node: String,
    val version: VersionInfo?,
    val status: HostStatusDto?,
    val updates: List<AptUpdateDto>?,
    val subscription: HostSubscriptionDto?,
    /** A newer kernel is installed than the one running (e.g. "7.0.14-25"); a restart picks it up. */
    val pendingKernel: String? = null,
)

@Serializable
data class TaskCountsDto(val ok: Int = 0, val error: Int = 0, val warning: Int = 0, val unknown: Int = 0) {
    val total: Int get() = ok + error + warning + unknown
}

/** `GET remotes/tasks/statistics`: counts over PDM's whole task cache, not just the recent page. */
@Serializable
data class TaskStatisticsDto(
    @SerialName("by-remote") val byRemote: Map<String, TaskCountsDto> = emptyMap(),
    @SerialName("by-type") val byType: Map<String, TaskCountsDto> = emptyMap(),
)

/** `GET access/domains` (public): realms offered on the login form. */
@Serializable
data class RealmDto(
    val realm: String = "",
    val type: String? = null,
    val comment: String? = null,
)

enum class NodeHaState(val raw: String, val label: String) {
    ONLINE("online", "Online"),
    MAINTENANCE("maintenance", "Maintenance"),
    UNKNOWN("unknown", "Unknown"),
    FENCE("fence", "Fence"),
    GONE("gone", "Gone");

    companion object {
        fun from(raw: String?): NodeHaState? = when (raw?.lowercase()?.trim()) {
            "online" -> ONLINE
            "maintenance" -> MAINTENANCE
            "unknown" -> UNKNOWN
            "fence" -> FENCE
            "gone" -> GONE
            else -> null
        }
    }
}

data class FleetNode(
    val id: String,
    val remoteId: String,
    val name: String,
    val status: String,
    val cpu: Double? = null,
    val maxcpu: Double? = null,
    val mem: Long? = null,
    val maxmem: Long? = null,
    val uptime: Long? = null,
    val haState: String? = null,
) {
    val haStateEnum: NodeHaState? get() = NodeHaState.from(haState)

    /** True if the node is in HA maintenance mode. */
    val isHaMaintenance: Boolean
        get() = haState?.equals("maintenance", ignoreCase = true) == true

    /** True if the node is in a transitional or fault HA state (fence, gone, unknown). */
    val isHaTransitionalOrFault: Boolean
        get() = haState?.lowercase() in setOf("fence", "gone", "unknown")

    /** Whether the node is eligible for receiving migrations without warnings. */
    val isMigrationCandidate: Boolean
        get() = status.equals("online", ignoreCase = true) && !isHaMaintenance && !isHaTransitionalOrFault
}

data class FleetStorage(
    val id: String,
    val remoteId: String,
    val name: String,
    val node: String?,
    val status: String,
    val disk: Long? = null,
    val maxdisk: Long? = null,
    val shared: Boolean = false,
)

data class FleetDatastore(
    val id: String,
    val remoteId: String,
    val name: String,
    val disk: Long? = null,
    val maxdisk: Long? = null,
    val usage: Double? = null,
    val maintenance: String? = null,
)

data class FleetNetwork(
    val id: String,
    val remoteId: String,
    val name: String,
    val node: String?,
    val status: String,
    val zoneType: String? = null,
)

data class LeaderEntry(
    val remoteId: String,
    val name: String,
    val kind: String,
    val last: Double? = null,
    val series: List<Float?> = emptyList(),
    val entityId: String? = null,
    /** Mean over the selected timeframe; what the leader ranking is based on. */
    val average: Double? = null,
)

data class Leaderboards(
    val guestCpu: List<LeaderEntry> = emptyList(),
    val nodeCpu: List<LeaderEntry> = emptyList(),
    val nodeMemory: List<LeaderEntry> = emptyList(),
) {
    val isEmpty: Boolean
        get() = guestCpu.isEmpty() && nodeCpu.isEmpty() && nodeMemory.isEmpty()
}

@Serializable
data class LastLoginForm(
    val host: String = "",
    val port: Int = 8443,
    val username: String = "root@pam",
    val authMode: AuthMode = AuthMode.PASSWORD,
    val savedSecret: String = "",
    val autoConnect: Boolean = false,
)

/** One row of `pve/remotes/{remote}/cluster-status`: a `cluster` header (clustered remotes only) plus `node` rows. */
@Serializable
data class ClusterStatusDto(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val nodes: Int? = null,
    val quorate: JsonElement? = null,
    val version: Int? = null,
    val ip: String? = null,
    val online: JsonElement? = null,
    val local: JsonElement? = null,
)

/** PVE sends some flags as 0/1, PDM 1.1 as booleans; accept both. */
fun JsonElement?.asFlag(): Boolean? = (this as? JsonPrimitive)?.let { p ->
    p.booleanOrNull ?: p.intOrNull?.let { it != 0 }
}

/** A number or string field (PVE sends ids either way) as text; null when absent or not a primitive. */
fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.contentOrNull

@Serializable
data class FirewallStateDto(
    val enabled: Boolean = false,
    val active: Int = 0,
    val all: Int = 0,
)

@Serializable
data class FirewallGuestDto(
    val kind: String = "",
    val name: String = "",
    val vmid: Int = 0,
    val status: FirewallStateDto? = null,
)

@Serializable
data class FirewallNodeDto(
    val node: String = "",
    val status: FirewallStateDto? = null,
    val guests: List<FirewallGuestDto> = emptyList(),
)

/** One remote from `pve/firewall/status`. */
@Serializable
data class FirewallRemoteDto(
    val remote: String = "",
    val status: FirewallStateDto? = null,
    val nodes: List<FirewallNodeDto> = emptyList(),
)

@Serializable
data class MetricCollectionDto(
    val remote: String = "",
    @SerialName("last-collection") val lastCollection: Long? = null,
)

data class ClusterNodeInfo(val name: String, val ip: String?, val online: Boolean, val local: Boolean)

/** What the remote detail page shows; each part is null when its read failed. */
data class RemoteDetail(
    val clusterName: String?,
    val quorate: Boolean?,
    val nodes: List<ClusterNodeInfo>?,
    val version: VersionInfo?,
    val firewall: FirewallRemoteDto?,
    val lastMetricCollection: Long?,
)

/** Firewall picture for one remote, in counts the UI can print. */
data class FirewallSummary(
    val clusterEnabled: Boolean,
    val nodesEnabled: Int,
    val nodes: Int,
    val guestsEnabled: Int,
    val guests: Int,
    val activeRules: Int,
)

fun FirewallRemoteDto.summary(): FirewallSummary {
    val guests = nodes.flatMap { it.guests }
    return FirewallSummary(
        clusterEnabled = status?.enabled == true,
        nodesEnabled = nodes.count { it.status?.enabled == true },
        nodes = nodes.size,
        guestsEnabled = guests.count { it.status?.enabled == true },
        guests = guests.size,
        activeRules = (status?.active ?: 0) + nodes.sumOf { it.status?.active ?: 0 } +
            guests.sumOf { it.status?.active ?: 0 },
    )
}

/** `GET remotes/updates/summary`: PDM's cached view of pending packages on every node. */
@Serializable
data class UpdateSummaryDto(
    val remotes: Map<String, RemoteUpdatesDto> = emptyMap(),
)

@Serializable
data class RemoteUpdatesDto(
    val status: String? = null,
    @SerialName("remote-type") val remoteType: String? = null,
    @SerialName("status-message") val statusMessage: String? = null,
    val nodes: Map<String, NodeUpdatesDto> = emptyMap(),
)

@Serializable
data class NodeUpdatesDto(
    val status: String? = null,
    @SerialName("status-message") val statusMessage: String? = null,
    @SerialName("number-of-updates") val numberOfUpdates: Int = 0,
    @SerialName("repository-status") val repositoryStatus: String? = null,
    @SerialName("last-refresh") val lastRefresh: Long? = null,
    val versions: List<PackageVersionDto> = emptyList(),
)

@Serializable
data class PackageVersionDto(
    val `package`: String = "",
    val version: String = "",
)

/** One pending package from `.../nodes/{node}/apt/update` (Debian field names). */
@Serializable
data class AptUpdateDto(
    @SerialName("Package") val name: String = "",
    @SerialName("OldVersion") val oldVersion: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Origin") val origin: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Section") val section: String? = null,
)

data class NodeUpdates(
    val remoteId: String,
    val node: String,
    val pending: Int,
    /** `ok`, `non-production-ready`, `no-products`, … as PDM reports it. */
    val repositoryStatus: String?,
    val lastRefresh: Long?,
    /** pve-manager / proxmox-backup-server version, when reported. */
    val version: String?,
    val error: String?,
) {
    val id: String get() = "$remoteId/$node"
    val repoOk: Boolean get() = repositoryStatus.isNullOrBlank() || repositoryStatus == "ok"
}

data class UpdatesRollup(val pending: Int, val nodesWithUpdates: Int, val repoWarnings: Int, val errors: Int)

fun List<NodeUpdates>.rollup() = UpdatesRollup(
    pending = sumOf { it.pending },
    nodesWithUpdates = count { it.pending > 0 },
    repoWarnings = count { !it.repoOk },
    errors = count { it.error != null },
)

data class DashboardMetrics(
    val pveRemotes: Int = 0,
    val pbsRemotes: Int = 0,
    val remoteErrors: Int = 0,
    val guestsRunning: Int = 0,
    val guestsStopped: Int = 0,
    val qemu: Int = 0,
    val lxc: Int = 0,
    val nodes: Int = 0,
    val nodesOnline: Int = 0,
    val nodesOffline: Int = 0,
    val pveCpuUsed: Double? = null,
    val pveCpuMax: Double? = null,
    val pveCpuAllocated: Double? = null,
    val pveMemUsed: Long? = null,
    val pveMemTotal: Long? = null,
    val pveStorageUsed: Long? = null,
    val pveStorageTotal: Long? = null,
    val storages: Int = 0,
    val sdnAvailable: Int = 0,
    val sdnError: Int = 0,
    val datastores: Int = 0,
)

@Serializable
data class CountTriple(
    val running: Long = 0,
    val stopped: Long = 0,
    val template: Long = 0,
    val unknown: Long = 0,
    val online: Long = 0,
    val offline: Long = 0,
    val available: Long = 0,
    val pending: Long = 0,
    val error: Long = 0,
)

@Serializable
data class CpuStatisticsDto(
    val used: Double = 0.0,
    val max: Double = 0.0,
    val allocated: Double? = null,
)

@Serializable
data class ByteStatisticsDto(
    val total: Long = 0,
    val used: Long = 0,
    val avail: Long = 0,
)

@Serializable
data class RemoteInfoStatus(
    val name: String = "",
    val ty: String? = null,
    val status: String? = null,
    val messages: List<String> = emptyList(),
)

@Serializable
data class FailedRemoteInfo(
    val name: String = "",
    val error: String? = null,
    @SerialName("remote_type") val remoteType: String? = null,
)

@Serializable
data class ResourcesStatus(
    val remotes: Long = 0,
    @SerialName("failed_remotes") val failedRemotes: Long = 0,
    @SerialName("pve_nodes") val pveNodes: CountTriple = CountTriple(),
    val qemu: CountTriple = CountTriple(),
    val lxc: CountTriple = CountTriple(),
    val storages: CountTriple = CountTriple(),
    @SerialName("sdn_zones") val sdnZones: CountTriple = CountTriple(),
    @SerialName("pbs_nodes") val pbsNodes: CountTriple = CountTriple(),
    @SerialName("pbs_datastores") val pbsDatastores: PbsDatastoreCounts = PbsDatastoreCounts(),
    @SerialName("pve_cpu_stats") val pveCpuStats: CpuStatisticsDto = CpuStatisticsDto(),
    @SerialName("pbs_cpu_stats") val pbsCpuStats: CpuStatisticsDto = CpuStatisticsDto(),
    @SerialName("pve_memory_stats") val pveMemoryStats: ByteStatisticsDto = ByteStatisticsDto(),
    @SerialName("pbs_memory_stats") val pbsMemoryStats: ByteStatisticsDto = ByteStatisticsDto(),
    @SerialName("pve_storage_stats") val pveStorageStats: ByteStatisticsDto = ByteStatisticsDto(),
    @SerialName("pbs_storage_stats") val pbsStorageStats: ByteStatisticsDto = ByteStatisticsDto(),
    @SerialName("failed_remotes_list") val failedRemotesList: List<FailedRemoteInfo> = emptyList(),
    @SerialName("remote-list") val remoteList: List<RemoteInfoStatus> = emptyList(),
)

@Serializable
data class PbsDatastoreCounts(
    val online: Long = 0,
    @SerialName("under_maintenance") val underMaintenance: Long? = null,
    @SerialName("high_usage") val highUsage: Long? = null,
)

@Serializable
data class ResourceRrdData(
    val start: Long = 0,
    val resolution: Long = 0,
    val data: List<Double?> = emptyList(),
)

@Serializable
data class TopEntityDto(
    val remote: String = "",
    val resource: PdmResource = PdmResource(),
    @SerialName("rrd-data") val rrdData: ResourceRrdData = ResourceRrdData(),
)

@Serializable
data class TopEntities(
    @SerialName("guest-cpu") val guestCpu: List<TopEntityDto> = emptyList(),
    @SerialName("node-cpu") val nodeCpu: List<TopEntityDto> = emptyList(),
    @SerialName("node-memory") val nodeMemory: List<TopEntityDto> = emptyList(),
)

@Serializable
data class RemoteSubscription(
    val remote: String = "",
    val state: String? = null,
) {
    fun isNotable(): Boolean {
        val s = state?.trim().orEmpty()
        return s.isNotEmpty() && !s.equals("none", ignoreCase = true)
    }
}

/** One row from `GET remotes/tasks/list`. Field names follow PDM 1.1 `TaskListItem`. */
@Serializable
data class RemoteTaskDto(
    val upid: String = "",
    val node: String = "",
    val pid: Long = 0,
    val pstart: Long = 0,
    @SerialName("starttime")
    @JsonNames("start-time", "startTime")
    val startTime: Long = 0,
    @SerialName("worker_type")
    @JsonNames("worker-type")
    val workerType: String = "",
    @SerialName("worker_id")
    @JsonNames("worker-id")
    val workerId: String? = null,
    val user: String = "",
    @SerialName("endtime")
    @JsonNames("end-time")
    val endTime: Long? = null,
    val status: String? = null,
)

enum class TaskPhase {
    Running,
    Ok,
    Warning,
    Error,
    Unknown,
}

/** Origin of a task (R3): either running directly on the PDM host or on a managed remote. */
@Serializable
sealed class TaskOrigin {
    @Serializable
    @SerialName("host")
    data class Host(val node: String) : TaskOrigin()

    @Serializable
    @SerialName("remote")
    data class Remote(val remoteKind: String, val remoteId: String) : TaskOrigin()
}

data class RemoteTask(
    val upid: String,
    val remoteId: String = "",
    val node: String = "",
    val workerType: String = "",
    val workerId: String? = null,
    val user: String = "",
    val startTime: Long = 0L,
    val endTime: Long? = null,
    val status: String? = null,
    val origin: TaskOrigin = if (remoteId.isNotBlank()) {
        TaskOrigin.Remote(if (upid.startsWith("pbs:")) "pbs" else "pve", remoteId)
    } else if (upid.contains("!")) {
        val head = upid.substringBefore("!")
        val k = if (head.startsWith("pbs:")) "pbs" else "pve"
        val r = if (head.contains(":")) head.substringAfter(":") else head
        TaskOrigin.Remote(k, r)
    } else {
        TaskOrigin.Host(node)
    },
) {
    val phase: TaskPhase
        get() = when {
            status.isNullOrBlank() && (endTime == null || endTime == 0L) -> TaskPhase.Running
            // PDM's task cache reports live workers with the literal status "RUNNING".
            status.equals("running", ignoreCase = true) -> TaskPhase.Running
            status.equals("OK", ignoreCase = true) -> TaskPhase.Ok
            status.orEmpty().startsWith("WARNINGS:", ignoreCase = true) -> TaskPhase.Warning
            status.equals("unknown", ignoreCase = true) -> TaskPhase.Unknown
            else -> TaskPhase.Error
        }

    val isHostTask: Boolean
        get() = origin is TaskOrigin.Host

    val kind: String
        get() = when (val o = origin) {
            is TaskOrigin.Host -> "host"
            is TaskOrigin.Remote -> o.remoteKind
        }

    val headline: String
        get() = buildString {
            append(workerType.ifBlank { "task" })
            workerId?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
        }
}

data class TaskRollup(
    val total: Int = 0,
    val running: Int = 0,
    val failed: Int = 0,
    val warnings: Int = 0,
)

fun List<RemoteTask>.taskRollup(): TaskRollup = TaskRollup(
    total = size,
    running = count { it.phase == TaskPhase.Running },
    failed = count { it.phase == TaskPhase.Error },
    warnings = count { it.phase == TaskPhase.Warning },
)

fun taskPlateValue(tasks: List<RemoteTask>?): String = tasks?.size?.toString() ?: "—"

fun taskPlateSubtitle(tasks: List<RemoteTask>?): String {
    if (tasks == null) return "unavailable"
    if (tasks.isEmpty()) return "no recent tasks"
    val roll = tasks.taskRollup()
    val parts = buildList {
        if (roll.running > 0) add("${roll.running} running")
        if (roll.failed > 0) add("${roll.failed} failed")
        if (roll.warnings > 0) add("${roll.warnings} warn")
        if (isEmpty()) add("finished")
    }
    return parts.joinToString(" · ")
}

/**
 * Typed availability wrapper for section reads to replace indiscriminate null collapses (Q4).
 */
sealed interface ReadAvailability<out T> {
    data object NotRequested : ReadAvailability<Nothing>
    data class Loading<out T>(val previous: T? = null) : ReadAvailability<T>
    data class Fresh<out T>(val value: T) : ReadAvailability<T>
    data class Stale<out T>(val value: T, val error: String? = null, val cachedAt: Long? = null) : ReadAvailability<T>
    data class Forbidden(val message: String = "Access forbidden (needs privilege)") : ReadAvailability<Nothing>
    data class NotFound(val message: String = "Resource not found on target") : ReadAvailability<Nothing>
    data class Unsupported(val message: String = "Endpoint not supported by server version") : ReadAvailability<Nothing>
    data class TransportError(val message: String, val rootCause: Throwable? = null) : ReadAvailability<Nothing>
    data class Failed(val message: String, val rootCause: Throwable? = null) : ReadAvailability<Nothing>

    val valueOrNull: T?
        get() = when (this) {
            is Fresh -> value
            is Stale -> value
            is Loading -> previous
            else -> null
        }

    val isAvailable: Boolean get() = valueOrNull != null
    val isFresh: Boolean get() = this is Fresh
    val isStale: Boolean get() = this is Stale
    val isLoading: Boolean get() = this is Loading
    val isForbidden: Boolean get() = this is Forbidden
    val isUnsupported: Boolean get() = this is Unsupported
    val isFailed: Boolean get() = this is TransportError || this is Failed
}

/** Flattened query filters for `remotes/tasks/list` (F1). */
@Serializable
data class TaskFilters(
    val start: Long? = null,
    val limit: Int? = null,
    val errors: Boolean? = null,
    val running: Boolean? = null,
    val userfilter: String? = null,
    val since: Long? = null,
    val until: Long? = null,
    val typefilter: String? = null,
    val statusfilter: String? = null,
    val remote: String? = null,
    val view: String? = null,
)

/** Host Syslog line (F2). */
@Serializable
data class SyslogLineDto(
    val n: Long = 0,
    val t: String = "",
)

/** Host Syslog filter parameters (F2). */
@Serializable
data class SyslogFilter(
    val start: Int? = null,
    val limit: Int? = null,
    val service: String? = null,
    val since: String? = null,
    val until: String? = null,
)

/** Host Journal filter parameters (F2). */
@Serializable
data class JournalFilter(
    val lastentries: Int? = null,
    val since: Long? = null,
    val until: Long? = null,
    val service: String? = null,
    val priority: String? = null,
)

/** Shared PDM Markdown notes state and conflict tracking (F3). */
@Serializable
data class SharedNotesState(
    val content: String = "",
    val digest: String? = null,
    val localDraft: String? = null,
    val isConflict: Boolean = false,
    val isEditing: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * Where an operation the app sent stands (Q5). Submission and observation are separate: a
 * request can be accepted and still not observed, and losing sight of a task is never a failure.
 */
@Serializable
enum class OperationSubmissionState {
    /** Written just before the request goes out. Found on restore: the outcome is unknown. */
    SUBMITTING,

    /** The server accepted it (with a task id when it gave one). */
    ACCEPTED,

    /** The answer was lost (connection dropped): the server may or may not have started it. */
    UNKNOWN,

    /** The server refused the request. Nothing ran. */
    REJECTED,

    /** Its task was seen running. */
    RUNNING,

    /** The app stopped watching (too long, or the server stopped answering). The task may still run. */
    OBSERVATION_PAUSED,

    /** Its task finished OK (or with warnings), or the planned outcome was confirmed live. */
    SUCCEEDED,

    /** Its task finished with an error. */
    FAILED,
}

/** What an operation was, so its task can be recognised in the server's task list. */
object OperationKind {
    const val REBOOT = "reboot"
    const val HOST_APT_REFRESH = "host_apt_refresh"
    const val MIGRATE = "migrate"
    const val SNAPSHOT_CREATE = "snapshot_create"
    const val SNAPSHOT_ROLLBACK = "snapshot_rollback"
    const val SNAPSHOT_DELETE = "snapshot_delete"
    fun power(action: GuestAction) = "power_${action.name.lowercase()}"
}

/**
 * Persistent receipt for an operation the app sent (Q5), in the encrypted session store.
 * Scoped to endpoint, port, sign-in kind and account; holds no secret of any kind. [baselineUptime]
 * and [sawDown] are a planned reboot's evidence (Q1), so a restored watch still knows the machine
 * restarted after the request.
 */
@Serializable
data class OperationReceipt(
    val id: String,
    val kind: String,
    val targetEndpoint: String,
    val targetPort: Int = 8443,
    val authMode: String = "ticket",
    val account: String,
    val origin: TaskOrigin? = null,
    val remoteId: String? = null,
    val node: String? = null,
    val vmid: Long? = null,
    /** Guest type ("qemu"/"lxc") for guest operations; recognises its worker in the task list. */
    val guestType: String? = null,
    val upid: String? = null,
    val state: OperationSubmissionState = OperationSubmissionState.SUBMITTING,
    val submittedAt: Long = System.currentTimeMillis(),
    val description: String = "",
    val error: String? = null,
    val baselineUptime: Long? = null,
    val sawDown: Boolean = false,
    /** Wall-clock time of the last live answer about it. */
    val lastObservedAt: Long? = null,
) {
    val isTerminal: Boolean
        get() = state == OperationSubmissionState.SUCCEEDED ||
            state == OperationSubmissionState.FAILED ||
            state == OperationSubmissionState.REJECTED

    /** Work the server may be doing right now because of it. */
    val mayBeActive: Boolean
        get() = state == OperationSubmissionState.SUBMITTING ||
            state == OperationSubmissionState.ACCEPTED ||
            state == OperationSubmissionState.RUNNING ||
            state == OperationSubmissionState.UNKNOWN
}
