package com.pdm.app.ui.home

import com.pdm.app.data.DemoFleet
import com.pdm.app.data.model.LeaderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderRankTest {
    @Test
    fun sparkLimitStaysFive() {
        assertEquals(5, LEADER_SPARK_LIMIT)
    }

    @Test
    fun rankLeadersJoinsLiveGuestCpuByEntityId() {
        val guests = DemoFleet.guests()
        val dns = guests.first { it.id == "g1" }
        val entry = LeaderEntry("edge-a", "dns", "qemu", last = 0.99, entityId = dns.id)
        val ranked = rankLeaders(listOf(entry), guests, DemoFleet.nodes(), null, LeaderMetric.GuestCpu)
        val row = ranked.single()
        assertEquals(1, row.rank)
        assertTrue(row.live)
        assertEquals(0.12f, row.fraction!!, 0.001f)
        assertTrue(row.caption.contains("now"))
        assertTrue(row.caption.contains("0.24 / 2"))
        assertFalse(row.caption.contains("rrd"))
    }

    @Test
    fun rankLeadersFallsBackToRrdWhenInventoryMisses() {
        val entry = LeaderEntry("edge-a", "ghost", "qemu", last = 0.5, entityId = "nope")
        val ranked = rankLeaders(listOf(entry), DemoFleet.guests(), DemoFleet.nodes(), null, LeaderMetric.GuestCpu)
        val row = ranked.single()
        assertFalse(row.live)
        assertEquals(0.5f, row.fraction!!, 0.001f)
        assertTrue(row.caption.contains("rrd"))
    }

    @Test
    fun rankLeadersFiltersSelectedRemoteAndKeepsOrder() {
        val entries = listOf(
            LeaderEntry("edge-a", "dns", "qemu", last = 0.1, entityId = "g1"),
            LeaderEntry("edge-b", "db", "qemu", last = 0.4, entityId = "g3"),
            LeaderEntry("edge-b", "lab", "qemu", last = 0.2, entityId = "g4"),
        )
        val ranked = rankLeaders(entries, DemoFleet.guests(), DemoFleet.nodes(), "edge-b", LeaderMetric.GuestCpu)
        assertEquals(listOf("db", "lab"), ranked.map { it.entry.name })
        assertEquals(listOf(1, 2), ranked.map { it.rank })
    }

    @Test
    fun rankLeadersJoinsNodeMemoryFromInventory() {
        val node = DemoFleet.nodes().first { it.name == "pve-a" }
        val entry = LeaderEntry("edge-a", "pve-a", "node", last = 0.9, entityId = node.id)
        val ranked = rankLeaders(listOf(entry), DemoFleet.guests(), DemoFleet.nodes(), null, LeaderMetric.NodeMemory)
        val row = ranked.single()
        assertTrue(row.live)
        assertTrue(row.caption.contains("now"))
        assertFalse(row.caption.contains("rrd"))
        assertTrue(row.fraction != null && row.fraction!! > 0f)
    }

    @Test
    fun rankLeadersJoinsNodeCpuByNameWhenEntityIdMisses() {
        val entry = LeaderEntry("edge-b", "pve-b", "node", last = 0.01, entityId = "missing")
        val ranked = rankLeaders(listOf(entry), DemoFleet.guests(), DemoFleet.nodes(), null, LeaderMetric.NodeCpu)
        val row = ranked.single()
        assertTrue(row.live)
        assertEquals(0.44f, row.fraction!!, 0.001f)
        assertTrue(row.caption.contains("now"))
    }

    @Test
    fun sparkWindowKeepsEntriesOutsideGlobalTopFiveWhenRemoteFiltered() {
        val edgeAEntries = (1..5).map { i ->
            LeaderEntry("edge-a", "vm-a$i", "qemu", last = 0.9 - i * 0.05)
        }
        val edgeBEntries = listOf(
            LeaderEntry("edge-b", "vm-b1", "qemu", last = 0.5),
            LeaderEntry("edge-b", "vm-b2", "qemu", last = 0.4),
            LeaderEntry("edge-b", "vm-b3", "qemu", last = 0.3),
        )
        val allEntries = edgeAEntries + edgeBEntries

        val bWindow = sparkWindow(allEntries, "edge-b")
        assertEquals(listOf("vm-b1", "vm-b2", "vm-b3"), bWindow.map { it.name })
        assertEquals(3, bWindow.size)

        val unfiltered = sparkWindow(allEntries, null)
        assertTrue(unfiltered.size <= LEADER_SPARK_LIMIT)
        assertEquals(LEADER_SPARK_LIMIT, unfiltered.size)
    }
}