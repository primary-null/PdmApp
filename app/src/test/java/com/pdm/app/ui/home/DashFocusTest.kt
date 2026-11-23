package com.pdm.app.ui.home

import com.pdm.app.data.DemoFleet
import com.pdm.app.data.model.LeaderEntry
import com.pdm.app.ui.util.formatUptime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashFocusTest {
    @Test
    fun openFromGuestsDoesNotStack() {
        val nav = DashNav().open(DashFocus.Nodes)
        assertEquals(DashFocus.Nodes, nav.focus)
        assertTrue(nav.stack.isEmpty())
        assertTrue(nav.canPop)
        assertEquals(DashFocus.Guests, nav.pop().focus)
        assertFalse(nav.pop().canPop)
    }

    @Test
    fun listFocusKeepsTheListWhileADetailIsOpen() {
        assertEquals(DashFocus.Guests, DashNav().listFocus)
        assertEquals(DashFocus.Nodes, DashNav().open(DashFocus.Nodes).listFocus)
        // A detail opened from the root list leaves Guests in the middle column.
        assertEquals(DashFocus.Guests, DashNav().open(DashFocus.GuestDetail("g1")).listFocus)
        // A detail opened from Nodes leaves Nodes there, even after hopping detail to detail.
        val nav = DashNav()
            .open(DashFocus.Nodes)
            .open(DashFocus.NodeDetail("n1"))
            .open(DashFocus.GuestDetail("g1"))
        assertEquals(DashFocus.Nodes, nav.listFocus)
        assertTrue(nav.focus.isDetail)
        assertFalse(DashFocus.Tasks.isDetail)
    }

    @Test
    fun nodeThenGuestStacksAndPops() {
        val nav = DashNav()
            .open(DashFocus.Nodes)
            .open(DashFocus.NodeDetail("edge-a/node/pve-a"))
            .open(DashFocus.GuestDetail("g1"))
        assertEquals(DashFocus.GuestDetail("g1"), nav.focus)
        val afterGuest = nav.pop()
        assertEquals(DashFocus.NodeDetail("edge-a/node/pve-a"), afterGuest.focus)
        assertEquals(DashFocus.Nodes, afterGuest.pop().focus)
        assertEquals(DashFocus.Guests, afterGuest.pop().pop().focus)
    }

    @Test
    fun guestsResetsStack() {
        val nav = DashNav().open(DashFocus.Cpu).open(DashFocus.Guests)
        assertEquals(DashFocus.Guests, nav.focus)
        assertFalse(nav.canPop)
        assertTrue(nav.stack.isEmpty())
    }

    @Test
    fun sameFocusIsNoOp() {
        val nav = DashNav().open(DashFocus.Storage)
        assertEquals(nav, nav.open(DashFocus.Storage))
    }

    @Test
    fun leaderMatchesDemoGuestByNameWhenIdsDiffer() {
        val entry = LeaderEntry("edge-a", "dns", "qemu", entityId = "edge-a/qemu/100")
        assertEquals(
            DashFocus.GuestDetail("g1"),
            entry.toFocus(DemoFleet.guests(), DemoFleet.nodes()),
        )
    }

    @Test
    fun leaderMatchesNodeByEntityId() {
        val entry = LeaderEntry("edge-a", "pve-a", "node", entityId = "edge-a/node/pve-a")
        assertEquals(
            DashFocus.NodeDetail("edge-a/node/pve-a"),
            entry.toFocus(DemoFleet.guests(), DemoFleet.nodes()),
        )
    }

    @Test
    fun leaderMatchesLxcByNameWhenIdsDiffer() {
        val entry = LeaderEntry("edge-a", "web", "lxc", entityId = "edge-a/lxc/101")
        assertEquals(
            DashFocus.GuestDetail("g2"),
            entry.toFocus(DemoFleet.guests(), DemoFleet.nodes()),
        )
    }

    @Test
    fun leaderNameFallbackSkipsDuplicateGuestNames() {
        val guests = DemoFleet.guests() + DemoFleet.guests().first().copy(id = "g9", vmid = 199)
        val entry = LeaderEntry("edge-a", "dns", "qemu", entityId = "missing")
        assertNull(entry.toFocus(guests, DemoFleet.nodes()))
    }

    @Test
    fun leaderUnknownKindReturnsNull() {
        val entry = LeaderEntry("edge-a", "dns", "storage")
        assertNull(entry.toFocus(DemoFleet.guests(), DemoFleet.nodes()))
    }

    @Test
    fun stackCapsAtEightAndEmptyStackPopsToGuests() {
        var nav = DashNav()
        repeat(12) { i -> nav = nav.open(DashFocus.NodeDetail("n$i")) }
        assertEquals(8, nav.stack.size)
        assertEquals(DashFocus.Guests, DashNav(focus = DashFocus.Cpu).pop().focus)
    }

    @Test
    fun mapAndDetailKeysDoNotCollide() {
        assertEquals("map/NODE-B/node/NODE-B", mapItemKey("NODE-B/node/NODE-B"))
        assertEquals("detail/NODE-B/node/NODE-B", detailItemKey("NODE-B/node/NODE-B"))
        assertTrue(mapItemKey("x") != detailItemKey("x"))
    }

    @Test
    fun mapPaneItemCountTracksHeadersAndExpandedRows() {
        val empty = mapPaneItemCount(
            notableSubscriptionCount = 0,
            hasLeaders = false,
            cpuGraphsExpanded = false,
            memoryGraphsExpanded = false,
            nodeCount = 0,
            nodesExpanded = false,
            storageCount = 0,
            storageExpanded = false,
            datastoreCount = 0,
            datastoresExpanded = false,
            networkCount = 0,
            sdnExpanded = false,
        )
        assertEquals(5, empty)
        val expandedNodes = mapPaneItemCount(
            notableSubscriptionCount = 1,
            hasLeaders = true,
            cpuGraphsExpanded = true,
            memoryGraphsExpanded = true,
            nodeCount = 5,
            nodesExpanded = true,
            storageCount = 2,
            storageExpanded = false,
            datastoreCount = 0,
            datastoresExpanded = false,
            networkCount = 3,
            sdnExpanded = true,
        )
        assertEquals(3 + 1 + 1 + 2 + 1 + 1 + 2 + 2 + 1 + 5 + 1 + 1 + 3, expandedNodes)
        val withHost = mapPaneItemCount(
            notableSubscriptionCount = 0,
            hasLeaders = false,
            cpuGraphsExpanded = false,
            memoryGraphsExpanded = false,
            nodeCount = 0,
            nodesExpanded = false,
            storageCount = 0,
            storageExpanded = false,
            datastoreCount = 0,
            datastoresExpanded = false,
            networkCount = 0,
            sdnExpanded = false,
            hasHost = true,
        )
        assertEquals(empty + 1, withHost)
    }

    @Test
    fun formatUptimeBuckets() {
        assertEquals("—", formatUptime(null))
        assertEquals("5m", formatUptime(300))
        assertEquals("2h 3m", formatUptime(7_380))
        assertEquals("1d 2h", formatUptime(93_600))
    }
}
