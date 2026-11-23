package com.pdm.app.data.ops

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.api.PdmApi
import com.pdm.app.data.api.AppJson
import com.pdm.app.data.cache.CACHED_AT_FIELD
import com.pdm.app.data.model.AptUpdateDto
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.ReadAvailability
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.model.UpdateSummaryDto
import com.pdm.app.data.repo.FleetRepository
import com.pdm.app.data.repo.PdmHttpException
import com.pdm.app.data.session.SessionStore
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q4: each optional section says how it was read, through the real fleet load. */
class SectionReadsTest {
    private fun repo(api: PdmApi): FleetRepository {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        store.setSession(
            SessionState(
                config = ServerConfig(host = "demo", authMode = AuthMode.PASSWORD, username = "root@pam"),
                ticket = "PDM:demo",
                csrf = "demo-csrf",
            ),
        )
        return FleetRepository(store, { api })
    }

    @Test
    fun aCachedEnvelopeDecodesWithItsSaveTime() {
        val body = """{"data":[],"$CACHED_AT_FIELD":1700000000000}"""
        val resp = AppJson.decodeFromString(PdmResponse.serializer(ListSerializer(JsonElement.serializer())), body)
        assertEquals(1_700_000_000_000L, resp.cachedAt)
        val live = AppJson.decodeFromString(PdmResponse.serializer(ListSerializer(JsonElement.serializer())), """{"data":[]}""")
        assertNull(live.cachedAt)
    }

    @Test
    fun eachSectionKeepsItsOwnCause() = runBlocking {
        val api = object : PdmApi by DemoApi() {
            override suspend fun updateSummary(): PdmResponse<UpdateSummaryDto> = throw IOException("connection reset")
            override suspend fun subscriptions(): PdmResponse<List<RemoteSubscription>> =
                throw PdmHttpException(403, """{"message":"permission check failed"}""", "Forbidden")
            override suspend fun permissions(): PdmResponse<Map<String, kotlinx.serialization.json.JsonObject>> =
                PdmResponse(data = emptyMap(), cachedAt = 42L)
        }
        val snap = repo(api).load()
        assertEquals(SectionStatus.Kind.UNREACHABLE, snap.reads[FleetSection.UPDATES]?.kind)
        assertEquals(SectionStatus.Kind.FORBIDDEN, snap.reads[FleetSection.SUBSCRIPTIONS]?.kind)
        // A saved copy is labelled as one, with its own age, while its neighbours stay live.
        assertEquals(SectionStatus(SectionStatus.Kind.CACHED, 42L), snap.reads[FleetSection.PERMISSIONS])
        assertEquals(SectionStatus.Kind.LIVE, snap.reads[FleetSection.LEADERS]?.kind)
        assertEquals(SectionStatus.Kind.LIVE, snap.reads[FleetSection.HOST_UPDATES]?.kind)
        // Failed parts carry no made-up values.
        assertNull(snap.updates)
        assertNull(snap.subscriptions)
    }

    @Test
    fun aMissingResourceIsNotCalledUnsupported() = runBlocking {
        val api = object : PdmApi by DemoApi() {
            override suspend fun hostAptUpdates(node: String): PdmResponse<List<AptUpdateDto>> =
                throw PdmHttpException(404, """{"message":"no such node"}""", "Not Found")
        }
        val snap = repo(api).load()
        assertEquals(SectionStatus.Kind.MISSING, snap.reads[FleetSection.HOST_UPDATES]?.kind)
        // The machine's other parts still loaded.
        assertTrue(snap.host?.status != null)
        assertNull(snap.host?.updates)
    }

    @Test
    fun anAnswerWithoutDataIsNotAnEmptyList() = runBlocking {
        val api = object : PdmApi by DemoApi() {
            override suspend fun hostAptUpdates(node: String): PdmResponse<List<AptUpdateDto>> = PdmResponse(data = null)
        }
        val snap = repo(api).load()
        assertEquals(SectionStatus.Kind.FAILED, snap.reads[FleetSection.HOST_UPDATES]?.kind)
        assertNull("not \"nothing pending\"", snap.host?.updates)
    }

    @Test
    fun aQuickLoadOnlyReportsWhatItRead() = runBlocking {
        val r = repo(DemoApi())
        val full = r.load()
        val quick = r.load(depth = com.pdm.app.data.repo.LoadDepth.QUICK, previousHost = full.host)
        assertTrue(FleetSection.UPDATES in full.reads)
        assertTrue(FleetSection.UPDATES !in quick.reads)
        assertTrue(FleetSection.HOST_STATUS in quick.reads)
    }

    @Test
    fun aFailedReadKeepsWhatWasShownButForbiddenShowsNothing() {
        val old = listOf("a")
        assertEquals(old, keepOnFailure(ReadAvailability.Failed("x"), old))
        assertEquals(old, keepOnFailure(ReadAvailability.TransportError("x"), old))
        assertEquals(old, keepOnFailure(null, old))
        assertEquals(listOf("b"), keepOnFailure(ReadAvailability.Stale(listOf("b"), cachedAt = 1L), old))
        assertEquals(emptyList<String>(), keepOnFailure(ReadAvailability.Fresh(emptyList<String>()), old))
        assertNull(keepOnFailure(ReadAvailability.Forbidden(), old))
    }

    @Test
    fun onlyTransientCausesOfferRetry() {
        assertTrue(SectionStatus(SectionStatus.Kind.FAILED).worthRetrying)
        assertTrue(SectionStatus(SectionStatus.Kind.UNREACHABLE).worthRetrying)
        assertTrue(!SectionStatus(SectionStatus.Kind.FORBIDDEN).worthRetrying)
        assertTrue(!SectionStatus(SectionStatus.Kind.UNSUPPORTED).worthRetrying)
    }

}
