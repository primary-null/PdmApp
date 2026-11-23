package com.pdm.app.data.ops

import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.NodeHaState

/**
 * Q3: which nodes a guest may be moved to, and what the person must be told first. Known HA
 * maintenance and fault states are never picked by default; a state this app doesn't know is shown
 * as is but not treated as a fault, and a node that reports no HA state is simply "not reported".
 */
object MigrationSafety {
    data class Choice(val nodes: List<String>, val preselected: String?, val note: String?)

    /** Destinations inside the guest's own cluster: online nodes other than its current one. */
    fun inCluster(nodes: List<FleetNode>, remoteId: String, currentNode: String?): Choice {
        val online = nodes.filter { it.remoteId == remoteId && it.name != currentNode && it.status.equals("online", ignoreCase = true) }
        val safe = online.filter { concern(it) == null }
        return Choice(
            // Safe nodes first, so the obvious choice is the first one shown.
            nodes = (safe + (online - safe.toSet())).map { it.name },
            preselected = safe.firstOrNull()?.name,
            note = if (online.isNotEmpty() && safe.isEmpty()) {
                "Every other node is in HA maintenance or an HA fault state. Pick one only if you mean to."
            } else null,
        )
    }

    /** The node to read a remote's storages and bridges from: a sound node when there is one. */
    fun queryNode(nodes: List<FleetNode>, remoteId: String): FleetNode? {
        val online = nodes.filter { it.remoteId == remoteId && it.status.equals("online", ignoreCase = true) }
        return online.firstOrNull { concern(it) == null } ?: online.firstOrNull()
    }

    /** Why moving a guest onto [node] needs a second thought, or null when nothing is known against it. */
    fun concern(node: FleetNode?): String? {
        node ?: return null
        return when (node.haStateEnum) {
            NodeHaState.MAINTENANCE -> "${node.name} is in HA maintenance. HA moves guests off it, not onto it."
            NodeHaState.FENCE -> "${node.name} is being fenced by HA."
            NodeHaState.GONE -> "HA reports ${node.name} as gone from the cluster."
            NodeHaState.UNKNOWN -> "HA can't tell the state of ${node.name}."
            NodeHaState.ONLINE, null -> null
        }
    }
}
