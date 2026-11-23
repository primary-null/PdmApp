package com.pdm.app.data.api

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.session.SessionStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Handshake
import okhttp3.CipherSuite
import okhttp3.TlsVersion
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Invocation

class ApiActivityFeedTest {
    private fun request(methodName: String): Request {
        val declaration = PdmApi::class.java.declaredMethods.first { it.name == methodName }
        return Request.Builder()
            .url("https://private-host.invalid/api2/json/actual-sensitive-path?password=query-secret")
            .header("Authorization", "header-secret")
            .tag(Invocation::class.java, Invocation.of(declaration, List(declaration.parameterCount) { "argument-secret" }))
            .build()
    }

    @Test
    fun routeUsesStaticTemplateNeverActualValues() {
        val route = activityRoute(request("nodeAptUpdates"))!!
        assertEquals("GET", route.method)
        assertEquals("/pve/remotes/{remote}/nodes/{node}/apt/update", route.endpoint)
        assertEquals("Reading pending packages", route.action)
        assertFalse(route.toString().contains("secret"))
        assertFalse(route.toString().contains("private-host"))
        assertFalse(route.toString().contains("actual-sensitive-path"))
        assertNull(activityRoute(request("createTicket")))
        assertNull(activityRoute(Request.Builder().url("https://example.invalid/secret").build()))
    }

    @Test
    fun disabledAndOldSessionEventsCannotEnterHistory() {
        val feed = ApiActivityFeed()
        val route = ActivityRoute("GET", "/nodes", "Reading host")
        feed.bindSession(1)
        assertNull(feed.begin(route, 1))
        feed.setEnabled(true)
        feed.bindSession(1)
        val pending = feed.begin(route, 1)!!
        feed.clear()
        feed.bindSession(2)
        feed.finish(pending, 200)
        assertTrue(feed.events.value.isEmpty())
        assertNull(feed.begin(route, 1))
        val next = feed.begin(route, 2)!!
        feed.setEnabled(false)
        feed.finish(next, 200)
        assertTrue(feed.events.value.isEmpty())
    }

    @Test
    fun concurrentRequestsFinishTheirOwnRowsAndHistoryIsBounded() {
        val feed = ApiActivityFeed()
        feed.setEnabled(true)
        feed.bindSession(3)
        val route = ActivityRoute("GET", "/nodes", "Reading host")
        val a = feed.begin(route, 3)!!
        val b = feed.begin(route, 3)!!
        feed.finish(b, 404)
        feed.finish(a, networkError = true)
        assertTrue(feed.events.value.first().networkError)
        assertEquals(404, feed.events.value.last().httpCode)
        assertTrue(feed.events.value.all { it.elapsedMs!! >= 0 })
        // Bounded at 80 (the LIVE panel's recent loads); the background shows at most 6 of them.
        repeat(100) { feed.begin(route, 3) }
        assertEquals(80, feed.events.value.size)
    }

    private class Chain(
        private val source: Request,
        private val fail: Boolean = false,
        private val handshake: Handshake? = null,
        private val during: () -> Unit = {},
    ) : Interceptor.Chain {
        override fun request(): Request = source
        override fun proceed(request: Request): Response {
            during()
            if (fail) throw IOException("private-exception-secret")
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503)
                .handshake(handshake)
                .message("private-response-message").body("private-body-secret".toResponseBody()).build()
        }
        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connection(): okhttp3.Connection? = null
        override fun connectTimeoutMillis(): Int = 1000
        override fun readTimeoutMillis(): Int = 1000
        override fun writeTimeoutMillis(): Int = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun tlsTracksEveryNegotiatedVersionEvenWhenActivityLoggingIsOff() {
        val feed = ApiActivityFeed()
        val observer = ApiActivityInterceptor(feed, 1) { true }
        for (version in listOf(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)) {
            val handshake = Handshake.get(version, CipherSuite.TLS_AES_128_GCM_SHA256, emptyList(), emptyList())
            observer.intercept(Chain(request("nodeAptUpdates"), handshake = handshake)).close()
            assertEquals(version.javaName, feed.tls.value)
        }
        assertTrue(feed.events.value.isEmpty())
    }

    @Test
    fun absentHandshakeDoesNotInventTlsAndOldSessionCannotOverwriteIt() {
        val feed = ApiActivityFeed()
        var current = true
        val observer = ApiActivityInterceptor(feed, 1) { current }
        observer.intercept(Chain(request("nodeAptUpdates"))).close()
        assertNull(feed.tls.value)
        val handshake = Handshake.get(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_GCM_SHA256, emptyList(), emptyList())
        observer.intercept(Chain(request("nodeAptUpdates"), handshake = handshake)).close()
        assertEquals("TLSv1.3", feed.tls.value)
        current = false
        feed.clearTls()
        observer.intercept(Chain(request("nodeAptUpdates"), handshake = handshake)).close()
        assertNull(feed.tls.value)
    }

