package com.pdm.app.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pdm.app.data.api.PdmAuth
import com.pdm.app.data.api.TofuTrustManager
import com.pdm.app.data.api.createTofuHostnameVerifier
import com.pdm.app.data.api.createTofuSslSocketFactory
import com.pdm.app.data.session.SessionStore
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class SubnetInfo(
    val ip: String,
    val prefixLength: Int,
    val networkCidr: String,
)

data class DiscoveredHost(
    val ip: String,
    val port: Int = PdmAuth.DEFAULT_PORT,
    val version: String,
    val latencyMs: Long = 0L,
    val authRequired: Boolean = false,
    /** TLS version the probe negotiated (OkHttp's name, e.g. `TLSv1.3`), when it got that far. */
    val tls: String? = null,
)

/** How a scan log line reads: plain step, PDM found, PDM needing sign-in, or nothing useful. */
enum class ScanTone { PLAIN, GOOD, WARN, QUIET }

/**
 * One line of the scan's log: a step that mattered (a subnet swept, a port open, the TLS
 * answer, the verdict). Closed addresses never get a line; they are only counted.
 */
data class ScanStep(
    val at: Long,
    val kind: String,
    val target: String,
    val text: String,
    val tone: ScanTone = ScanTone.PLAIN,
)

/** What a probe saw on the way to its verdict, for the scan log. Filled in by [LocalNet.probePdm]. */
class ProbeNotes {
    @Volatile var tls: String? = null
    @Volatile var pinned: Boolean = false
}

data class ScanProgress(
    val scanned: Int,
    val total: Int,
    val currentIp: String,
    val verified: List<DiscoveredHost>,
    val pdmDetected: List<DiscoveredHost> = emptyList(),
    val unverified: List<String> = emptyList(),
    val isFinished: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null,
    /** The log so far, oldest first, at most [LocalNet.MAX_LOG] lines. */
    val log: List<ScanStep> = emptyList(),
    /** Addresses whose port answered at all. */
    val openCount: Int = 0,
    /** Swept networks, e.g. `192.0.2.0/24`. */
    val networks: List<String> = emptyList(),
    /** Set once finished. */
    val elapsedMs: Long? = null,
)

/**
 * LAN sweep for Proxmox Datacenter Manager on 8443. Does not probe PVE 8006.
 */
