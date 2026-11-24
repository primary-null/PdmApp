package com.pdm.app.data

import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.model.RrdField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoApiTest {
    @Test
    fun startAndStopMutateGuestState() = runTest {
        val api = DemoApi()
        val before = api.listLxc("edge-a").data.orEmpty().first { it.vmid == 101L }
        assertEquals("stopped", before.status)
        api.lxcStart("edge-a", 101)
        val running = api.listLxc("edge-a").data.orEmpty().first { it.vmid == 101L }
        assertEquals("running", running.status)
        api.lxcStop("edge-a", 101)
        val stopped = api.listLxc("edge-a").data.orEmpty().first { it.vmid == 101L }
        assertEquals("stopped", stopped.status)
    }

    @Test
    fun resourcesListGroupsRemotes() = runTest {
        val api = DemoApi()
        val groups = api.resourcesList().data.orEmpty()
        assertTrue(groups.any { it.remote == "edge-a" })
        assertTrue(groups.any { it.remote == "backup" })
        val guests = FleetMapper.guestsFromResources(groups)
        assertTrue(guests.any { it.name == "dns" && it.type == "qemu" })
        assertEquals(4, guests.size)
        assertTrue(guests.any { it.cpu != null })
        assertEquals(3, FleetMapper.nodesFromResources(groups).size)
    }

    @Test
    fun ticketReturnsDemoCookiePayload() = runTest {
        val http = DemoApi().createTicket("root@pdm", "x")
        assertTrue(http.isSuccessful)
        assertEquals("PDM:demo", http.body()?.data?.ticket)
    }

    @Test
    fun guestNodeAndHostHistoryHonorEveryTimeframe() = runTest {
        val now = 1_800_000_017L
        val api = DemoApi { now }
        val durations = mapOf(
            "hour" to 3_600L, "day" to 86_400L, "week" to 604_800L,
            "month" to 2_592_000L, "year" to 31_536_000L,
        )
        durations.forEach { (timeframe, duration) ->
            val histories = listOf(
                api.guestRrd("edge-a", "qemu", 100, timeframe, "AVERAGE").data.orEmpty(),
                api.nodeRrd("edge-a", "pve-a", timeframe, "AVERAGE").data.orEmpty(),
                api.hostRrd("pdm-demo", timeframe, "AVERAGE").data.orEmpty(),
            ).map(FleetMapper::rrdFromJson)
            histories.forEach { points ->
                assertEquals(timeframe, duration, points.last().time - points.first().time)
                assertTrue(points.size in 60..400)
                assertTrue(points.zipWithNext().all { it.second.time > it.first.time })
                assertTrue(now - points.last().time in 0 until (points[1].time - points[0].time))
                assertTrue(points.all { it.values.getValue(RrdField.CPU) in 0.0..1.0 })
            }
            assertEquals(histories[0].map { it.time }, histories[1].map { it.time })
            assertEquals(histories[0].map { it.time }, histories[2].map { it.time })
        }
    }

    @Test
    fun leaderHistoryMatchesUsageHistoryAndSelectedRange() = runTest {
        val api = DemoApi { 1_800_000_017L }
        for ((timeframe, duration) in listOf("hour" to 3_600L, "day" to 86_400L, "week" to 604_800L)) {
            val leaders = api.topEntities(timeframe).data!!
            (leaders.guestCpu + leaders.nodeCpu + leaders.nodeMemory).forEach {
                val rrd = it.rrdData
                assertEquals(duration, rrd.resolution * (rrd.data.size - 1))
                assertTrue(rrd.start > 1_700_000_000)
            }
            val guest = leaders.guestCpu.first()
            val guestUsage = FleetMapper.rrdFromJson(api.guestRrd(guest.remote, "qemu", guest.resource.vmid!!, timeframe, "AVERAGE").data!!)
            assertEquals(guest.rrdData.data, guestUsage.map { it.values[RrdField.CPU] })
            val node = leaders.nodeCpu.first()
            val usage = FleetMapper.rrdFromJson(api.nodeRrd(node.remote, node.resource.node!!, timeframe, "AVERAGE").data!!)
            assertEquals(node.rrdData.data, usage.map { it.values[RrdField.CPU] })
            val memory = leaders.nodeMemory.first().rrdData.data
            usage.forEachIndexed { index, point ->
                assertEquals(memory[index]!!, point.values.getValue(RrdField.MEM_USED) / point.values.getValue(RrdField.MEM_TOTAL), 0.000000001)
            }
        }
    }

    @Test
    fun overlappingRangesDescribeTheSameActivityAtSharedTimestamps() = runTest {
        val api = DemoApi { 1_800_000_017L }
        val hour = FleetMapper.rrdFromJson(api.guestRrd("edge-a", "qemu", 100, "hour", "AVERAGE").data!!).associateBy { it.time }
        val week = FleetMapper.rrdFromJson(api.guestRrd("edge-a", "qemu", 100, "week", "AVERAGE").data!!)
        val shared = week.filter { it.time in hour }
        assertTrue(shared.size >= 2)
        shared.forEach { assertEquals(hour.getValue(it.time).values, it.values) }
    }

    @Test
    fun aWeeklyFleetTrendHasCpuMemoryAndNetworkAcrossTheWholePlot() = runTest {
        val api = DemoApi { 1_800_000_017L }
        val histories = DemoFleet.nodes().map { node ->
            FleetMapper.rrdFromJson(api.nodeRrd(node.remoteId, node.name, "week", "AVERAGE").data!!) to node.maxcpu
        }
        val trend = FleetTrendMath.aggregate(histories)
        for (series in listOf(trend.cpu, trend.mem, trend.net)) {
            assertEquals(120, series.size)
            assertTrue(series.all { it != null && it.isFinite() })
            val values = series.filterNotNull()
            assertTrue(values.max() > values.min())
        }
    }
}
