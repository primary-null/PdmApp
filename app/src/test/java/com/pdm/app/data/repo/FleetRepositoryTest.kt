package com.pdm.app.data.repo

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.api.PdmApi
import com.pdm.app.data.model.AptUpdateDto
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.HostStatusDto
import com.pdm.app.data.model.HostSubscriptionDto
import com.pdm.app.data.model.PdmNodeRef
import com.pdm.app.data.model.PdmRemote
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.RemoteResourcesGroup
import com.pdm.app.data.model.RemoteTaskDto
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.TaskPhase
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.model.TaskStatisticsDto
import com.pdm.app.data.model.UpdateSummaryDto
import com.pdm.app.data.model.VersionInfo
import com.pdm.app.data.model.RemoteSubscription
import kotlinx.serialization.json.JsonObject
import com.pdm.app.data.model.VmEntry
import com.pdm.app.data.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FleetRepositoryTest {
    private fun signedInStore(): SessionStore {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        store.setSession(
            SessionState(
                config = ServerConfig(host = "demo", authMode = AuthMode.PASSWORD, username = "root@pdm"),
                ticket = "PDM:demo",
                csrf = "demo-csrf",
            ),
        )
        return store
    }

    @Test
    fun loadSurfaces401InsteadOfEmptyHome() = runTest {
        val store = signedInStore()
        val api = object : PdmApi by DemoApi() {
            override suspend fun listRemotes(): PdmResponse<List<PdmRemote>> {
                throw PdmHttpException(401, "authentication failure", "Unauthorized")
            }
        }
        val repo = FleetRepository(store, { api })
        try {
            repo.load()
            fail("expected 401")
        } catch (e: PdmHttpException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun loadRethrowsCancellationFromGuestFallback() = runTest {
        val store = signedInStore()
        val api = object : PdmApi by DemoApi() {
            override suspend fun resourcesList(maxAge: Int): PdmResponse<List<RemoteResourcesGroup>> =
                PdmResponse(data = emptyList())

            override suspend fun listQemu(remote: String): PdmResponse<List<VmEntry>> {
                throw CancellationException("cancelled")
            }
        }
        val repo = FleetRepository(store, { api })
        try {
            repo.load()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun demoLoadReturnsRemotesAndGuests() = runTest {
        val store = signedInStore()
        val repo = FleetRepository(store, { DemoApi() })
        val snap = repo.load()
        assertEquals(3, snap.remotes.size)
        assertEquals(4, snap.guests.size)
        assertTrue(snap.nodes.isNotEmpty())
        assertTrue(snap.storages.isNotEmpty())
        assertTrue(snap.leaders.guestCpu.isNotEmpty())
        assertEquals(3, snap.metrics.nodes)
        assertTrue(snap.guests.any { it.node == "pve-a" && it.cpu != null })
        assertTrue(snap.remotes.first { it.id == "edge-a" }.endpoints.isNotEmpty())
        assertTrue(snap.subscriptions!!.any { it.remote == "backup" && it.isNotable() })
        assertEquals(1, snap.subscriptions!!.count { it.isNotable() })
        val tasks = snap.tasks!!
        assertEquals(3, tasks.size)
        assertEquals(TaskPhase.Running, tasks.first().phase)
        assertEquals("edge-a", tasks.first().remoteId)
        assertTrue(tasks.any { it.phase == TaskPhase.Error && it.remoteId == "backup" })
    }

    @Test
    fun taskListFailureDoesNotBlankFleet() = runTest {
        val store = signedInStore()
        val api = object : PdmApi by DemoApi() {
            override suspend fun listTasks(
                limit: Int,
                running: Boolean?,
                remote: String?,
            ): PdmResponse<List<RemoteTaskDto>> {
                throw PdmHttpException(404, "not found", "Not Found")
            }
        }
        val snap = FleetRepository(store, { api }).load()
        assertEquals(3, snap.remotes.size)
        assertNull(snap.tasks)
    }

    @Test
    fun successfulHttpWithAptFetchErrorIsNotRenderedAsChangelog() = runTest {
        val api = object : PdmApi by DemoApi() {
            override suspend fun aptChangelog(remote: String, node: String, name: String): PdmResponse<String> =
                PdmResponse(data = "E: Failed to fetch example Changelog unavailable (404 Not Found)\nRC: 100")
        }
        val repo = FleetRepository(signedInStore(), { api })
        try {
            repo.changelog("edge-a", "pve-a", "bind9")
            fail("Expected changelog failure")
        } catch (e: PdmException) {
            assertTrue(e.message!!.contains("not published"))
        }
    }

    @Test
    fun quickLoadSkipsSlowTierAndKeepsHostFacts() = runTest {
        val slow = mutableListOf<String>()
        val api = object : PdmApi by DemoApi() {
            override suspend fun subscriptions(): PdmResponse<List<RemoteSubscription>> {
                slow += "subscriptions"; throw AssertionError("slow tier")
            }
            override suspend fun updateSummary(): PdmResponse<UpdateSummaryDto> {
                slow += "updates"; throw AssertionError("slow tier")
            }
            override suspend fun permissions(): PdmResponse<Map<String, JsonObject>> {
                slow += "permissions"; throw AssertionError("slow tier")
            }
            override suspend fun pdmNodes(): PdmResponse<List<PdmNodeRef>> {
                slow += "nodes"; throw AssertionError("slow tier")
            }
            override suspend fun version(): PdmResponse<VersionInfo> {
                slow += "version"; throw AssertionError("slow tier")
            }
            override suspend fun hostAptUpdates(node: String): PdmResponse<List<AptUpdateDto>> {
                slow += "apt"; throw AssertionError("slow tier")
            }
            override suspend fun hostSubscription(node: String): PdmResponse<HostSubscriptionDto> {
                slow += "host-sub"; throw AssertionError("slow tier")
            }
        }
        val full = FleetRepository(signedInStore(), { DemoApi() }).load()
        val previous = full.host!!.copy(status = HostStatusDto())
        val snap = FleetRepository(signedInStore(), { api }).load(depth = LoadDepth.QUICK, previousHost = previous)
        assertEquals(emptyList<String>(), slow)
        assertEquals(3, snap.remotes.size)
        assertNull(snap.subscriptions)
        assertNull(snap.updates)
        assertNull(snap.permissions)
        val host = snap.host!!
        assertEquals(previous.version, host.version)
        assertEquals(previous.updates, host.updates)
        assertNotNull(host.status)
        assertEquals(full.host!!.status, host.status)
    }
}
