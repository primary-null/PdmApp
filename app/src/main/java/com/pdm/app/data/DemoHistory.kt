package com.pdm.app.data

import com.pdm.app.data.model.ResourceRrdData
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Synthetic history shared by demo usage, leaders and dashboard counts. No persistence or I/O. */
object DemoHistory {
    private const val DAY_SECONDS = 86_400L

    data class Window(val start: Long, val resolution: Long, val samples: Int) {
        fun timeAt(index: Int): Long = start + index * resolution
    }

    /** Keep long ranges bounded while covering the entire requested interval. Times are epoch seconds. */
    fun window(timeframe: String, nowSeconds: Long): Window {
        val (duration, resolution) = when (timeframe) {
            "day" -> DAY_SECONDS to 300L
            "week" -> 7 * DAY_SECONDS to 1_800L
            "month" -> 30 * DAY_SECONDS to 7_200L
            "year" -> 365 * DAY_SECONDS to DAY_SECONDS
            else -> 3_600L to 60L
        }
        val end = Math.floorDiv(nowSeconds, resolution) * resolution
        return Window(end - duration, resolution, (duration / resolution).toInt() + 1)
    }

    private fun wave(time: Long, period: Long, phase: Double): Double =
        (sin(2 * PI * (Math.floorMod(time, period).toDouble() / period + phase)) + 1) / 2

    /** Absolute-time signals stay consistent when switching between overlapping ranges. */
    fun cpu(seed: Int, time: Long): Double {
        val phase = Math.floorMod(seed, 29) / 29.0
        val daily = wave(time, DAY_SECONDS, phase * 0.15 - 0.35)
        val weekly = wave(time, 7 * DAY_SECONDS, phase)
        val short = wave(time, 5_400L, phase)
        val backupTime = Math.floorMod(time + Math.floorMod(seed, 7) * 3_600L, DAY_SECONDS).toDouble() / DAY_SECONDS
        val distance = (backupTime - 0.12) / 0.025
        val backup = exp(-distance * distance)
        return (0.06 + 0.3 * daily + 0.07 * weekly + 0.04 * short + 0.3 * backup).coerceIn(0.02, 0.95)
    }

    fun memoryRatio(seed: Int, time: Long): Double {
        val phase = Math.floorMod(seed, 29) / 29.0
        return (0.31 + 0.18 * wave(time, DAY_SECONDS, phase * 0.15 - 0.4) +
            0.05 * wave(time, 7 * DAY_SECONDS, phase) + 0.05 * cpu(seed, time)).coerceIn(0.0, 1.0)
    }

    fun leaderSeries(window: Window, seed: Int, memory: Boolean = false): ResourceRrdData = ResourceRrdData(
        start = window.start,
        resolution = window.resolution,
        data = List(window.samples) { i ->
            val time = window.timeAt(i)
            if (memory) memoryRatio(seed, time) else cpu(seed, time)
        },
    )

    /** A week of counts, ending at the current demo snapshot; dense enough for every dashboard range. */
    fun fleetSamples(current: FleetSample): List<FleetSample> = buildList {
        val dayMs = DAY_SECONDS * 1_000
        var time = current.t - FleetHistory.KEEP_MS
        while (time <= current.t) {
            val seconds = time / 1_000
            val load = cpu(17, seconds)
            val outage = if (current.remotes > 0 && current.nodes > 0 &&
                Math.floorMod(seconds / DAY_SECONDS, 3L) == 1L &&
                Math.floorMod(seconds, DAY_SECONDS) in 10_800L..14_400L
            ) 1 else 0
            val guestsChange = when {
                load > 0.35 -> 1
                load < 0.2 -> -1
                else -> 0
            }
            add(current.copy(
                t = time,
                remotesOk = (current.remotesOk - outage).coerceIn(0, current.remotes),
                guestsUp = (current.guestsUp + guestsChange).coerceIn(0, current.guests),
                nodesUp = (current.nodesUp - outage).coerceIn(0, current.nodes),
                faults = current.faults + outage,
                tasksRunning = current.tasksRunning?.let { (it + (load * 4).toInt() - 1).coerceAtLeast(0) },
                tasksFailed = current.tasksFailed?.plus(outage),
                updates = current.updates?.let { (it + Math.floorMod(seconds / 3_600, 24L).toInt() / 4 - 2).coerceAtLeast(0) },
                storageUsed = current.storageUsed?.let {
                    (it * (0.85 + load * 0.45)).toLong().coerceIn(0, current.storageTotal ?: Long.MAX_VALUE)
                },
                sdnOk = (current.sdnOk - outage).coerceAtLeast(0),
                sdnError = current.sdnError + outage,
            ))
            time += if (current.t - time > dayMs) 600_000L else 60_000L
        }
        // Counts shown beside the graphs and their final sample describe the same demo snapshot.
        this[lastIndex] = current
    }
}
