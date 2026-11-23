package com.pdm.app.data

import com.pdm.app.data.api.DemoApi
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
}
