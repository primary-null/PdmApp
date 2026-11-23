package com.pdm.app.ui.home

import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.FleetStorage
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.RemoteNode

/** One typed filter across remotes, nodes, storage, and guests. Blank keeps every row in scope. */
internal data class FleetSearchHits(
    val remotes: List<RemoteNode>,
    val nodes: List<FleetNode>,
    val storages: List<FleetStorage>,
    val guests: List<Guest>,
) {
    val total: Int get() = remotes.size + nodes.size + storages.size + guests.size
    val isEmpty: Boolean get() = total == 0
}

internal fun fleetSearch(
    query: String,
    remotes: List<RemoteNode>,
    nodes: List<FleetNode>,
    storages: List<FleetStorage>,
    guests: List<Guest>,
    remoteId: String? = null,
): FleetSearchHits {
    val q = query.trim().lowercase()
    fun inScope(remote: String) = remoteId == null || remote == remoteId
    return FleetSearchHits(
        remotes = remotes.filter { inScope(it.id) && it.matchesQuery(q) },
        nodes = nodes.filter { inScope(it.remoteId) && it.matchesQuery(q) },
        storages = storages.filter { inScope(it.remoteId) && it.matchesQuery(q) },
        guests = guests.filter { inScope(it.remoteId) && it.matchesQuery(q) },
    )
}

internal fun Guest.matchesQuery(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isBlank()) return true
    return name.hit(q) ||
        type.hit(q) ||
        vmid.toString().contains(q) ||
        remoteId.hit(q) ||
        node.hit(q) ||
        tags.any { it.hit(q) }
}

private fun RemoteNode.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return id.hit(query) ||
        name.hit(query) ||
        kind.hit(query) ||
        health.hit(query) ||
        endpoints.any { it.host.hit(query) || it.display.hit(query) }
}

private fun FleetNode.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return name.hit(query) || id.hit(query) || remoteId.hit(query) || status.hit(query)
}

private fun FleetStorage.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return name.hit(query) || id.hit(query) || remoteId.hit(query) || node.hit(query) || status.hit(query)
}

private fun String?.hit(query: String): Boolean = this?.lowercase()?.contains(query) == true
