package com.pdm.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.model.LeaderEntry
import com.pdm.app.ui.util.formatPercent

@Composable
fun usageBarColor(fraction: Float): Color = when {
    fraction >= 0.85f -> MaterialTheme.colorScheme.error
    fraction >= 0.70f -> TechColors.Amber
    else -> TechColors.LinkGreen
}

@Composable
fun TechUsageBar(
    label: String,
    fraction: Float?,
    caption: String,
    modifier: Modifier = Modifier,
    /** Track length as a share of the row (see AllocationScale); 1 = full width. */
    span: Float = 1f,
    /** Mark the end of the track: the allocation is the limit, nothing runs past it. */
    capped: Boolean = false,
) {
    if (fraction == null) return
    val color = usageBarColor(fraction)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                caption,
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(
            Modifier
                .fillMaxWidth(span.coerceIn(0.05f, 1f))
                .height(if (capped) 9.dp else 5.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(TechColors.Edge),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .background(color),
                )
            }
            if (capped) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(TechColors.Mute),
                )
            }
        }
    }
}

@Composable
fun TechSparkline(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    lineColor: Color = TechColors.LinkGreen,
    /** Smallest top of scale. Usage fractions under this stay low instead of filling the box. */
    scaleFloor: Float = 0.10f,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        val n = values.size
        if (n < 2) return@Canvas
        val w = size.width
        val h = size.height
        val nums = values.mapNotNull { it }
        if (nums.size < 2) return@Canvas
        val max = (nums.max() * 1.1f).coerceAtLeast(scaleFloor)
        val step = w / (n - 1).coerceAtLeast(1)
        val path = Path()
        var started = false
        values.forEachIndexed { index, value ->
            if (value == null) {
                started = false
                return@forEachIndexed
            }
            val x = index * step
            val y = h - (value / max).coerceIn(0f, 1f) * h
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        val lastIndex = values.indexOfLast { it != null }
        val last = values.getOrNull(lastIndex)
        if (last != null) {
            drawCircle(
                color = lineColor,
                radius = 3.dp.toPx(),
                center = Offset(lastIndex * step, h - (last / max).coerceIn(0f, 1f) * h),
            )
        }
    }
}

@Composable
fun LeaderPlate(
    title: String,
    entries: List<LeaderEntry>,
    selectedRemoteId: String?,
    onEntry: ((LeaderEntry) -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    val visible = entries.filter { selectedRemoteId == null || it.remoteId == selectedRemoteId }
    if (visible.isEmpty()) return
    TechPlate(railColor = TechColors.Amber) {
        Text(
            title.uppercase(),
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(6.dp))
        visible.forEach { entry ->
            val last = entry.last
            val color = usageBarColor((last ?: 0.0).toFloat())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .then(if (onEntry != null) Modifier.clickable { onEntry(entry) } else Modifier),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${entry.remoteId} · ${entry.kind}",
                            fontFamily = FontFamily.Monospace,
                            color = TechColors.Mute,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatPercent(last),
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        entry.average?.let {
                            Text(
                                "avg ${formatPercent(it)}",
                                fontFamily = FontFamily.Monospace,
                                color = TechColors.Mute,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                TechSparkline(entry.series, lineColor = color)
            }
        }
    }
}

@Composable
fun LeaderRankRow(
    rank: Int,
    name: String,
    subtitle: String,
    fraction: Float?,
    caption: String,
    live: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val color = usageBarColor(fraction ?: 0f)
    TechPlate(
        railColor = color,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                "#$rank",
                modifier = Modifier.width(36.dp),
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelMedium,
            )
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        name,
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        caption,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    subtitle,
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    style = MaterialTheme.typography.labelSmall,
                )
                TechUsageBar(
                    label = if (live) "NOW" else "RRD",
                    fraction = fraction,
                    caption = "",
                )
            }
        }
    }
}
