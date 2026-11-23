package com.pdm.app.ui.util

import java.util.Locale
import kotlin.math.abs

fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    if (abs(bytes) < 1024) return "${bytes}B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
    var value = bytes.toDouble()
    var unit = -1
    while (abs(value) >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val text = if (abs(value) >= 10) value.toInt().toString() else "%.1f".format(Locale.US, value)
    return text + units[unit]
}

fun formatRatio(used: Long?, total: Long?): String {
    if (used == null || total == null || total <= 0L) return "—"
    return "${formatBytes(used)} / ${formatBytes(total)}"
}

fun formatCores(used: Double?, max: Double?): String {
    if (used == null || max == null || max <= 0.0) return "—"
    val usedText = if (used < 10) "%.2f".format(Locale.US, used) else used.toInt().toString()
    val maxText = if (max % 1.0 == 0.0) max.toInt().toString() else "%.1f".format(Locale.US, max)
    return "$usedText / $maxText"
}

fun formatPercent(fraction: Double?): String {
    if (fraction == null) return "—"
    val pct = (fraction * 100.0).coerceAtLeast(0.0)
    return if (pct < 1.0) "%.2f%%".format(Locale.US, pct) else "%.0f%%".format(Locale.US, pct)
}

fun fractionOf(used: Long?, total: Long?): Float? {
    if (used == null || total == null || total <= 0L) return null
    return (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

fun fractionOf(used: Double?, total: Double?): Float? {
    if (used == null || total == null || total <= 0.0) return null
    return (used / total).toFloat().coerceIn(0f, 1f)
}

fun formatTaskAge(startEpochSeconds: Long, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): String {
    if (startEpochSeconds <= 0L) return "—"
    val delta = (nowEpochSeconds - startEpochSeconds).coerceAtLeast(0L)
    return when {
        delta < 60 -> "just now"
        delta < 3_600 -> "${delta / 60}m ago"
        delta < 86_400 -> "${delta / 3_600}h ago"
        else -> "${delta / 86_400}d ago"
    }
}

/** Epoch seconds as local `Oct 23 06:11`. */
fun formatTimestamp(epochSeconds: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US)
        .format(java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault()))

fun formatUptime(seconds: Long?): String {
    if (seconds == null || seconds < 0L) return "—"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
