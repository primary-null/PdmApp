package com.pdm.app.data.repo

import com.pdm.app.data.api.PdmAuth
import com.pdm.app.data.api.PdmClientFactory
import com.pdm.app.data.api.ProbeApi
import com.pdm.app.data.api.TofuDecision
import com.pdm.app.data.api.TofuVerdict
import com.pdm.app.data.api.isCaPin
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.LoginOutcome
import com.pdm.app.data.model.RealmDto
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.model.TicketInfo
import com.pdm.app.data.session.ProbeAuth
import com.pdm.app.data.session.SessionStore
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

private const val TICKET_RENEW_AFTER_MS = 60 * 60 * 1000L
private const val PARTIAL_CSRF = "invalid"

class AuthRepository(
    private val sessionStore: SessionStore,
    private val clients: PdmClientFactory,
) {
    suspend fun login(
        config: ServerConfig,
        saveSecret: Boolean = false,
        /** The second factor, already prefixed (`totp:123456`, `recovery:1a2b-...`); see TfaChallenge. */
        tfaAnswer: String? = null,
        tfaChallenge: String? = null,
        /** The hidden demo button. A typed host of demo is refused without this. */
        allowDemo: Boolean = false,
    ): LoginOutcome {
        if (PdmAuth.isDemoHost(config.host) && !allowDemo) {
            return LoginOutcome.Failed(PdmException("Host required"))
        }
        val attempt = sessionStore.beginLogin()
        var probe: ProbeApi? = null
        return try {
            probe = clients.apiForProbe(config)
            val api = probe.api
            var ticket: String? = null
            var csrf: String? = null
            var cookieName = PdmAuth.COOKIE_NAME
            var username: String? = null

            when (config.authMode) {
                AuthMode.PASSWORD -> {
                    val user = PdmAuth.normalizeUsername(config.username, config.realm)
                    val http = api.createTicket(
                        username = user,
                        // With a challenge the password field carries the factor, never the password.
                        password = if (tfaAnswer.isNullOrBlank() || tfaChallenge.isNullOrBlank()) config.password else tfaAnswer,
                        tfaChallenge = tfaChallenge?.takeIf { !tfaAnswer.isNullOrBlank() },
                    )
                    if (!http.isSuccessful) {
                        val errorBody = http.errorBody()?.string()
                        if (http.code() == 401) {
                            val partial = extractPartialTicket(errorBody)
                            if (partial != null) {
                                return LoginOutcome.NeedsTfa(partial, config)
                            }
                            if (tfaChallenge.isNullOrBlank()) {
                                return LoginOutcome.Failed(
                                    PdmHttpException(
                                        401,
                                        null,
                                        "Login rejected for $user. Check the password and the realm " +
                                            "(pam = Linux user such as root, pdm = PDM-only user).",
                                    ),
                                )
                            }
                        }
                        throw PdmHttpException(http.code(), errorBody, http.message())
                    }
                    val data = http.body()?.data
                        ?: return LoginOutcome.Failed(PdmException("Login failed: empty ticket response"))
                    val cookies = http.headers().values("Set-Cookie")
                    val parsed = PdmAuth.parseSetCookieTicket(cookies)
                    val rawTicket = parsed?.value ?: data.ticket
                    if (needsTfa(data, rawTicket)) {
                        val partial = rawTicket ?: data.infoTicket
                            ?: return LoginOutcome.Failed(PdmException("TFA required but no challenge ticket"))
                        return LoginOutcome.NeedsTfa(partial, config)
                    }
                    if (rawTicket.isNullOrBlank()) {
                        return LoginOutcome.Failed(
                            PdmException("Login failed: no PDMAuthCookie in Set-Cookie (ticket-info is not a session)"),
                        )
                    }
                    ticket = rawTicket
                    cookieName = parsed?.name ?: PdmAuth.COOKIE_NAME
                    csrf = data.csrf
                    username = data.username ?: user
                }
                AuthMode.API_TOKEN -> {
                    if (config.apiToken.isBlank()) {
                        return LoginOutcome.Failed(PdmException("API token is empty"))
                    }
                    username = PdmAuth.normalizeApiToken(config.apiToken).substringBefore('!')
                        .ifBlank { null }
                }
            }

            ticket?.let { probe.probeAuth.set(ProbeAuth(it, csrf, cookieName)) }
            val version = try {
                api.version().data
            } finally {
                probe.probeAuth.set(null)
            }
            val full = SessionState(
                config = config.withoutEphemeralSecrets(),
                ticket = ticket,
                csrf = csrf,
                username = username,
                version = version,
                cookieName = cookieName,
                ticketIssuedAtMs = if (ticket != null) System.currentTimeMillis() else 0L,
            )
            val published = sessionStore.publishIfGeneration(attempt) {
                sessionStore.saveLastForm(config, saveSecret)
                sessionStore.setSession(full)
            }
            if (published) LoginOutcome.Success(full) else LoginOutcome.Failed(PdmException("Session changed"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The handshake refused a certificate nobody has confirmed, before any credential
            // was sent, and captured the pin to confirm. A capture with no saved pin, or under a
            // CA pin, can only mean that refusal.
            certificateQuestion(config, saveSecret, attempt, probe?.capturedFingerprint?.get())?.let { return it }
            sessionStore.publishIfGeneration(attempt) { sessionStore.clearSession() }
            LoginOutcome.Failed(mapError(e))
        }
    }

    private fun certificateQuestion(
        config: ServerConfig,
        saveSecret: Boolean,
        attempt: Long,
        captured: String?,
    ): LoginOutcome.NeedsCertConfirmation? {
        if (captured == null || PdmAuth.isDemoHost(config.host)) return null
        val pin = sessionStore.getEndpointPin(config.host, config.port)
        if (pin != null && !isCaPin(pin)) return null
        val verdict = TofuDecision.evaluate(pin, captured) { false }
        if (verdict !is TofuVerdict.NeedsConfirmation) return null
        val known = sessionStore.savedConnections().any {
            it.host.equals(config.host, ignoreCase = true) && it.port == config.port
        }
        return LoginOutcome.NeedsCertConfirmation(verdict.presentedFp, config, saveSecret, attempt, known)
    }

    /** The user checked the fingerprint and accepted: pin it, then sign in against that pin. */
    suspend fun confirmCertificate(pending: LoginOutcome.NeedsCertConfirmation): LoginOutcome {
        val pinned = sessionStore.publishIfGeneration(pending.attemptGeneration) {
            sessionStore.saveEndpointPin(pending.config.host, pending.config.port, pending.fingerprint)
        }
        if (!pinned) return LoginOutcome.Failed(PdmException("Session changed"))
        return login(pending.config, saveSecret = pending.saveSecret)
    }

    fun rejectCertificate(pending: LoginOutcome.NeedsCertConfirmation) {
        sessionStore.publishIfGeneration(pending.attemptGeneration) { sessionStore.clearSession() }
    }

    /**
     * PDM tickets last about 2 h. `access/ticket` accepts the current ticket as the password and
     * returns a fresh one, so a live session can be kept without holding the account password.
     * Returns true when nothing was due or the renewal landed; false when the ticket was rejected.
     */
    suspend fun renewTicketIfStale(maxAgeMs: Long = TICKET_RENEW_AFTER_MS): Boolean {
        val snap = sessionStore.snapshot() ?: return false
        val state = snap.state
        val ticket = state.ticket
        if (state.config.authMode != AuthMode.PASSWORD || ticket.isNullOrBlank()) return true
        if (PdmAuth.isDemoHost(state.config.host)) return true
        if (System.currentTimeMillis() - state.ticketIssuedAtMs < maxAgeMs) return true
        return try {
            val http = clients.apiForProbe(state.config).api.createTicket(
                username = PdmAuth.normalizeUsername(state.config.username, state.config.realm),
                password = ticket,
            )
            if (!http.isSuccessful) return decideRenewHttp(http.code(), null, null)
            val data = http.body()?.data
            val parsed = PdmAuth.parseSetCookieTicket(http.headers().values("Set-Cookie"))
            val csrf = data?.csrf
            if (parsed == null || !decideRenewHttp(http.code(), parsed.value, csrf)) return false
            sessionStore.replaceTicketIfCurrent(
                snap,
                state.copy(
                    ticket = parsed.value,
                    csrf = csrf,
                    cookieName = parsed.name,
                    ticketIssuedAtMs = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (renewTicketRejected(e)) return false
            e.rethrowIfFleetFatal()
            // Network blips and other HTTP answers: keep the current ticket; the next refresh tries again.
            true
        }
    }

    /**
     * Realms the server offers. Public and credential-free, so it may read from a server whose
     * certificate is not confirmed yet; a pinned endpoint must still match its pin.
     */
    suspend fun realms(hostRaw: String, port: Int): List<RealmDto> {
        val hp = PdmAuth.parseHostPort(hostRaw, port)
        val probe = clients.apiForProbe(ServerConfig(host = hp.host, port = port), allowUnconfirmed = true)
        return probe.api.realms().data.orEmpty().filter { it.realm.isNotBlank() }
    }

    fun logout() {
        sessionStore.clearSession()
        clients.clear()
    }

    private fun needsTfa(data: TicketInfo, rawTicket: String?): Boolean {
        if ((data.needTfa ?: 0) != 0) return true
        // proxmox-auth-api answers a TFA login with a partial ticket in the body and this CSRF value.
        if (data.csrf == PARTIAL_CSRF && !rawTicket.isNullOrBlank()) return true
        val ticket = rawTicket ?: return false
        return ticket.contains("TFA:", ignoreCase = true) ||
            ticket.contains("tfa!", ignoreCase = true) ||
            ticket.contains("TFA-PARTIAL", ignoreCase = true)
    }

    private fun extractPartialTicket(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val json = AppJsonParse(errorBody)
            json
        } catch (_: Exception) {
            null
        }
    }

    private fun AppJsonParse(errorBody: String): String? {
        val root = com.pdm.app.data.api.AppJson.parseToJsonElement(errorBody)
        val obj = root as? kotlinx.serialization.json.JsonObject ?: return null
        val data = obj["data"]
        if (data is kotlinx.serialization.json.JsonObject) {
            val ticket = data["ticket"] as? kotlinx.serialization.json.JsonPrimitive
            if (!ticket?.content.isNullOrBlank()) return ticket.content
        }
        val ticket = obj["ticket"] as? kotlinx.serialization.json.JsonPrimitive
        return ticket?.content?.takeIf { it.isNotBlank() }
    }

    private fun mapError(e: Throwable): Throwable {
        generateSequence(e) { it.cause }.forEach { cause ->
            if (cause is CertificateException) return PdmException(cause.message ?: "TLS rejected", cause)
            if (cause is SSLHandshakeException) return PdmException(cause.rootMessage(), cause)
        }
        return when (e) {
            is PdmException -> e
            is HttpException -> PdmHttpException(e.code(), e.response()?.errorBody()?.string(), e.message(), e)
            else -> PdmException(e.rootMessage(), e)
        }
    }
}

/**
 * True when this HTTP answer should keep the current ticket (any code except 401/403), or when a
 * 2xx answer carried a usable ticket and CSRF. A 2xx without those returns false.
 */
internal fun decideRenewHttp(code: Int, newTicket: String?, csrf: String?): Boolean {
    if (code == 401 || code == 403) return false
    if (code !in 200..299) return true
    return !newTicket.isNullOrBlank() && !csrf.isNullOrBlank() && csrf != PARTIAL_CSRF
}

/** 401/403 from Retrofit or [PdmHttpException]. Other failures are not a rejected ticket. */
internal fun renewTicketRejected(error: Throwable): Boolean {
    if (error.isAuthFailure()) return true
    val code = generateSequence(error) { it.cause }.mapNotNull { cause ->
        when (cause) {
            is HttpException -> cause.code()
            is PdmHttpException -> cause.code
            else -> null
        }
    }.firstOrNull()
    return code == 401 || code == 403
}
