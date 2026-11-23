package com.pdm.app.data.api

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.session.SessionStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthInterceptorTest {
    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var interceptedRequest: Request? = null

        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            interceptedRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connection(): okhttp3.Connection? = null
        override fun connectTimeoutMillis(): Int = 10000
        override fun readTimeoutMillis(): Int = 10000
        override fun writeTimeoutMillis(): Int = 10000
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun passwordPostSendsPdmCookieAndCsrf() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val config = ServerConfig(host = "192.0.2.10", port = 8443, authMode = AuthMode.PASSWORD)
        store.setSession(SessionState(config = config, ticket = "ticket-1", csrf = "csrf-1"))
        val interceptor = AuthInterceptor(store)
        val request = Request.Builder()
            .url("https://192.0.2.10:8443/api2/json/pve/remotes/edge-a/qemu/100/start")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val chain = FakeChain(request)
        interceptor.intercept(chain)
        val intercepted = chain.interceptedRequest
        assertEquals("PDMAuthCookie=ticket-1", intercepted?.header("Cookie"))
        assertEquals("csrf-1", intercepted?.header("CSRFPreventionToken"))
        assertNull(intercepted?.header("Authorization"))
    }

    @Test
    fun tokenHeaderUsesColonNotEqualsBeforeSecret() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val config = ServerConfig(
            host = "192.0.2.10",
            port = 8443,
            authMode = AuthMode.API_TOKEN,
            apiToken = "root@pdm!ops=uuid-secret",
        )
        store.setSession(SessionState(config = config))
        val interceptor = AuthInterceptor(store)
        val request = Request.Builder()
            .url("https://192.0.2.10:8443/api2/json/remotes/remote")
            .get()
            .build()
        val chain = FakeChain(request)
        interceptor.intercept(chain)
        assertEquals(
            "PDMAPIToken=root@pdm!ops:uuid-secret",
            chain.interceptedRequest?.header("Authorization"),
        )
        assertNull(chain.interceptedRequest?.header("Cookie"))
    }

    @Test
    fun passwordPostEchoesHostPrefixedCookieName() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val config = ServerConfig(host = "192.0.2.10", port = 8443, authMode = AuthMode.PASSWORD)
        store.setSession(
            SessionState(
                config = config,
                ticket = "ticket-1",
                csrf = "csrf-1",
                cookieName = "__Host-PDMAuthCookie",
            ),
        )
        val interceptor = AuthInterceptor(store)
        val request = Request.Builder()
            .url("https://192.0.2.10:8443/api2/json/pve/remotes/edge-a/qemu/100/start")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val chain = FakeChain(request)
        interceptor.intercept(chain)
        assertEquals("__Host-PDMAuthCookie=ticket-1", chain.interceptedRequest?.header("Cookie"))
    }

    @Test
    fun unconfirmedProbeSendsNoCredential() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val config = ServerConfig(
            host = "192.0.2.10",
            port = 8443,
            authMode = AuthMode.API_TOKEN,
            apiToken = "root@pdm!ops:uuid-secret",
            password = "should-not-send",
        )
        store.setSession(SessionState(config = config, ticket = "ticket-1", csrf = "csrf-1"))
        val interceptor = AuthInterceptor(store, boundConfig = config, preferBoundConfig = true, allowUnconfirmed = true)
        val request = Request.Builder().url("https://192.0.2.10:8443/api2/json/access/domains").get().build()
        val chain = FakeChain(request)
        interceptor.intercept(chain)
        assertNull(chain.interceptedRequest?.header("Authorization"))
        assertNull(chain.interceptedRequest?.header("Cookie"))
        assertNull(chain.interceptedRequest?.header("CSRFPreventionToken"))
    }

    @Test
    fun unconfirmedProbeRefusesATokenConfig() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val factory = PdmClientFactory(store)
        try {
            factory.apiForProbe(
                ServerConfig(host = "192.0.2.10", authMode = AuthMode.API_TOKEN, apiToken = "root@pdm!ops:secret"),
                allowUnconfirmed = true,
            )
            org.junit.Assert.fail("unconfirmed probe must refuse a token")
        } catch (e: IllegalArgumentException) {
            org.junit.Assert.assertTrue(e.message.orEmpty().contains("token"))
        }
    }
}
