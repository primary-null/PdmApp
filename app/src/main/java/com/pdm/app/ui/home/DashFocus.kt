package com.pdm.app.ui.home

import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.LeaderEntry

/** What the detail pane is showing. Left dashboard stays the map. */
sealed class DashFocus {
    data object Guests : DashFocus()
    data object Nodes : DashFocus()
    data object Storage : DashFocus()
    data object Datastores : DashFocus()
    data object Sdn : DashFocus()
    data object Cpu : DashFocus()
    data object Memory : DashFocus()
    data object Faults : DashFocus()
    data object Tasks : DashFocus()
    data class GuestDetail(val id: String) : DashFocus()
    data class NodeDetail(val id: String) : DashFocus()
    data class StorageDetail(val id: String) : DashFocus()
    data class DatastoreDetail(val id: String) : DashFocus()
    data class NetworkDetail(val id: String) : DashFocus()
    data class TaskDetail(val upid: String) : DashFocus()
    data object Updates : DashFocus()
    data object Host : DashFocus()
    data class RemoteDetail(val id: String) : DashFocus()
    data class NodeUpdates(val remoteId: String, val node: String) : DashFocus()
    data object Operations : DashFocus()

    val isRoot: Boolean get() = this is Guests

    /** A single thing's spec rather than a list; the desk layout gives these their own column. */
    val isDetail: Boolean
        get() = when (this) {
            is GuestDetail, is NodeDetail, is StorageDetail, is DatastoreDetail, is NetworkDetail,
            is TaskDetail, is RemoteDetail, is NodeUpdates, Host -> true
            else -> false
        }

    val title: String
        get() = when (this) {
            Guests -> "Guests"
            Nodes -> "Nodes"
            Storage -> "Storage"
            Datastores -> "Datastores"
            Sdn -> "SDN"
            Cpu -> "CPU"
            Memory -> "Memory"
            Faults -> "Faults"
            Tasks -> "Tasks"
            is GuestDetail -> "Guest"
            is NodeDetail -> "Node"
            is StorageDetail -> "Storage"
            is DatastoreDetail -> "Datastore"
            is NetworkDetail -> "SDN"
            is TaskDetail -> "Task"
            Updates -> "Updates"
            Host -> "PDM host"
            is RemoteDetail -> "Remote"
            is NodeUpdates -> "Updates"
            Operations -> "Operations"
        }
}

data class DashNav(
    val focus: DashFocus = DashFocus.Guests,
    val stack: List<DashFocus> = emptyList(),
) {
    val canPop: Boolean get() = !focus.isRoot

    /** The list the desk layout keeps in the middle column while a detail is open on the right. */
    val listFocus: DashFocus
        get() = if (!focus.isDetail) focus else stack.lastOrNull { !it.isDetail } ?: DashFocus.Guests

    fun open(next: DashFocus): DashNav {
        if (next == focus) return this
        if (next is DashFocus.Guests) return DashNav()
        val newStack = if (focus.isRoot) stack else (stack + focus).takeLast(8)
        return DashNav(next, newStack)
    }

    fun pop(): DashNav {
        if (focus.isRoot) return this
        if (stack.isEmpty()) return DashNav()
        return DashNav(stack.last(), stack.dropLast(1))
    }
}

fun LeaderEntry.toFocus(guests: List<Guest>, nodes: List<FleetNode>): DashFocus? {
    matchedGuest(guests)?.let { return DashFocus.GuestDetail(it.id) }
    matchedNode(nodes)?.let { return DashFocus.NodeDetail(it.id) }
    return null
}

fun LeaderEntry.matchedGuest(guests: List<Guest>): Guest? {
    entityId?.let { id -> guests.find { it.id == id } }?.let { return it }
    val kindNorm = kind.lowercase()
    if (kindNorm != "qemu" && kindNorm != "lxc") return null
    return guests.filter { it.remoteId == remoteId && it.name.equals(name, ignoreCase = true) }
        .singleOrNull()
        ?: guests.filter { it.remoteId == remoteId && it.type == kindNorm && it.vmid.toString() == name }
            .singleOrNull()
}

fun LeaderEntry.matchedNode(nodes: List<FleetNode>): FleetNode? {
    entityId?.let { id -> nodes.find { it.id == id } }?.let { return it }
    if (kind.lowercase() != "node") return null
    return nodes.filter { it.remoteId == remoteId && it.name.equals(name, ignoreCase = true) }
        .singleOrNull()
}

internal fun mapItemKey(id: String) = "map/$id"

internal fun detailItemKey(id: String) = "detail/$id"

/** Item count of `dashboardItems` + `inventoryItems` so compact can scroll to the detail pane. */
internal fun mapPaneItemCount(
    notableSubscriptionCount: Int,
    hasLeaders: Boolean,
    cpuGraphsExpanded: Boolean,
    memoryGraphsExpanded: Boolean,
    nodeCount: Int,
    nodesExpanded: Boolean,
    storageCount: Int,
    storageExpanded: Boolean,
    datastoreCount: Int,
    datastoresExpanded: Boolean,
    networkCount: Int,
    sdnExpanded: Boolean,
    hasHost: Boolean = false,
): Int {
    var n = 3
    if (hasLeaders) {
        n += 1
        n += 1
        if (cpuGraphsExpanded) n += 2
        n += 1
        if (memoryGraphsExpanded) n += 1
    }
    n += 2
    if (hasHost) n += 1
    if (notableSubscriptionCount > 0) n += 2
    if (nodeCount > 0) {
        n += 1
        if (nodesExpanded) n += nodeCount
    }
    if (storageCount > 0) {
        n += 1
        if (storageExpanded) n += storageCount
    }
    if (datastoreCount > 0) {
        n += 1
        if (datastoresExpanded) n += datastoreCount
    }
    if (networkCount > 0) {
        n += 1
        if (sdnExpanded) n += networkCount
    }
    return n
}
