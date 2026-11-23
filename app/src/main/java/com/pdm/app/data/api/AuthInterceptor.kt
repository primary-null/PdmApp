package com.pdm.app.data.api

import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.session.ProbeAuth
import com.pdm.app.data.session.SessionIdentity
import com.pdm.app.data.session.SessionSnapshot
import com.pdm.app.data.session.SessionStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Injects `PDMAPIToken=id:secret` or `Cookie: PDMAuthCookie=...` plus
 * `CSRFPreventionToken` on mutating requests. Probe clients never borrow
 * the live session identity.
 */
class AuthInterceptor(
    private val sessionStore: SessionStore,
    private val boundConfig: ServerConfig? = null,
    private val preferBoundConfig: Boolean = false,
    private val probeAuthSlot: AtomicReference<ProbeAuth?>? = null,
    private val boundSession: SessionSnapshot? = sessionStore.snapshot(),
    private val allowUnconfirmed: Boolean = false,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        // A probe that has not confirmed the certificate must not attach a credential.
        if (allowUnconfirmed) return chain.proceed(builder.build())
        val config = boundConfig ?: boundSession?.state?.config
        if (config != null && request.url.isWithinApi(config)) {
            if (preferBoundConfig) {
                applyConfig(builder, config, request)
            } else {
                val expected = boundSession ?: throw IOException("Session changed")
                val current = sessionStore.currentSession(expected) ?: throw IOException("Session changed")
                if (SessionIdentity.of(config) != SessionIdentity.of(expected.state.config)) {
                    throw IOException("Session changed")
                }
                applySession(builder, current, request)
            }
        }
        return chain.proceed(builder.build())
    }

    private fun applySession(builder: Request.Builder, session: SessionState, request: Request) {
        when (session.config.authMode) {
            AuthMode.API_TOKEN -> {
                val token = session.config.apiToken.trim()
                if (token.isNotEmpty()) {
                    builder.header("Authorization", PdmAuth.tokenAuthorization(token))
                }
            }
            AuthMode.PASSWORD -> {
                val ticket = session.ticket
                if (!ticket.isNullOrBlank()) {
                    builder.header("Cookie", PdmAuth.cookieHeader(session.cookieName, ticket))
                }
                applyCsrf(builder, session.csrf, request.method)
            }
        }
    }

    private fun applyConfig(builder: Request.Builder, config: ServerConfig, request: Request) {
        when (config.authMode) {
            AuthMode.API_TOKEN -> {
                val token = config.apiToken.trim()
                if (token.isNotEmpty()) {
                    builder.header("Authorization", PdmAuth.tokenAuthorization(token))
                }
            }
            AuthMode.PASSWORD -> {
                val auth = probeAuthSlot?.get()
                if (auth != null && auth.ticket.isNotBlank()) {
                    builder.header("Cookie", PdmAuth.cookieHeader(auth.cookieName, auth.ticket))
                    applyCsrf(builder, auth.csrf, request.method)
                }
            }
        }
    }

    private fun applyCsrf(builder: Request.Builder, csrf: String?, method: String) {
        val upper = method.uppercase()
        if (upper != "GET" && upper != "HEAD" && upper != "OPTIONS") {
            if (!csrf.isNullOrBlank()) {
                builder.header("CSRFPreventionToken", csrf)
            }
        }
    }
}
