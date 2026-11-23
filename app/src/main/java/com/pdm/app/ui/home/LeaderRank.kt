package com.pdm.app.ui.home

import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.LeaderEntry
import com.pdm.app.ui.util.formatCores
import com.pdm.app.ui.util.formatPercent
import com.pdm.app.ui.util.formatRatio
import com.pdm.app.ui.util.fractionOf

/** Maximum number of leader rows shown in a sparkline plate. */
internal const val LEADER_SPARK_LIMIT = 5

internal fun sparkWindow(
    entries: List<LeaderEntry>,
    selectedRemoteId: String?,
    limit: Int = LEADER_SPARK_LIMIT,
): List<LeaderEntry> = entries
    .filter { selectedRemoteId == null || it.remoteId == selectedRemoteId }
    .take(limit)

enum class LeaderMetric {
    GuestCpu,
    NodeCpu,
    NodeMemory,
}

data class RankedLeader(
    val rank: Int,
    val entry: LeaderEntry,
    val fraction: Float?,
    val caption: String,
    val live: Boolean,
)

fun rankLeaders(
    entries: List<LeaderEntry>,
    guests: List<Guest>,
    nodes: List<FleetNode>,
    selectedRemoteId: String?,
    metric: LeaderMetric,
): List<RankedLeader> {
    val visible = entries.filter { selectedRemoteId == null || it.remoteId == selectedRemoteId }
    return visible.mapIndexed { index, entry ->
        when (metric) {
            LeaderMetric.GuestCpu -> rankGuestCpu(index + 1, entry, guests)
            LeaderMetric.NodeCpu -> rankNodeCpu(index + 1, entry, nodes)
            LeaderMetric.NodeMemory -> rankNodeMemory(index + 1, entry, nodes)
        }
    }
}

private fun rankGuestCpu(rank: Int, entry: LeaderEntry, guests: List<Guest>): RankedLeader {
    val guest = entry.matchedGuest(guests)
    if (guest == null) return rrdRank(rank, entry)
    val usedCores = if (guest.cpu != null && guest.maxcpu != null) guest.cpu * guest.maxcpu else null
    val caption = listOfNotNull(
        "${formatPercent(guest.cpu)} now",
        guest.maxcpu?.let { formatCores(usedCores, it) },
        guest.status.takeIf { !it.equals("running", ignoreCase = true) },
    ).joinToString(" · ")
    return RankedLeader(
        rank = rank,
        entry = entry,
        fraction = guest.cpu?.toFloat()?.coerceIn(0f, 1f),
        caption = caption,
        live = true,
    )
}

private fun rankNodeCpu(rank: Int, entry: LeaderEntry, nodes: List<FleetNode>): RankedLeader {
    val node = entry.matchedNode(nodes)
    if (node == null) return rrdRank(rank, entry)
    val usedCores = if (node.cpu != null && node.maxcpu != null) node.cpu * node.maxcpu else null
    val caption = listOfNotNull(
        "${formatPercent(node.cpu)} now",
        node.maxcpu?.let { formatCores(usedCores, it) },
    ).joinToString(" · ")
    return RankedLeader(
        rank = rank,
        entry = entry,
        fraction = node.cpu?.toFloat()?.coerceIn(0f, 1f),
        caption = caption,
        live = true,
    )
}

private fun rankNodeMemory(rank: Int, entry: LeaderEntry, nodes: List<FleetNode>): RankedLeader {
    val node = entry.matchedNode(nodes)
    if (node == null) return rrdRank(rank, entry)
    return RankedLeader(
        rank = rank,
        entry = entry,
        fraction = fractionOf(node.mem, node.maxmem),
        caption = "${formatRatio(node.mem, node.maxmem)} now",
        live = true,
    )
}

private fun rrdRank(rank: Int, entry: LeaderEntry): RankedLeader = RankedLeader(
    rank = rank,
    entry = entry,
    fraction = entry.last?.toFloat()?.coerceIn(0f, 1f),
    caption = "${formatPercent(entry.last)} rrd",
    live = false,
)
