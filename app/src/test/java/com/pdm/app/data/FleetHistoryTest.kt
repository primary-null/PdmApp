package com.pdm.app.data

import com.pdm.app.data.model.RrdField
import com.pdm.app.data.model.RrdPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FleetHistoryTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Test
    fun keepsAtMostOneSampleAMinute() {
        var h = emptyList<FleetSample>()
        h = FleetHistory.add(h, FleetSample(t = 0))
        h = FleetHistory.add(h, FleetSample(t = 30_000))
        h = FleetHistory.add(h, FleetSample(t = minute))
        assertEquals(listOf(0L, minute), h.map { it.t })
    }

    @Test
    fun dropsAfterAWeekAndThinsAfterADay() {
        val now = 8 * day
        val samples = (0 until 8 * 24 * 60).map { FleetSample(t = it * minute) }
        val kept = FleetHistory.tidy(samples, now)
        // Nothing older than 7 days.
        assertEquals(true, kept.all { now - it.t <= FleetHistory.KEEP_MS })
        // Last day at full minute resolution, the six days before at one per 10 minutes.
        assertEquals(24 * 60, kept.count { now - it.t <= day })
        val thinned = kept.count { now - it.t > day }
        assertEquals(true, kotlin.math.abs(thinned - 6 * 24 * 6) <= 2)
    }

    @Test
    fun seriesAveragesIntoBucketsAndLeavesGaps() {
        val samples = listOf(FleetSample(t = 0, guestsUp = 2), FleetSample(t = 5_000, guestsUp = 4))
        val s = FleetHistory.series(samples, now = 60_000, windowMs = 60_000, buckets = 6) { it.guestsUp.toFloat() }
        assertEquals(3f, s[0]!!, 0.001f)
        assertNull(s[3])
    }

    @Test
    fun gapsBetweenSamplesCarryTheEarlierValue() {
        val samples = listOf(FleetSample(t = 0, guestsUp = 2), FleetSample(t = 40_000, guestsUp = 5))
        val s = FleetHistory.series(samples, now = 60_000, windowMs = 60_000, buckets = 6) { it.guestsUp.toFloat() }
        assertEquals(listOf(2f, 2f, 2f, 2f, 5f, null), s)
    }

    @Test
    fun fleetTrendSumsNodesWeightedByCores() {
        fun p(t: Long, cpu: Double, max: Double, used: Double, total: Double, net: Double) = RrdPoint(
            t,
            mapOf(
                RrdField.CPU to cpu, RrdField.CPU_MAX to max, RrdField.MEM_USED to used,
                RrdField.MEM_TOTAL to total, RrdField.NET_IN to net, RrdField.NET_OUT to net,
            ),
        )
        val big = listOf(p(1, 0.5, 20.0, 10.0, 60.0, 100.0), p(2, 0.5, 20.0, 10.0, 60.0, 100.0))
        val small = listOf(p(1, 1.0, 4.0, 10.0, 15.0, 50.0))
        val t = FleetTrendMath.aggregate(listOf(big to null, small to null))
        // (0.5*20 + 1*4) / 24
        assertEquals(14f / 24f, t.cpu[0]!!, 0.001f)
        assertEquals(20f / 75f, t.mem[0]!!, 0.001f)
        assertEquals(300f, t.net[0]!!, 0.001f)
        // At t=2 the small node has no point: CPU and memory still read, network shows a gap.
        assertEquals(0.5f, t.cpu[1]!!, 0.001f)
        assertNull(t.net[1])
    }

    @Test
    fun downsampleAveragesConsecutiveValues() {
        val d = FleetTrendMath.downsample(listOf(1f, 3f, null, null, 5f, 7f), 3)
        assertEquals(listOf(2f, null, 6f), d)
    }
}
