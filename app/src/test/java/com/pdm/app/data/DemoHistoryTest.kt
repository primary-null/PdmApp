package com.pdm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoHistoryTest {
    private val now = 1_800_000_017L

    @Test
    fun windowsCoverEveryRequestedRangeWithoutFutureOrUnboundedSamples() {
        val durations = mapOf(
            "hour" to 3_600L, "day" to 86_400L, "week" to 604_800L,
            "month" to 2_592_000L, "year" to 31_536_000L,
        )
        durations.forEach { (name, duration) ->
            val window = DemoHistory.window(name, now)
            val last = window.timeAt(window.samples - 1)
            assertEquals(name, duration, last - window.start)
            assertTrue(name, now - last in 0 until window.resolution)
            assertTrue(name, window.samples in 60..400)
        }
        assertEquals(DemoHistory.window("hour", now), DemoHistory.window("unknown", now))
    }

    @Test
    fun signalsAreStableBoundedAndDifferentAcrossResources() {
        val window = DemoHistory.window("week", now)
        val first = List(window.samples) { DemoHistory.cpu(Int.MIN_VALUE, window.timeAt(it)) }
        assertEquals(first, List(window.samples) { DemoHistory.cpu(Int.MIN_VALUE, window.timeAt(it)) })
        assertTrue(first.all { it.isFinite() && it in 0.0..1.0 })
        assertTrue(first.max() - first.min() > 0.25)
        assertTrue(first != List(window.samples) { DemoHistory.cpu(101, window.timeAt(it)) })
        assertTrue(List(window.samples) { DemoHistory.memoryRatio(101, window.timeAt(it)) }.all { it in 0.0..1.0 })
    }

    @Test
    fun aWeekIncludesActivityChangesOnEveryDay() {
        val window = DemoHistory.window("week", now)
        val perDay = (0 until window.samples - 1).groupBy { (window.timeAt(it) - window.start) / 86_400 }
        assertEquals(7, perDay.size)
        perDay.values.forEach { indices ->
            val values = indices.map { DemoHistory.cpu(100, window.timeAt(it)) }
            assertTrue(values.max() - values.min() > 0.25)
        }
    }

    private fun snapshot() = FleetSample(
        t = now * 1_000, remotes = 3, remotesOk = 3, guests = 4, guestsUp = 2,
        nodes = 3, nodesUp = 3, tasksRunning = 1, tasksFailed = 0, updates = 5,
        storageUsed = 40L shl 30, storageTotal = 200L shl 30, sdnOk = 2,
    )

    @Test
    fun countsCoverAWeekAndEndAtTheCurrentDemoSnapshot() {
        val current = snapshot()
        val samples = DemoHistory.fleetSamples(current)
        assertEquals(current, samples.last())
        assertEquals(current.t - FleetHistory.KEEP_MS, samples.first().t)
        assertTrue(samples.size in 2_000..2_500)
        assertTrue(samples.zipWithNext().all { (a, b) -> b.t > a.t })
        assertEquals(setOf(60_000L, 600_000L), samples.zipWithNext().map { it.second.t - it.first.t }.toSet())
        assertTrue(samples.all {
            it.guestsUp in 0..it.guests && it.nodesUp in 0..it.nodes && it.remotesOk in 0..it.remotes &&
                it.storageUsed!! in 0..it.storageTotal!! && it.tasksRunning!! >= 0 && it.updates!! >= 0
        })
        assertEquals(samples, DemoHistory.fleetSamples(current))
    }

    @Test
    fun dashboardGraphsAreImmediatelyPopulatedForHourDayAndWeek() {
        val current = snapshot()
        val samples = DemoHistory.fleetSamples(current)
        listOf("hour", "day", "week").forEach { timeframe ->
            val series = FleetHistory.series(samples, current.t, FleetHistory.windowMs(timeframe)) { it.guestsUp.toFloat() }
            assertEquals(60, series.size)
            assertTrue(timeframe, series.all { it != null })
        }
        val week = FleetHistory.series(samples, current.t, FleetHistory.KEEP_MS) { it.guestsUp.toFloat() }.filterNotNull()
        assertTrue(week.max() - week.min() > 0.2f)
    }

    @Test
    fun missingOptionalMetricsStayMissingAndEmptyFleetsStayWithinBounds() {
        val samples = DemoHistory.fleetSamples(FleetSample(t = now * 1_000))
        assertTrue(samples.all {
            it.guestsUp == 0 && it.nodesUp == 0 && it.remotesOk == 0 && it.faults == 0 &&
                it.tasksRunning == null && it.tasksFailed == null && it.updates == null && it.storageUsed == null
        })
    }
}
