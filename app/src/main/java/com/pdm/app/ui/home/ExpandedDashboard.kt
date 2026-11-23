package com.pdm.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.FleetHistory
import com.pdm.app.data.FleetSample
import com.pdm.app.data.model.rollup
import com.pdm.app.data.model.taskPlateSubtitle
import com.pdm.app.data.model.taskPlateValue
import com.pdm.app.data.model.taskRollup
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.TrendView
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.components.TechSparkline
import com.pdm.app.ui.util.formatCores
import com.pdm.app.ui.util.formatPercent
import com.pdm.app.ui.util.formatRatio

/** DASHBOARD label with the EXPAND / COMPACT toggle; expanded, the graphs' 1h / 1d / 1w sits here too. */
@Composable
internal fun DashboardHeader(
    expanded: Boolean,
    timeframe: String,
    onTimeframe: (String) -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(12.dp).height(3.dp).background(TechColors.Amber))
        Spacer(Modifier.width(8.dp))
        Text(
            "DASHBOARD",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = TechColors.Amber,
            style = MaterialTheme.typography.labelMedium,
        )
        if (expanded) {
            TimeframeRow(timeframe, onTimeframe)
            Spacer(Modifier.width(8.dp))
        }
        TechActionPlate(label = if (expanded) "Compact" else "Expand", onClick = onToggle, emphasized = expanded)
    }
}

/** Remotes, guests, nodes, faults, tasks, updates: number, recorded history, breakdown. */
@Composable
internal fun ExpandedCounts(
    state: FleetUiState,
    onRemotes: () -> Unit,
    onGuests: () -> Unit,
    onNodes: () -> Unit,
    onFaults: () -> Unit,
    onTasks: () -> Unit,
    onUpdates: () -> Unit,
) {
    val m = state.metrics
    val recorded = recordedSeries(state)
    val roll = state.tasks?.taskRollup()
    val upd = state.updates?.rollup()
    val hostPending = state.host?.updates?.size ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TrendPlate(
            title = "Remotes",
            value = (m.pveRemotes + m.pbsRemotes).toString(),
            subtitle = "${m.pveRemotes} PVE · ${m.pbsRemotes} PBS",
            series = recorded { it.remotesOk.toFloat() },
            note = RECORDING,
            breakdown = state.remotes.joinToString(" · ") { "${it.name} ${if (it.error.isNullOrBlank()) "ok" else "ERROR"}" },
            rail = TechColors.Amber,
            onClick = onRemotes,
        )
        TrendPlate(
            title = "Guests",
            value = (m.guestsRunning + m.guestsStopped).toString(),
            subtitle = "${m.guestsRunning} up · ${m.guestsStopped} down",
            series = recorded { it.guestsUp.toFloat() },
            note = RECORDING,
            breakdown = "${m.qemu} qemu · ${m.lxc} lxc · graph: guests up",
            rail = TechColors.LinkGreen,
            onClick = onGuests,
        )
        TrendPlate(
            title = "Nodes",
            value = m.nodes.toString(),
            subtitle = "${m.nodesOnline} up" + if (m.nodesOffline > 0) " · ${m.nodesOffline} down" else "",
            series = recorded { it.nodesUp.toFloat() },
            note = RECORDING,
            breakdown = state.nodes.joinToString(" · ") { if (it.status == "online") it.name else "${it.name} DOWN" },
            rail = if (m.nodesOffline > 0) TechColors.Amber else TechColors.LinkGreen,
            onClick = onNodes,
        )
        TrendPlate(
            title = "Faults",
            value = m.remoteErrors.toString(),
            subtitle = if (m.remoteErrors == 0) "remotes clean" else "remote errors",
            series = recorded { it.faults.toFloat() },
            note = RECORDING,
            breakdown = state.remotes.filter { !it.error.isNullOrBlank() }
                .joinToString(" · ") { "${it.name}: ${it.error}" }.ifBlank { "no remote reports an error" },
            rail = if (m.remoteErrors == 0) TechColors.LinkGreen else MaterialTheme.colorScheme.error,
            onClick = onFaults,
        )
        TrendPlate(
            title = "Tasks",
            value = taskPlateValue(state.tasks),
            subtitle = taskPlateSubtitle(state.tasks),
            series = recorded { it.tasksRunning?.toFloat() },
            note = RECORDING,
            breakdown = state.tasks?.let { tasks ->
                val failedBy = tasks.filter { it.phase == com.pdm.app.data.model.TaskPhase.Error }
                    .groupingBy { it.remoteId }.eachCount()
                val parts = failedBy.entries.sortedByDescending { it.value }.map { "${it.key} ${it.value} failed" }
                (parts.ifEmpty { listOf("no failed tasks") } + "graph: running").joinToString(" · ")
            },
            rail = when {
                roll == null -> TechColors.Mute
                roll.failed > 0 -> MaterialTheme.colorScheme.error
                roll.running > 0 || roll.warnings > 0 -> TechColors.Amber
                else -> TechColors.LinkGreen
            },
            onClick = onTasks,
        )
        TrendPlate(
            title = "Updates",
            value = upd?.let { (it.pending + hostPending).toString() } ?: "—",
            subtitle = upd?.let { "${it.nodesWithUpdates} nodes · ${it.repoWarnings} repo warn" } ?: "unavailable",
            series = recorded { it.updates?.toFloat() },
            note = RECORDING,
            breakdown = state.updates?.groupBy { it.remoteId }
                ?.map { (remote, nodes) -> "$remote ${nodes.sumOf { it.pending }}" }
                ?.plus(listOfNotNull("PDM $hostPending".takeIf { hostPending > 0 }))
                ?.joinToString(" · "),
            rail = when {
                upd == null -> TechColors.Mute
                upd.errors > 0 -> MaterialTheme.colorScheme.error
                upd.pending + hostPending > 0 || upd.repoWarnings > 0 -> TechColors.Amber
                else -> TechColors.LinkGreen
            },
            onClick = onUpdates,
        )
    }
}