    @Test
    fun observerPreservesBodyAndNeverStoresExceptionOrResponseText() {
        val feed = ApiActivityFeed()
        feed.setEnabled(true)
        feed.bindSession(1)
        val observer = ApiActivityInterceptor(feed, 1) { true }
        val response = observer.intercept(Chain(request("nodeAptUpdates")))
        assertEquals("private-body-secret", response.body!!.string())
        assertEquals(503, feed.events.value.single().httpCode)
        try {
            observer.intercept(Chain(request("nodeAptUpdates"), fail = true))
            fail("Expected original network error")
        } catch (e: IOException) {
            assertEquals("private-exception-secret", e.message)
        }
        assertTrue(feed.events.value.last().networkError)
        assertFalse(feed.events.value.toString().contains("private-"))
        assertFalse(feed.events.value.toString().contains("secret"))
    }

    @Test
    fun inFlightHandshakeCannotRestoreClearedTls() {
        val feed = ApiActivityFeed()
        val observer = ApiActivityInterceptor(feed, 1) { true }
        val handshake = Handshake.get(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_GCM_SHA256, emptyList(), emptyList())
        observer.intercept(Chain(request("nodeAptUpdates"), handshake = handshake) { feed.clearTls() }).close()
        assertNull(feed.tls.value)
    }

    @Test
    fun noteTlsStoresOnlyVersionNamesAndAStaleEpochCannotWrite() {
        val feed = ApiActivityFeed()
        val epoch = feed.currentTlsEpoch()
        feed.noteTls("https://private-host.invalid/secret", epoch)
        assertNull(feed.tls.value)
        feed.noteTls("TLSv1.2", epoch)
        assertEquals("TLSv1.2", feed.tls.value)
        feed.clearTls()
        feed.noteTls("TLSv1.2", epoch)
        assertNull(feed.tls.value)
    }

    @Test
    fun switchingSessionClearsTlsButTogglingObservationKeepsIt() {
        val feed = ApiActivityFeed()
        feed.setEnabled(true)
        feed.bindSession(1)
        val observer = ApiActivityInterceptor(feed, 1) { true }
        val handshake = Handshake.get(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_GCM_SHA256, emptyList(), emptyList())
        observer.intercept(Chain(request("nodeAptUpdates"), handshake = handshake)).close()
        assertEquals("TLSv1.3", feed.tls.value)
        feed.setEnabled(false)
        feed.setEnabled(true)
        feed.bindSession(1)
        assertEquals("TLSv1.3", feed.tls.value)
        feed.bindSession(2)
        assertNull(feed.tls.value)
        assertTrue(feed.events.value.isEmpty())
    }

    @Test
    fun finishRestoresAnOutcomeTheCapHadDropped() {
        val feed = ApiActivityFeed()
        feed.setEnabled(true)
        feed.bindSession(4)
        val stopped = feed.begin(ActivityRoute("POST", "/pve/remotes/{remote}/qemu/{vmid}/stop", "Requesting guest stop"), 4)!!
        val failed = feed.begin(ActivityRoute("GET", "/resources/list", "Reading fleet inventory"), 4)!!
        repeat(80) { feed.begin(ActivityRoute("GET", "/nodes", "Reading host"), 4) }
        assertEquals(80, feed.events.value.size)
        assertTrue(feed.events.value.none { it.id == stopped.id || it.id == failed.id })
        feed.finish(stopped, 204)
        feed.finish(failed, networkError = true)
        val restoredStop = feed.events.value.single { it.id == stopped.id }
        assertEquals(204, restoredStop.httpCode)
        assertEquals("/pve/remotes/{remote}/qemu/{vmid}/stop", restoredStop.route.endpoint)
        assertEquals(stopped.startedAt, restoredStop.startedAt)
        val restoredFailure = feed.events.value.single { it.id == failed.id }
        assertTrue(restoredFailure.networkError)
        assertEquals("/resources/list", restoredFailure.route.endpoint)
        assertNull(restoredFailure.httpCode)
    }

    @Test
    fun logoutClearsTlsAndAnInFlightHandshakeCannotRestoreIt() {
        val feed = ApiActivityFeed()
        feed.setEnabled(true)
        feed.bindSession(1)
        val factory = PdmClientFactory(SessionStore(injectedPrefs = FakeSharedPreferences()), feed)
        val epoch = feed.currentTlsEpoch()
        feed.noteTls("TLSv1.3", epoch)
        assertEquals("TLSv1.3", feed.tls.value)
        val observer = ApiActivityInterceptor(feed, 1) { true }
        val handshake = Handshake.get(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_GCM_SHA256, emptyList(), emptyList())
        observer.intercept(Chain(request("nodeAptUpdates"), handshake = handshake) { factory.clear() }).close()
        assertNull(feed.tls.value)
        assertTrue(feed.events.value.isEmpty())
        feed.noteTls("TLSv1.3", epoch)
        assertNull(feed.tls.value)
    }

    @Test
    fun sessionChangeDropsTheOpenRowInsteadOfLeavingItInFlight() {
        val feed = ApiActivityFeed()
        feed.setEnabled(true)
        feed.bindSession(1)
        var current = true
        val observer = ApiActivityInterceptor(feed, 1) { current }
        observer.intercept(Chain(request("nodeAptUpdates")) { current = false }).close()
        assertTrue(feed.events.value.isEmpty())
        try {
            current = true
            observer.intercept(Chain(request("nodeAptUpdates"), fail = true) { current = false })
            fail("Expected original network error")
        } catch (e: IOException) {
            assertEquals("private-exception-secret", e.message)
        }
        assertTrue(feed.events.value.isEmpty())
    }
}
