package com.pdm.app.data

import kotlinx.serialization.Serializable

/**
 * What the dashboard counted at one live load. PDM keeps no history for these counts, so the app
 * records one per minute while it is live and keeps a week (see [FleetHistory]).
 */
@Serializable
data class FleetSample(
    /** Wall clock, epoch ms. */
    val t: Long,
    val remotes: Int = 0,
    val remotesOk: Int = 0,
    val guests: Int = 0,
    val guestsUp: Int = 0,
    val nodes: Int = 0,
    val nodesUp: Int = 0,
    val faults: Int = 0,
    val tasksRunning: Int? = null,
    val tasksFailed: Int? = null,
    val updates: Int? = null,
    val storageUsed: Long? = null,
    val storageTotal: Long? = null,
    val sdnOk: Int = 0,
    val sdnError: Int = 0,
)

@Serializable
data class FleetHistoryFile(val samples: List<FleetSample> = emptyList())

/** Recorded dashboard counts: one per minute, a week at most, thinned to 10 min after a day. */
object FleetHistory {
    const val MIN_GAP_MS = 55_000L
    const val KEEP_MS = 7 * 24 * 3_600_000L
    private const val DAY_MS = 24 * 3_600_000L
    private const val THIN_STEP_MS = 10 * 60_000L

    /** Add [sample] (dropped if the last one is under a minute old), then prune and thin. */
    fun add(samples: List<FleetSample>, sample: FleetSample): List<FleetSample> {
        val last = samples.lastOrNull()
        val next = if (last != null && sample.t - last.t < MIN_GAP_MS) samples else samples + sample
        return tidy(next, sample.t)
    }

    /** Drop samples older than a week; older than a day keep the first of each 10-minute slot. */
    fun tidy(samples: List<FleetSample>, now: Long): List<FleetSample> {
        var lastSlot = Long.MIN_VALUE
        return samples.sortedBy { it.t }.filter { s ->
            when {
                now - s.t > KEEP_MS -> false
                now - s.t <= DAY_MS -> true
                else -> {
                    val slot = s.t / THIN_STEP_MS
                    (slot != lastSlot).also { lastSlot = slot }
                }
            }
        }
    }

    /**
     * [buckets] averages of [pick] across the [windowMs] before [now], oldest first. Counts hold
     * until they change, so an empty bucket between two samples carries the earlier value (a line,
     * not scattered dots); before the first and after the last sample stays null.
     */
    fun series(
        samples: List<FleetSample>,
        now: Long,
        windowMs: Long,
        buckets: Int = 60,
        pick: (FleetSample) -> Float?,
    ): List<Float?> {
        val start = now - windowMs
        val width = windowMs.toDouble() / buckets
        val sums = DoubleArray(buckets)
        val counts = IntArray(buckets)
        samples.forEach { s ->
            if (s.t < start || s.t > now) return@forEach
            val v = pick(s) ?: return@forEach
            val i = ((s.t - start) / width).toInt().coerceIn(0, buckets - 1)
            sums[i] += v
            counts[i]++
        }
        val raw = List(buckets) { i -> if (counts[i] == 0) null else (sums[i] / counts[i]).toFloat() }
        val first = raw.indexOfFirst { it != null }
        val last = raw.indexOfLast { it != null }
        if (first < 0) return raw
        var carry: Float? = null
        return raw.mapIndexed { i, v ->
            when {
                i < first || i > last -> null
                v != null -> v.also { carry = it }
                else -> carry
            }
        }
    }

    fun windowMs(timeframe: String): Long = when (timeframe) {
        "hour" -> 3_600_000L
        "week" -> KEEP_MS
        else -> DAY_MS
    }
}