/** CPU, memory, network (PDM's node history), storage and SDN (recorded): number, graph, breakdown. */
@Composable
internal fun ExpandedLoad(
    state: FleetUiState,
    onCpu: () -> Unit,
    onMemory: () -> Unit,
    onStorage: () -> Unit,
    onSdn: () -> Unit,
    onNetwork: () -> Unit,
) {
    val m = state.metrics
    val recorded = recordedSeries(state)
    val trend = state.trend
    val trendNote = trendNote(trend)
    val memFrac = if ((m.pveMemTotal ?: 0L) > 0L) (m.pveMemUsed ?: 0L).toDouble() / m.pveMemTotal!! else null
    val online = state.nodes.filter { it.status == "online" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TrendPlate(
            title = "CPU",
            value = formatCores(m.pveCpuUsed, m.pveCpuMax),
            subtitle = m.pveCpuAllocated?.let { "alloc ${it.toInt()} cores" } ?: "cluster cores",
            series = trend?.data?.cpu,
            note = trendNote,
            breakdown = online.sortedByDescending { it.cpu ?: 0.0 }.take(4)
                .joinToString(" · ") { "${it.name} ${formatPercent(it.cpu)}" },
            rail = TechColors.LinkGreen,
            scaleFloor = 0.10f,
            onClick = onCpu,
        )
        TrendPlate(
            title = "Memory",
            value = formatRatio(m.pveMemUsed, m.pveMemTotal),
            subtitle = formatPercent(memFrac),
            series = trend?.data?.mem,
            note = trendNote,
            breakdown = online.sortedByDescending { fraction(it.mem, it.maxmem) ?: 0.0 }.take(4)
                .joinToString(" · ") { "${it.name} ${formatPercent(fraction(it.mem, it.maxmem))}" },
            rail = TechColors.LinkGreen,
            // Fixed 0-100 %: memory barely moves, and auto-scale made a steady 44 % look full.
            scaleFloor = 1f,
            onClick = onMemory,
        )
        val net = trend?.data?.net
        TrendPlate(
            title = "Network",
            value = net?.lastOrNull { it != null }?.let { formatRate(it.toDouble()) } ?: "—",
            subtitle = "in + out, all nodes",
            series = net,
            note = trendNote,
            breakdown = net?.filterNotNull()?.maxOrNull()?.let { "peak ${formatRate(it.toDouble())} in this window" },
            rail = TechColors.LinkGreen,
            scaleFloor = 1024f,
            onClick = onNetwork,
        )
        TrendPlate(
            title = "Storage",
            value = formatRatio(m.pveStorageUsed, m.pveStorageTotal),
            subtitle = "${m.storages} pools",
            series = recorded { s -> s.storageUsed?.let { u -> s.storageTotal?.takeIf { it > 0 }?.let { u.toFloat() / it } } },
            note = RECORDING,
            breakdown = state.storages.sortedByDescending { fraction(it.disk, it.maxdisk) ?: 0.0 }.take(3)
                .joinToString(" · ") { "${it.name}@${it.node ?: it.remoteId} ${formatPercent(fraction(it.disk, it.maxdisk))}" },
            rail = TechColors.LinkGreen,
            scaleFloor = 1f,
            onClick = onStorage,
        )
        TrendPlate(
            title = "SDN",
            value = m.sdnAvailable.toString(),
            subtitle = if (m.sdnError == 0) "zones ok" else "${m.sdnError} error",
            series = recorded { it.sdnOk.toFloat() },
            note = RECORDING,
            breakdown = state.networks.distinctBy { it.name }.joinToString(" · ") {
                if (it.status == "available" || it.status == "ok") it.name else "${it.name} ${it.status}"
            },
            rail = if (m.sdnError == 0) TechColors.LinkGreen else MaterialTheme.colorScheme.error,
            onClick = onSdn,
        )
    }
}

