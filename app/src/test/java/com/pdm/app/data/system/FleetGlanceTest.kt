package com.pdm.app.data.system

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.system.LaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetGlanceTest {
    private val remotes = listOf(
        RemoteNode("edge-a", "edge-a", "pve"),
        RemoteNode("edge-b", "edge-b", "pve", error = "unreachable"),
    )
    private val guests = listOf(
        Guest("edge-a/qemu/100", "edge-a", "web", "qemu", 100, "running"),
        Guest("edge-a/lxc/101", "edge-a", "dns", "lxc", 101, "stopped"),
        Guest("edge-b/qemu/200", "edge-b", "db", "qemu", 200, "running"),
    )
    private val manageEdgeA = Permissions.of(mapOf("/resource/edge-a" to setOf(Permissions.RESOURCE_MANAGE)))

    private fun glance(live: Boolean = true, writable: Permissions? = manageEdgeA, signedIn: Boolean = true) =
        FleetGlance.of(signedIn, live, demo = false, remotes, guests, nodesOnline = 3, nodes = 4,
            runningTasks = 2, writable = writable, at = 1_000L)

    @Test
    fun countsFaultsAndRunningGuests() {
        val g = glance()
        assertEquals(2, g.remotes)
        assertEquals(1, g.faults)
        assertEquals(2, g.guestsRunning)
        assertEquals(3, g.guests)
        assertEquals("1 fault", g.status)
    }

    @Test
    fun powerFollowsTheKnownPermissionAndLiveData() {
        val byId = glance().guestList.associateBy { it.id }
        assertTrue(byId.getValue("edge-a/qemu/100").canPower)
        assertFalse(byId.getValue("edge-b/qemu/200").canPower)
        // Cached data or unknown permissions: nothing can be powered from outside the app.
        assertTrue(glance(live = false).guestList.none { it.canPower })
        assertTrue(glance(writable = null).guestList.none { it.canPower })
    }

    @Test
    fun signedOutCarriesNothing() {
        val g = glance(signedIn = false)
        assertEquals(FleetGlance.SIGNED_OUT, g)
        assertEquals("Signed out", g.status)
    }

    @Test
    fun savedCopyKeepsCountsButNoGuestsAndIsNeverLive() {
        val store = GlanceStore(FakeSharedPreferences())
        assertEquals(FleetGlance.SIGNED_OUT, store.load())
        val g = glance()
        store.save(g)
        val loaded = store.load()
        assertEquals(g.withoutGuests().copy(live = false), loaded)
        assertTrue(loaded.guestList.isEmpty())
        store.save(FleetGlance.SIGNED_OUT)
        assertEquals(FleetGlance.SIGNED_OUT, store.load())
    }

    @Test
    fun visiblePartIgnoresSecondsAndGuestNames() {
        val a = glance().copy(at = 60_000L)
        val b = a.copy(at = 119_000L, guestList = emptyList())
        assertEquals(GlanceStore.visible(a), GlanceStore.visible(b))
        assertFalse(GlanceStore.visible(a) == GlanceStore.visible(a.copy(at = 120_000L)))
    }

    @Test
    fun launchActionsParse() {
        assertEquals(LaunchAction.Refresh, LaunchAction.parse(LaunchAction.REFRESH, null))
        assertEquals(LaunchAction.Tasks, LaunchAction.parse(LaunchAction.TASKS, null))
        assertEquals(LaunchAction.Updates, LaunchAction.parse(LaunchAction.UPDATES, null))
        assertEquals(LaunchAction.Faults, LaunchAction.parse(LaunchAction.FAULTS, null))
        assertEquals(LaunchAction.Guest("edge-a/qemu/100"), LaunchAction.parse(LaunchAction.GUEST, "edge-a/qemu/100"))
        assertNull(LaunchAction.parse(LaunchAction.GUEST, " "))
        assertNull(LaunchAction.parse("android.intent.action.MAIN", null))
        assertNull(LaunchAction.parse(null, null))
    }
}
