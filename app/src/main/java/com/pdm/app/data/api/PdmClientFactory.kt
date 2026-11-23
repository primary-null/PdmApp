package com.pdm.app.data.api

import com.pdm.app.BuildConfig
import com.pdm.app.data.cache.CacheOnlyInterceptor
import com.pdm.app.data.cache.CachingInterceptor
import com.pdm.app.data.cache.ResponseCache
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.repo.redactSecrets
import com.pdm.app.data.session.ProbeAuth
import com.pdm.app.data.session.SessionIdentity
import com.pdm.app.data.session.SessionSnapshot
import com.pdm.app.data.session.SessionStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

data class ProbeApi(
    val api: PdmApi,
    val probeAuth: AtomicReference<ProbeAuth?> = AtomicReference(null),
    val capturedFingerprint: AtomicReference<String?> = AtomicReference(null),
)

class PdmClientFactory(
    private val sessionStore: SessionStore,
    private val activityFeed: ApiActivityFeed = ApiActivityFeed(),
    private val cache: ResponseCache? = null,
) {
    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedApi: PdmApi? = null

    private val demoApi: PdmApi by lazy { DemoApi() }

    @Synchronized
    fun apiForSession(session: SessionSnapshot): PdmApi {
        val config = session.state.config
        check(sessionStore.isCurrent(session)) { "Session changed" }
        activityFeed.bindSession(session.generation)
        if (PdmAuth.isDemoHost(config.host)) return demoApi
        val pin = sessionStore.getEndpointPin(config.host, config.port).orEmpty()
        val offline = session.state.cachedOnly
        val key = "${SessionIdentity.of(config)}|${session.generation}|$pin|$offline"
        cachedApi?.let { existing -> if (cachedKey == key) return existing }
        val api = if (offline) buildCacheOnly(config) else build(config, preferBoundConfig = false, boundSession = session)
        return api.also {
            cachedKey = key
            cachedApi = it
        }
    }

    /**
     * Login, ticket renewal and the realm list. [allowUnconfirmed] lets an unverified certificate
     * through and is only for requests that carry no credential (the public realm list).
     */
    @Synchronized
    fun apiForProbe(config: ServerConfig, allowUnconfirmed: Boolean = false): ProbeApi {
        if (allowUnconfirmed) {
            require(config.password.isBlank() && config.apiToken.isBlank()) {
                "An unconfirmed probe cannot carry a password or API token"
            }
        }
        if (PdmAuth.isDemoHost(config.host)) return ProbeApi(demoApi)
        val slot = AtomicReference<ProbeAuth?>(null)
        val fingerprint = AtomicReference<String?>(null)
        return ProbeApi(
            build(
                config,
                preferBoundConfig = true,
                probeAuthSlot = slot,
                fingerprintSlot = fingerprint,
                allowUnconfirmed = allowUnconfirmed,
            ),
            slot,
            fingerprint,
        )
    }

    @Synchronized
    fun clear() {
        cachedKey = null
        cachedApi = null
        activityFeed.clear()
        activityFeed.clearTls()
    }

    /** Offline session: no TLS, no auth, no network; the interceptor answers from the cache. */
    private fun buildCacheOnly(config: ServerConfig): PdmApi {
        val store = cache ?: throw IllegalStateException("Offline view needs the response cache")
        val client = OkHttpClient.Builder()
            .addInterceptor(CacheOnlyInterceptor(store, ResponseCache.loginKey(SessionIdentity.of(config))))
            .build()
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PdmApi::class.java)
    }

    /**
     * Websocket client for a terminal: the session's pinned TLS and credential, nothing else. No
     * response cache, activity feed or header log ever sees the upgrade or its one-use ticket.
     */
    @Synchronized
    fun shellClient(session: SessionSnapshot): OkHttpClient {
        check(sessionStore.isCurrent(session)) { "Session changed" }
        return securedBuilder(session.state.config, preferBoundConfig = false, boundSession = session)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // No websocket pings: PDM (1.1) answers them with a masked pong, which the RFC forbids
            // and OkHttp rightly drops the line for. termproxy's own "2" keepalive does the job.
            .build()
    }

    private fun build(
        config: ServerConfig,
        preferBoundConfig: Boolean,
        probeAuthSlot: AtomicReference<ProbeAuth?>? = null,
        fingerprintSlot: AtomicReference<String?>? = null,
        boundSession: SessionSnapshot? = null,
        allowUnconfirmed: Boolean = false,
    ): PdmApi {
        val builder = securedBuilder(config, preferBoundConfig, probeAuthSlot, fingerprintSlot, boundSession, allowUnconfirmed)
        // Application interceptor: session and probe only. The shell client never calls build().
        builder.addInterceptor(GetRetryInterceptor())

        if (!preferBoundConfig && boundSession != null) {
            // After auth, before activity: the activity feed reports what the network really did,
            // and a "Session changed" from auth is never papered over with cached data.
            cache?.let { builder.addInterceptor(CachingInterceptor(it, ResponseCache.loginKey(SessionIdentity.of(config)))) }
            builder.addInterceptor(ApiActivityInterceptor(activityFeed, boundSession.generation) {
                sessionStore.isCurrent(boundSession)
            })
        }

        if (BuildConfig.DEBUG) {
            val defaultLogger = HttpLoggingInterceptor.Logger.DEFAULT
            val sanitizedLogger = HttpLoggingInterceptor.Logger { message ->
                val sanitized = redactSecrets(
                    message.replace(
                        Regex("([?&](?:ticket|token)=)[^&\\s]+", RegexOption.IGNORE_CASE),
                        "$1[REDACTED]",
                    ),
                ) ?: message
                defaultLogger.log(sanitized)
            }
            builder.addInterceptor(
                HttpLoggingInterceptor(sanitizedLogger).apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Cookie")
                    redactHeader("Authorization")
                    redactHeader("CSRFPreventionToken")
                    redactHeader("Set-Cookie")
                },
            )
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(builder.build())
            .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(PdmApi::class.java)
    }

    private fun securedBuilder(
        config: ServerConfig,
        preferBoundConfig: Boolean,
        probeAuthSlot: AtomicReference<ProbeAuth?>? = null,
        fingerprintSlot: AtomicReference<String?>? = null,
        boundSession: SessionSnapshot? = null,
        allowUnconfirmed: Boolean = false,
    ): OkHttpClient.Builder {
        val trustManager = TofuTrustManager(
            host = config.host,
            // Every client that can carry a credential needs a saved pin or CA trust. An
            // unconfirmed certificate is refused in the handshake and captured for the user.
            allowUnconfirmed = allowUnconfirmed,
            pinLookup = { sessionStore.getEndpointPin(config.host, config.port) },
            onCertCaptured = { _, fp -> fingerprintSlot?.compareAndSet(null, fp) },
            // Remember the issuing authority of a publicly trusted certificate, so a later
            // certificate from another authority needs confirmation.
            onAuthoritySeen = { caPin ->
                if (!allowUnconfirmed && sessionStore.getEndpointPin(config.host, config.port) == null) {
                    sessionStore.saveEndpointPin(config.host, config.port, caPin)
                }
            },
        )
        val sslSocketFactory = createTofuSslSocketFactory(trustManager)
        val hostnameVerifier = createTofuHostnameVerifier(config.host)

        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .addInterceptor(
                AuthInterceptor(
                    sessionStore,
                    config,
                    preferBoundConfig,
                    probeAuthSlot,
                    boundSession,
                    allowUnconfirmed = allowUnconfirmed,
                ),
            )
    }
}
