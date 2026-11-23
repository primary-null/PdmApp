package com.pdm.app.data.api

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import retrofit2.Invocation
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** OkHttp [okhttp3.TlsVersion.javaName] values. Anything else is not a version and is ignored. */
private val TLS_VERSION_NAMES = setOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1", "SSLv3")

/** Only static method annotations enter this model. No URL values, bodies, headers or exceptions. */
data class ActivityRoute(val method: String, val endpoint: String, val action: String)
data class ApiActivity(
    val id: Long,
    val route: ActivityRoute,
    val httpCode: Int? = null,
    val elapsedMs: Long? = null,
    val networkError: Boolean = false,
    /** The app stopped waiting (newer refresh, left the screen); not a server or network fault. */
    val cancelled: Boolean = false,
    /** Wall clock when the request left, for the LIVE panel. */
    val startedAt: Long = System.currentTimeMillis(),
) {
    val outcome: String get() = when {
        cancelled -> "CANCELLED"
        networkError -> "NO HTTP RESPONSE"
        httpCode != null -> "HTTP $httpCode"
        else -> "IN FLIGHT"
    }
}

/** Deliberately reads only our Retrofit declaration, never the actual URL or invocation arguments. */
internal fun activityRoute(request: Request): ActivityRoute? {
    val declaration = request.tag(Invocation::class.java)?.method() ?: return null
    if (declaration.declaringClass != PdmApi::class.java) return null
    val method: String
    val path: String
    when {
        declaration.isAnnotationPresent(GET::class.java) -> {
            method = "GET"
            path = declaration.getAnnotation(GET::class.java)!!.value
        }
        declaration.isAnnotationPresent(POST::class.java) -> {
            method = "POST"
            path = declaration.getAnnotation(POST::class.java)!!.value
        }
        declaration.isAnnotationPresent(DELETE::class.java) -> {
            method = "DELETE"
            path = declaration.getAnnotation(DELETE::class.java)!!.value
        }
        else -> return null
    }
    if (path == "access/ticket" || !path.matches(Regex("[A-Za-z0-9_/{}/-]+"))) return null
    val action = when {
        path == "resources/list" -> "Reading fleet inventory"
        path == "resources/status" -> "Reading fleet health"
        path == "resources/top-entities" -> "Reading CPU and memory leaders"
        path == "resources/subscription" -> "Reading subscription status"
        path == "remotes/remote" -> "Reading remote connections"
        path == "remotes/tasks/list" -> "Reading recent tasks"
        path == "remotes/tasks/statistics" -> "Reading task statistics"
        path == "remotes/metric-collection/status" -> "Checking metric collection"
        path == "pve/firewall/status" -> "Reading fleet firewall status"
        path.endsWith("/subscription") -> "Reading host subscription"
        path.endsWith("cluster-nextid") -> "Finding the next guest ID"
        path == "remotes/updates/summary" -> "Reading update summary"
        path == "remotes/updates/refresh" -> "Asking PDM to refresh updates"
        path.endsWith("apt/changelog") -> "Reading package changelog"
        path.endsWith("apt/update") -> "Reading pending packages"
        path.endsWith("rrddata") -> "Reading usage history"
        path.contains("tasks") && method == "DELETE" -> "Requesting task stop"
        path.endsWith("/log") -> "Reading task log"
        path.contains("tasks") -> "Reading task status"
        path.endsWith("/config") -> "Reading guest hardware"
        path.endsWith("{vmid}/status") -> "Reading guest status"
        path.endsWith("/storage") -> "Reading node storage"
        path.endsWith("/network") -> "Reading node network"
        path.endsWith("cluster-status") -> "Reading cluster health"
        path.endsWith("/remote-migrate") -> "Requesting cross-remote migration"
        path.endsWith("/migrate") && method == "GET" -> "Checking migration requirements"
        path.endsWith("/migrate") -> "Requesting guest migration"
        path.endsWith("/rollback") -> "Requesting snapshot rollback"
        path.contains("/snapshot") && method == "DELETE" -> "Requesting snapshot deletion"
        path.contains("/snapshot") && method == "POST" -> "Requesting snapshot creation"
        path.contains("/snapshot") -> "Reading guest snapshots"
        path.endsWith("/start") -> "Requesting guest start"
        path.endsWith("/shutdown") -> "Requesting guest shutdown"
        path.endsWith("/stop") -> "Requesting guest stop"
        path.endsWith("/resume") -> "Requesting guest resume"
        path == "access/permissions" -> "Checking account permissions"
        path.endsWith("/status") -> "Reading system status"
        path.endsWith("version") -> "Reading server version"
        path == "nodes" -> "Reading PDM host identity"
        else -> if (method == "GET") "Reading PDM data" else "Sending PDM request"
    }
    return ActivityRoute(method, "/$path", action)
}