open class LocalNet(
    private val context: Context?,
    private val sessionStore: SessionStore?,
) {
    @Suppress("DEPRECATION")
    open fun getScannableSubnets(): List<SubnetInfo> {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val subnets = mutableListOf<SubnetInfo>()
        val seenCidrs = mutableSetOf<String>()
        cm.allNetworks.forEach { network ->
            val lp = cm.getLinkProperties(network) ?: return@forEach
            for (linkAddress in lp.linkAddresses) {
                val address = linkAddress.address
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    val ipStr = address.hostAddress ?: continue
                    val prefix = linkAddress.prefixLength.coerceIn(1, 32)
                    val ipLong = parseIpv4(ipStr) ?: continue
                    val effectivePrefix = if (prefix < 24) 24 else prefix
                    val mask = if (effectivePrefix == 0) {
                        0L
                    } else {
                        (0xFFFFFFFFL shl (32 - effectivePrefix)) and 0xFFFFFFFFL
                    }
                    val networkIp = ipLong and mask
                    val cidr = "${formatIpv4(networkIp)}/$effectivePrefix"
                    if (seenCidrs.add(cidr)) {
                        subnets.add(SubnetInfo(ip = ipStr, prefixLength = effectivePrefix, networkCidr = cidr))
                    }
                }
            }
        }
        return subnets
    }

    open fun isPrivateSubnet(ip: String): Boolean {
        val parts = ip.trim().split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val b0 = parts[0]
        val b1 = parts[1]
        return when {
            b0 == 10 -> true
            b0 == 172 && b1 in 16..31 -> true
            b0 == 192 && b1 == 168 -> true
            b0 == 127 -> true
            else -> false
        }
    }

    fun isTailscaleSubnet(ip: String): Boolean {
        val ipLong = parseIpv4(ip) ?: return false
        val tailscaleNet = parseIpv4("100.64.0.0") ?: return false
        val mask = (0xFFFFFFFFL shl (32 - 10)) and 0xFFFFFFFFL
        return (ipLong and mask) == (tailscaleNet and mask)
    }

    open fun generateSubnetIps(ip: String, prefixLength: Int): List<String> {
        val ipLong = parseIpv4(ip) ?: return emptyList()
        val effectivePrefix = if (prefixLength < 24) 24 else prefixLength.coerceAtMost(30)
        val mask = (0xFFFFFFFFL shl (32 - effectivePrefix)) and 0xFFFFFFFFL
        val network = ipLong and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        val ips = mutableListOf<String>()
        var curr = network + 1
        while (curr < broadcast) {
            ips.add(formatIpv4(curr))
            curr++
        }
        return ips
    }

    open suspend fun checkPortOpen(
        ip: String,
        port: Int = PdmAuth.DEFAULT_PORT,
        timeoutMs: Int = 400,
    ): Boolean = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    open suspend fun probePdm(
        host: String,
        port: Int = PdmAuth.DEFAULT_PORT,
        timeoutMs: Long = 10000L,
        notes: ProbeNotes? = null,
    ): ProbeResult = withContext(Dispatchers.IO) {
        if (host.equals("demo", ignoreCase = true)) {
            return@withContext ProbeResult.Verified("0.1-demo")
        }
        val store = sessionStore ?: return@withContext ProbeResult.Unreachable
        notes?.pinned = store.getEndpointPin(host, port) != null
        var client: OkHttpClient? = null
        try {
            // Discovery never sends a credential, so an unconfirmed certificate may answer;
            // a pinned endpoint must still match its pin.
            val trustManager = TofuTrustManager(
                host = host,
                allowUnconfirmed = true,
                pinLookup = { store.getEndpointPin(host, port) },
            )
            val probeClient = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .sslSocketFactory(createTofuSslSocketFactory(trustManager), trustManager)
                .hostnameVerifier(createTofuHostnameVerifier(host))
                .build()
            client = probeClient

            val html = getBody(probeClient, "https://$host:$port/")
            val ping = getBody(probeClient, "https://$host:$port/api2/json/ping")
            notes?.tls = html.tls ?: ping.tls
            val remotesCode = if (PdmFingerprint.pingIsPong(ping.body) &&
                !PdmFingerprint.isPdmSurface(html.body.orEmpty())
            ) {
                getStatus(probeClient, "https://$host:$port/api2/json/remotes/remote")
            } else {
                null
            }
            PdmFingerprint.classify(
                serverHeader = html.server ?: ping.server,
                html = html.body,
                pingBody = ping.body,
                remotesCode = remotesCode,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ProbeResult.Unreachable
        } finally {
            client?.let { shutdownProbeClient(it) }
        }
    }

    fun scanSubnet(
        subnet: SubnetInfo? = null,
        port: Int = PdmAuth.DEFAULT_PORT,
        concurrency: Int = 32,
    ): Flow<ScanProgress> = channelFlow {
        val targets = if (subnet != null) listOf(subnet) else getScannableSubnets()
        if (targets.isEmpty()) {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isCellular = cm?.activeNetwork?.let {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } ?: false
            send(
                ScanProgress(
                    scanned = 0,
                    total = 0,
                    currentIp = "",
                    verified = emptyList(),
                    isFinished = true,
                    error = if (isCellular) {
                        "On cellular data — no local network to scan."
                    } else {
                        "No scannable local network found."
                    },
                ),
            )
            return@channelFlow
        }

        val scannable = targets.filter { isPrivateSubnet(it.ip) }
        val tailscale = targets.filter { isTailscaleSubnet(it.ip) }
        val infoMsg = if (tailscale.isNotEmpty()) {
            "Tailscale/VPN (100.64.x) — not swept. Add the host manually."
        } else {
            null
        }
        if (scannable.isEmpty()) {
            send(
                ScanProgress(
                    scanned = 0,
                    total = 0,
                    currentIp = "",
                    verified = emptyList(),
                    isFinished = true,
                    error = infoMsg ?: "No scannable private network found.",
                ),
            )
            return@channelFlow
        }

        val allIps = scannable.flatMap { generateSubnetIps(it.ip, it.prefixLength) }
        val total = allIps.size
        val networks = scannable.map { it.networkCidr }
        val started = System.nanoTime()
        val scanned = AtomicInteger(0)
        val open = AtomicInteger(0)
        val log = ArrayList<ScanStep>()
        val logMutex = Mutex()
        val verifiedList = mutableListOf<DiscoveredHost>()
        val detectedList = mutableListOf<DiscoveredHost>()
        val unverifiedList = mutableListOf<String>()
        val verifiedMutex = Mutex()
        val detectedMutex = Mutex()
        val unverifiedMutex = Mutex()
        val semaphore = Semaphore(concurrency)

        suspend fun snapshot(currentIp: String, finished: Boolean = false) = ScanProgress(
            scanned = if (finished) total else scanned.get(),
            total = total,
            currentIp = currentIp,
            verified = verifiedMutex.withLock { verifiedList.toList() },
            pdmDetected = detectedMutex.withLock { detectedList.toList() },
            unverified = unverifiedMutex.withLock { unverifiedList.toList() },
            isFinished = finished,
            infoMessage = infoMsg,
            log = logMutex.withLock { log.toList() },
            openCount = open.get(),
            networks = networks,
            elapsedMs = if (finished) (System.nanoTime() - started) / 1_000_000 else null,
        )

        suspend fun note(kind: String, target: String, text: String, tone: ScanTone = ScanTone.PLAIN) {
            logMutex.withLock {
                log += ScanStep(System.currentTimeMillis(), kind, target, text, tone)
                if (log.size > MAX_LOG) log.removeAt(0)
            }
        }

        scannable.forEach { subnet ->
            note("net", subnet.networkCidr, "this device ${subnet.ip} · ${generateSubnetIps(subnet.ip, subnet.prefixLength).size} addresses · port $port")
        }
        tailscale.forEach { note("skip", it.networkCidr, "VPN range, not swept", ScanTone.QUIET) }
        send(snapshot(allIps.firstOrNull().orEmpty()))

        coroutineScope {
            allIps.forEach { ip ->
                launch {
                    semaphore.withPermit {
                        val isOpen = checkPortOpen(ip, port, timeoutMs = 400)
                        if (isOpen) {
                            open.incrementAndGet()
                            note("tcp", ip, "$port open")
                            send(snapshot(ip))
                            val probeStart = System.nanoTime()
                            val notes = ProbeNotes()
                            // The port already answered; a slow TLS handshake gets one longer retry.
                            var result = probePdm(ip, port, timeoutMs = 3_000L, notes = notes)
                            if (result is ProbeResult.Unreachable) {
                                // Unreachable is also "answered, but nothing recognisable"; say which.
                                note(
                                    "tls",
                                    ip,
                                    if (notes.tls == null) "no answer in 3 s · retrying with 8 s" else "unclear answer · checking again",
                                )
                                send(snapshot(ip))
                                result = probePdm(ip, port, timeoutMs = 8_000L, notes = notes)
                            }
                            val latency = (System.nanoTime() - probeStart) / 1_000_000
                            notes.tls?.let { tls ->
                                note("tls", ip, tlsName(tls) + if (notes.pinned) " · pinned" else " · not pinned")
                            }
                            when (result) {
                                is ProbeResult.Verified -> {
                                    verifiedMutex.withLock {
                                        verifiedList.add(
                                            DiscoveredHost(
                                                ip = ip,
                                                port = port,
                                                version = result.version,
                                                latencyMs = latency,
                                                tls = notes.tls,
                                            ),
                                        )
                                    }
                                    note("pdm", ip, "verified · $latency ms", ScanTone.GOOD)
                                }
                                is ProbeResult.PdmDetected -> {
                                    detectedMutex.withLock {
                                        detectedList.add(
                                            DiscoveredHost(
                                                ip = ip,
                                                port = port,
                                                version = "login to confirm",
                                                latencyMs = latency,
                                                authRequired = true,
                                                tls = notes.tls,
                                            ),
                                        )
                                    }
                                    note("pdm", ip, "sign-in needed · $latency ms", ScanTone.WARN)
                                }
                                is ProbeResult.NotPdm -> note("skip", ip, "not PDM", ScanTone.QUIET)
                                is ProbeResult.Unreachable -> {
                                    unverifiedMutex.withLock {
                                        if (unverifiedList.size < 32) unverifiedList.add(ip)
                                    }
                                    note("?", ip, "port open · not confirmed", ScanTone.QUIET)
                                }
                            }
                        }
                        scanned.incrementAndGet()
                        send(snapshot(ip))
                    }
                }
            }
        }

        val pdm = verifiedMutex.withLock { verifiedList.size } + detectedMutex.withLock { detectedList.size }
        val seconds = (System.nanoTime() - started) / 100_000_000 / 10.0
        note("done", "$total checked", "$seconds s · ${open.get()} open · $pdm PDM")
        send(snapshot("", finished = true))
    }.flowOn(Dispatchers.IO)

    private data class HttpPeek(
        val reached: Boolean,
        val status: Int? = null,
        val server: String? = null,
        val body: String? = null,
        val tls: String? = null,
    )

    private fun getBody(client: OkHttpClient, url: String): HttpPeek {
        val request = Request.Builder().url(url).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (_: IOException) {
            return HttpPeek(reached = false)
        }
        response.use { res ->
            val body = try {
                boundedProbeBody(res)
            } catch (_: Exception) {
                null
            }
            return HttpPeek(
                reached = true,
                status = res.code,
                server = res.header("Server"),
                body = body,
                tls = res.handshake?.tlsVersion?.javaName,
            )
        }
    }

    private fun shutdownProbeClient(client: OkHttpClient) {
        try {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun getStatus(client: OkHttpClient, url: String): Int? {
        val request = Request.Builder().url(url).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (_: IOException) {
            return null
        }
        response.use { return it.code }
    }

    companion object {
        /** LAN discovery reads at most this much of an unauthenticated body. */
        const val PROBE_BODY_CAP = 65_536L

        /** The scan log keeps this many lines; a sweep only logs open ports, so this is generous. */
        const val MAX_LOG = 120

        /** OkHttp's `TLSv1.3` as `TLS 1.3`. */
        fun tlsName(javaName: String): String = javaName.replace("TLSv", "TLS ").replace("SSLv", "SSL ")

        fun parseIpv4(ip: String): Long? {
            val parts = ip.trim().split('.').mapNotNull { it.toLongOrNull() }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return null
            return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        }

        fun formatIpv4(ipLong: Long): String {
            val b0 = (ipLong ushr 24) and 0xFF
            val b1 = (ipLong ushr 16) and 0xFF
            val b2 = (ipLong ushr 8) and 0xFF
            val b3 = ipLong and 0xFF
            return "$b0.$b1.$b2.$b3"
        }
    }
}

/** Capped peek of a probe response. Null when the response has no body. */
internal fun boundedProbeBody(response: okhttp3.Response, limit: Long = LocalNet.PROBE_BODY_CAP): String? {
    if (response.body == null) return null
    return response.peekBody(limit).string()
}
