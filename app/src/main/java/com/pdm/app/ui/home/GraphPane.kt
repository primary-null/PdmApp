package com.pdm.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.FleetMapper
import com.pdm.app.data.model.RrdField
import com.pdm.app.data.model.RrdPoint
import com.pdm.app.data.model.RrdTimeframe
import com.pdm.app.data.model.ratio
import com.pdm.app.data.model.series
import com.pdm.app.data.model.stats
import com.pdm.app.ui.GraphView
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.util.formatBytes
import com.pdm.app.ui.util.formatPercent

/** Points drawn per chart; a day of 60 s samples (1440) collapses to this many buckets. */
private const val CHART_POINTS = 120

private val GRAPH_TIMEFRAMES = listOf(RrdTimeframe.HOUR, RrdTimeframe.DAY, RrdTimeframe.WEEK, RrdTimeframe.MONTH)

/** Usage history from `rrddata`: CPU, memory, network, and disk I/O (guests) or load (nodes). */
@Composable
internal fun UsagePlate(view: GraphView?, node: Boolean, onTimeframe: (RrdTimeframe) -> Unit) {
    TechPlate(railColor = TechColors.Edge) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "USAGE",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GRAPH_TIMEFRAMES.forEach { tf ->
                    TechActionPlate(label = tf.label, onClick = { onTimeframe(tf) }, emphasized = view?.timeframe == tf)
                }
            }
        }
        val points = view?.points
        when {
            points == null && view?.error != null -> MutedLine("Usage history did not load: ${view.error}")
            points == null -> MutedLine("reading usage history…")
            // A stopped guest still returns time slots, just with no values in them.
            points.isEmpty() || points.all { it.values.keys.none { k -> k == RrdField.CPU || k == RrdField.MEM_USED } } ->
                MutedLine("no samples in this timeframe (not running)")
            else -> Charts(points, node)
        }
    }
}

@Composable
internal fun HostUsagePlate(
    points: List<RrdPoint>,
    timeframe: RrdTimeframe,
    loading: Boolean,
    onTimeframe: (RrdTimeframe) -> Unit,
) {
    TechPlate(railColor = TechColors.Edge) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "HOST METRICS",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GRAPH_TIMEFRAMES.forEach { tf ->
                    TechActionPlate(label = tf.label, onClick = { onTimeframe(tf) }, emphasized = timeframe == tf)
                }
            }
        }
        when {
            loading && points.isEmpty() -> MutedLine("reading host metrics…")
            points.isEmpty() -> MutedLine("no samples in this timeframe")
            else -> Charts(points, node = true)
        }
    }
}

@Composable
private fun Charts(points: List<RrdPoint>, node: Boolean) {
    val cpu = points.series(RrdField.CPU)
    val cpuStats = cpu.stats()
    val cores = points.lastOrNull { it.values[RrdField.CPU_MAX] != null }?.values?.get(RrdField.CPU_MAX)
    ChartBlock(
        title = "CPU",
        caption = "now ${pct(cpuStats.last)} · avg ${pct(cpuStats.avg)} · peak ${pct(cpuStats.max)}" +
            (cores?.let { " · ${it.toInt()} cores" } ?: ""),
        lines = listOf(cpu to TechColors.LinkGreen),
        floor = 0.10f,
        scaleLabel = { pct(it) },
    )

    val mem = points.ratio(RrdField.MEM_USED, RrdField.MEM_TOTAL)
    val memUsed = points.lastOrNull { it.values[RrdField.MEM_USED] != null }?.values
    ChartBlock(
        title = "MEMORY",
        caption = memUsed?.let { "now ${formatBytes(it[RrdField.MEM_USED]?.toLong())} / ${formatBytes(it[RrdField.MEM_TOTAL]?.toLong())}" }
            ?: "—",
        lines = listOf(mem to TechColors.Amber),
        // Memory reads as "how full", so it is always drawn on a 0-100 % scale.
        floor = 1f,
        scaleLabel = { pct(it) },
    )

    val netIn = points.series(RrdField.NET_IN)
    val netOut = points.series(RrdField.NET_OUT)
    ChartBlock(
        title = "NETWORK",
        caption = "in ${rate(netIn.stats().last)} · out ${rate(netOut.stats().last)} · peak ${rate(maxOf(netIn.stats().max ?: 0f, netOut.stats().max ?: 0f))}",
        lines = listOf(netIn to TechColors.LinkGreen, netOut to TechColors.Amber),
        floor = 1024f,
        scaleLabel = { rate(it) },
        legend = listOf("in" to TechColors.LinkGreen, "out" to TechColors.Amber),
    )

    if (node) {
        val load = points.series(RrdField.LOAD1)
        ChartBlock(
            title = "LOAD",
            caption = "1 min load ${"%.2f".format(java.util.Locale.US, load.stats().last ?: 0f)}" +
                (cores?.let { " on ${it.toInt()} cores" } ?: ""),
            lines = listOf(load to TechColors.LinkGreen),
            // Scale to the core count so "full" means saturated, not "the busiest minute".
            floor = cores?.toFloat() ?: 1f,
            scaleLabel = { "%.1f".format(java.util.Locale.US, it) },
        )
    } else {
        val read = points.series(RrdField.DISK_READ)
        val write = points.series(RrdField.DISK_WRITE)
        ChartBlock(
            title = "DISK I/O",
            caption = "read ${rate(read.stats().last)} · write ${rate(write.stats().last)}",
            lines = listOf(read to TechColors.LinkGreen, write to TechColors.Amber),
            floor = 1024f,
            scaleLabel = { rate(it) },
            legend = listOf("read" to TechColors.LinkGreen, "write" to TechColors.Amber),
        )
    }
}

@Composable
private fun ChartBlock(
    title: String,
    caption: String,
    lines: List<Pair<List<Float?>, Color>>,
    floor: Float,
    scaleLabel: (Float) -> String,
    legend: List<Pair<String, Color>> = emptyList(),
) {
    val drawn = lines.map { (values, color) -> FleetMapper.downsample(values.map { it?.toDouble() }, CHART_POINTS) to color }
    val top = (drawn.flatMap { it.first }.filterNotNull().maxOrNull() ?: 0f).let { maxOf(it * 1.1f, floor) }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp)
            Text(caption, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        }
        legend.forEach { (name, color) ->
            Text("■ $name  ", fontFamily = FontFamily.Monospace, color = color, style = MaterialTheme.typography.labelSmall)
        }
        // Chart top, not the peak: buckets average short spikes away; the caption peak is exact.
        Text("scale ${scaleLabel(top)}", fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(4.dp))
    val grid = TechColors.Edge
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        val w = size.width
        val h = size.height
        // Baseline and half-scale guide; enough to read a level without a full axis.
        drawLine(grid, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        drawLine(
            grid,
            Offset(0f, h / 2),
            Offset(w, h / 2),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        drawn.forEach { (values, color) ->
            if (values.size < 2) return@forEach
            val step = w / (values.size - 1)
            val path = Path()
            var started = false
            values.forEachIndexed { i, v ->
                if (v == null) {
                    started = false
                    return@forEachIndexed
                }
                val x = i * step
                val y = h - (v / top).coerceIn(0f, 1f) * h
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

private fun pct(v: Float?): String = formatPercent(v?.toDouble())

private fun rate(v: Float?): String = if (v == null) "—" else "${formatBytes(v.toLong())}/s"
