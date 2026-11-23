package com.pdm.app.data

import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.model.RemoteSubscription
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetMapperDashboardTest {
    @Test
    fun demoFleetDashboardCountsRemotesGuestsAndNodes() {
        val metrics = FleetMapper.dashboard(DemoFleet.remotes, DemoFleet.guests())
        assertEquals(2, metrics.pveRemotes)
        assertEquals(1, metrics.pbsRemotes)
        assertEquals(0, metrics.remoteErrors)
        assertEquals(2, metrics.guestsRunning)
        assertEquals(2, metrics.guestsStopped)
        assertEquals(3, metrics.qemu)
        assertEquals(1, metrics.lxc)
        assertEquals(2, metrics.nodes)
    }

    @Test
    fun dashboardPrefersStatusNodeCountAndLoad() = runTest {
        val api = DemoApi()
        val groups = api.resourcesList().data.orEmpty()
        val nodes = FleetMapper.nodesFromResources(groups)
        val status = api.resourcesStatus().data
        val metrics = FleetMapper.dashboard(
            remotes = DemoFleet.remotes,
            guests = DemoFleet.guests(),
            nodes = nodes,
            storages = FleetMapper.storagesFromResources(groups),
            datastores = FleetMapper.datastoresFromResources(groups),
            networks = FleetMapper.networksFromResources(groups),
            status = status,
        )
        assertEquals(3, metrics.nodes)
        assertTrue(metrics.pveCpuMax != null && metrics.pveCpuMax!! > 0)
        assertTrue(metrics.storages > 0)
    }

    @Test
    fun notableSubscriptionsSkipNoneAndBlank() {
        assertTrue(RemoteSubscription("backup", "active").isNotable())
        assertTrue(RemoteSubscription("edge-a", "expired").isNotable())
        assertFalse(RemoteSubscription("edge-a", "none").isNotable())
        assertFalse(RemoteSubscription("edge-a", "NONE").isNotable())
        assertFalse(RemoteSubscription("edge-a", null).isNotable())
        assertFalse(RemoteSubscription("edge-a", "  ").isNotable())
    }
}