/**
 * Opt-in, bounded, memory-only history. On while the Activity background or the LIVE panel is up.
 * Epochs prevent old requests repopulating a cleared session.
 */
class ApiActivityFeed {
    private val _events = MutableStateFlow<List<ApiActivity>>(emptyList())
    val events: StateFlow<List<ApiActivity>> = _events.asStateFlow()

    /**
     * The TLS version of the last live response (OkHttp's name, e.g. `TLSv1.3`), recorded whether
     * or not the feed is on. Only the version: no certificate, cipher or peer details.
     * Clearing advances an epoch captured when the call starts, so that call cannot put the badge back.
     */
    private val _tls = MutableStateFlow<String?>(null)
    val tls: StateFlow<String?> = _tls.asStateFlow()
    private var tlsEpoch = 0L

    /** Epoch to capture before a request starts. [noteTls] ignores a stale one. */
    @Synchronized
    fun currentTlsEpoch(): Long = tlsEpoch

    @Synchronized
    fun noteTls(version: String, observedEpoch: Long) {
        if (observedEpoch != tlsEpoch || version !in TLS_VERSION_NAMES) return
        _tls.value = version
    }

    @Synchronized
    fun clearTls() {
        tlsEpoch++
        _tls.value = null
    }
    private var enabled = false
    private var generation: Long? = null
    private var epoch = 0L
    private var sequence = 0L
    class Handle internal constructor(
        val id: Long,
        val epoch: Long,
        val startedNanos: Long,
        val startedAt: Long,
        val route: ActivityRoute,
    )

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        clear()
    }

    @Synchronized
    fun bindSession(value: Long) {
        if (generation == value) return
        val previous = generation
        clear()
        // A real session switch drops the previous badge. Turning observation back on clears
        // generation first, then rebinds the same session, and must keep the badge.
        if (previous != null) clearTls()
        generation = value
    }

    @Synchronized
    fun clear() {
        epoch++
        generation = null
        _events.value = emptyList()
    }

    @Synchronized
    fun begin(route: ActivityRoute, sessionGeneration: Long): Handle? {
        if (!enabled || generation != sessionGeneration) return null
        val id = ++sequence
        val startedAt = System.currentTimeMillis()
        val handle = Handle(id, epoch, System.nanoTime(), startedAt, route)
        // 80 so the LIVE panel's recent loads keep their requests; the background shows at most 6.
        // A row that falls off is still completed by [finish] from the handle.
        _events.value = (_events.value + ApiActivity(id, route, startedAt = startedAt)).takeLast(80)
        return handle
    }

    @Synchronized
    fun finish(handle: Handle, code: Int? = null, networkError: Boolean = false, cancelled: Boolean = false) {
        if (!enabled || handle.epoch != epoch) return
        val elapsed = ((System.nanoTime() - handle.startedNanos) / 1_000_000).coerceAtLeast(0)
        val completed = { row: ApiActivity ->
            row.copy(httpCode = code, elapsedMs = elapsed, networkError = networkError, cancelled = cancelled)
        }
        val rows = _events.value
        _events.value = if (rows.any { it.id == handle.id }) {
            rows.map { if (it.id == handle.id) completed(it) else it }
        } else {
            (rows + completed(ApiActivity(handle.id, handle.route, startedAt = handle.startedAt))).takeLast(80)
        }
    }

    /** The session ended before this call did. Drop the open row instead of leaving it in flight. */
    @Synchronized
    fun discard(handle: Handle) {
        if (handle.epoch != epoch) return
        _events.value = _events.value.filterNot { it.id == handle.id }
    }
}

/** Observes the existing call; never makes requests or consumes response bodies. */
internal class ApiActivityInterceptor(
    private val feed: ApiActivityFeed,
    private val generation: Long,
    private val current: () -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val route = activityRoute(request)
        val observedTlsEpoch = feed.currentTlsEpoch()
        val handle = if (route != null && current()) feed.begin(route, generation) else null
        try {
            val response = chain.proceed(request)
            if (handle != null) {
                if (current()) feed.finish(handle, code = response.code) else feed.discard(handle)
            }
            // Saved answers carry no handshake, so only a real exchange updates the version.
            response.handshake?.tlsVersion?.javaName?.let { version ->
                if (current()) feed.noteTls(version, observedTlsEpoch)
            }
            return response
        } catch (e: Exception) {
            // Anything thrown below must close the event, or it reads "IN FLIGHT" forever.
            if (handle != null) {
                if (current()) {
                    val cancelled = runCatching { chain.call().isCanceled() }.getOrDefault(false)
                    feed.finish(handle, networkError = !cancelled, cancelled = cancelled)
                } else {
                    feed.discard(handle)
                }
            }
            throw e
        }
    }
}