/** One tile, full width: title, big number and subtitle on the left, graph on the right, a breakdown line under. */
@Composable
private fun TrendPlate(
    title: String,
    value: String,
    subtitle: String,
    series: List<Float?>?,
    note: String,
    breakdown: String?,
    rail: Color,
    onClick: () -> Unit,
    scaleFloor: Float = 1f,
) {
    TechPlate(railColor = rail, modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.36f)) {
                Text(
                    title.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    letterSpacing = 1.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                Text(
                    subtitle,
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(0.64f).padding(end = 16.dp), contentAlignment = Alignment.CenterStart) {
                if ((series?.count { it != null } ?: 0) >= 2) {
                    TechSparkline(series!!, scaleFloor = scaleFloor)
                } else {
                    Text(note, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        breakdown?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val RECORDING = "recording · one point a minute while live"

private fun recordedSeries(state: FleetUiState): ((FleetSample) -> Float?) -> List<Float?> {
    val now = System.currentTimeMillis()
    val window = FleetHistory.windowMs(state.timeframe)
    return { pick -> FleetHistory.series(state.history, now, window, pick = pick) }
}

private fun trendNote(trend: TrendView?): String = when {
    trend == null || (trend.loading && trend.data == null) -> "reading node history…"
    trend.error != null -> "node history did not load: ${trend.error}"
    else -> "no node history for this window"
}

private fun fraction(used: Long?, total: Long?): Double? =
    if (used == null || total == null || total <= 0L) null else used.toDouble() / total

/** `12.4 MB/s`, decimal units like network tools. */
internal fun formatRate(bytesPerSecond: Double): String {
    val units = listOf("B/s", "kB/s", "MB/s", "GB/s")
    var v = bytesPerSecond.coerceAtLeast(0.0)
    var i = 0
    while (v >= 1000 && i < units.lastIndex) {
        v /= 1000
        i++
    }
    return if (v >= 100 || i == 0) "${v.toInt()} ${units[i]}" else "%.1f %s".format(java.util.Locale.US, v, units[i])
}
