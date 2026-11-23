package com.pdm.app.data.api

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Interceptor chain only: no socket and no MockWebServer. */
class GetRetryInterceptorTest {
    private fun client(next: Interceptor) = OkHttpClient.Builder()
        .addInterceptor(GetRetryInterceptor())
        .addInterceptor(next)
        .build()

    private fun call(method: String, next: Interceptor): Response {
        val builder = Request.Builder().url("https://192.0.2.10/api2/json/ping")
        val request = if (method == "POST") {
            builder.post(FormBody.Builder().add("command", "reboot").build()).build()
        } else {
            builder.get().build()
        }
        return client(next).newCall(request).execute()
    }

    @Test
    fun postIsNotRetried() {
        var attempts = 0
        try {
            call("POST", Interceptor {
                attempts++
                throw IOException("connection reset")
            })
            fail("POST must surface the failure")
        } catch (_: IOException) {
        }
        assertEquals(1, attempts)
    }

    @Test
    fun getRetriesOnceThenSucceeds() {
        var attempts = 0
        val response = call("GET", Interceptor { chain ->
            attempts++
            if (attempts == 1) throw IOException("blip")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody(null))
                .build()
        })
        response.use { assertEquals(200, it.code) }
        assertEquals(2, attempts)
    }

    @Test
    fun sslFailureIsNotRetried() {
        var attempts = 0
        try {
            call("GET", Interceptor {
                attempts++
                throw SSLHandshakeException("certificate")
            })
            fail("TLS failure must surface")
        } catch (_: SSLHandshakeException) {
        }
        assertEquals(1, attempts)
    }
}
