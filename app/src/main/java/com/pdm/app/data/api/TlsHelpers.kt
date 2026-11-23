package com.pdm.app.data.api

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.internal.tls.OkHostnameVerifier

// TLS trust decisions have no "trust self-signed" switch. The order is
// saved pin -> device CA store -> the user confirms, before any credential is sent.

object CertUtils {
    /** Colon-separated uppercase SHA-256 of the whole certificate. */
    fun computeSha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun normalizeFingerprint(fp: String): String =
        fp.replace(":", "").replace(" ", "").trim().uppercase()

    /** Colon-separated uppercase SHA-256 of a certificate's public key (SubjectPublicKeyInfo). */
    fun computeKeySha256(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}

/**
 * A saved pin is either a leaf certificate fingerprint (self-signed servers, such as PDM's own
 * default certificate) or, for servers with a publicly trusted certificate, [CA_PIN_PREFIX] plus
 * the key fingerprint of the trust anchor that issued it. A CA pin survives renewals from the
 * same authority.
 */
const val CA_PIN_PREFIX = "CA:"

fun isCaPin(pin: String?): Boolean = pin != null && pin.startsWith(CA_PIN_PREFIX)

fun caPinOf(anchorKeyFp: String): String = CA_PIN_PREFIX + anchorKeyFp

/** The fingerprint to show for [pin]: a CA pin's anchor key, otherwise the pin itself. */
fun pinDisplayFingerprint(pin: String): String = pin.removePrefix(CA_PIN_PREFIX)

/** Outcome of the pin decision; pure logic, unit-tested in TlsHelpersTest and CaPinTest. */
sealed interface TofuVerdict {
    /** Matches this endpoint's saved pin. */
    data object Allow : TofuVerdict

    /**
     * Unpinned and valid by the device's CA store and hostname rules. [caPin] identifies the
     * issuing trust anchor, when known, so the caller can remember the authority.
     */
    data class SystemTrusted(val caPin: String? = null) : TofuVerdict

    /** Needs the user's confirmation; [presentedFp] is the pin to save once confirmed. */
    data class NeedsConfirmation(val presentedFp: String) : TofuVerdict

    data class Reject(val reason: String) : TofuVerdict
}

object TofuDecision {
    /**
     * Decides how to handle a presented server certificate, in this order:
     * - certificate pin: matching -> allow; mismatching -> reject (MITM protection), even if CA-valid.
     * - CA pin: CA-valid under the same trust anchor -> allow, so renewals keep working; a different
     *   anchor, or a certificate that is no longer CA-valid -> the user confirms the new pin.
     * - no pin, valid by system CA and hostname rules -> allow; the caller may remember [anchorPin].
     * - no pin otherwise -> first use; the user confirms before pinning, for every new server.
     */
    fun evaluate(
        pinnedFp: String?,
        presentedFp: String,
        anchorPin: () -> String? = { null },
        systemTrusted: () -> Boolean,
    ): TofuVerdict {
        val pin = pinnedFp?.takeIf { it.isNotBlank() }
        return when {
            pin != null && CertUtils.normalizeFingerprint(presentedFp) == CertUtils.normalizeFingerprint(pin) ->
                TofuVerdict.Allow
            pin != null && isCaPin(pin) -> {
                val current = if (systemTrusted()) anchorPin() else null
                if (current != null && CertUtils.normalizeFingerprint(current) == CertUtils.normalizeFingerprint(pin)) {
                    TofuVerdict.Allow
                } else {
                    TofuVerdict.NeedsConfirmation(current ?: presentedFp)
                }
            }
            pin != null -> TofuVerdict.Reject(
                "Certificate changed for this host — possible MITM attack! " +
                    "(pinned: $pin, presented: $presentedFp)",
            )
            systemTrusted() -> TofuVerdict.SystemTrusted(anchorPin())
            else -> TofuVerdict.NeedsConfirmation(presentedFp)
        }
    }
}

/** The device CA store, loaded once per process rather than once per client. */
internal val platformTrustManager: X509TrustManager by lazy {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
}

/** `pdm.example`, `198.51.100.34` or `fd00::1` from whatever the user typed (scheme, port, path). */
internal fun bareHost(raw: String): String {
    val s = raw.trim().substringAfter("://").substringBefore('/')
    if (s.startsWith("[")) return s.substringAfter('[').substringBefore(']').lowercase()
    // A bare IPv6 address has several colons and no port.
    return (if (s.count { it == ':' } == 1) s.substringBefore(':') else s).lowercase()
}

/** True when [chain] is valid for [host] under [defaultTrustManager], including hostname rules. */
fun systemTrusts(
    defaultTrustManager: X509TrustManager,
    chain: Array<out X509Certificate>,
    authType: String?,
    host: String,
): Boolean = try {
    defaultTrustManager.checkServerTrusted(chain, authType)
    OkHostnameVerifier.verify(bareHost(host), chain[0])
} catch (_: CertificateException) {
    false
} catch (_: RuntimeException) {
    // Platform validators throw unchecked exceptions for malformed certificates.
    false
}

/**
 * The CA pin for a [chain] already validated by [trustManager]: the key of the first trust anchor
 * that is either in the chain or issued a certificate in it. Null when no anchor can be identified.
 */
fun trustAnchorPin(trustManager: X509TrustManager, chain: Array<out X509Certificate>): String? {
    val anchors = try {
        trustManager.acceptedIssuers
    } catch (_: RuntimeException) {
        emptyArray()
    }
    val anchor = chain.firstNotNullOfOrNull { cert ->
        anchors.firstOrNull { it == cert }
            ?: anchors.firstOrNull { it.subjectX500Principal == cert.issuerX500Principal && signedBy(cert, it) }
    }
    return anchor?.let { caPinOf(CertUtils.computeKeySha256(it)) }
}

private fun signedBy(cert: X509Certificate, issuer: X509Certificate): Boolean = try {
    cert.verify(issuer.publicKey)
    true
} catch (_: java.security.GeneralSecurityException) {
    false
} catch (_: RuntimeException) {
    false
}

/**
 * Accepts a pinned match, or a CA-valid certificate for an unpinned endpoint; rejects a changed
 * pin. An unpinned certificate the device cannot verify is captured via [onCertCaptured] and only
 * let through when [allowUnconfirmed] is set: discovery and the public realm list, which never
 * carry credentials. Every client that can carry a password or token refuses it during the
 * handshake, before a byte of HTTP is sent, so the caller can ask the user and retry.
 * A CA-valid certificate for an unpinned endpoint reports its authority to [onAuthoritySeen].
 */
class TofuTrustManager(
    val host: String,
    val allowUnconfirmed: Boolean,
    private val pinLookup: () -> String?,
    private val onCertCaptured: ((X509Certificate, String) -> Unit)? = null,
    private val defaultTrustManagerOverride: X509TrustManager? = null,
    private val onAuthoritySeen: ((String) -> Unit)? = null,
) : X509TrustManager {

    private val defaultTrustManager: X509TrustManager
        get() = defaultTrustManagerOverride ?: platformTrustManager

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        defaultTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty server certificate chain for $host")
        }
        val serverCert = chain[0]
        val presentedFp = CertUtils.computeSha256Fingerprint(serverCert)
        val pin = pinLookup()
        val verdict = TofuDecision.evaluate(
            pin,
            presentedFp,
            anchorPin = { trustAnchorPin(defaultTrustManager, chain) },
        ) {
            systemTrusts(defaultTrustManager, chain, authType, host)
        }
        when (verdict) {
            is TofuVerdict.SystemTrusted -> verdict.caPin?.let { onAuthoritySeen?.invoke(it) }
            is TofuVerdict.NeedsConfirmation -> onCertCaptured?.invoke(serverCert, verdict.presentedFp)
            // Under a CA pin, a capture always means the authority changed and needs confirmation.
            TofuVerdict.Allow -> if (!isCaPin(pin)) onCertCaptured?.invoke(serverCert, presentedFp)
            is TofuVerdict.Reject -> onCertCaptured?.invoke(serverCert, presentedFp)
        }
        when (verdict) {
            TofuVerdict.Allow, is TofuVerdict.SystemTrusted -> Unit
            is TofuVerdict.NeedsConfirmation -> if (!allowUnconfirmed) {
                throw CertificateException("Certificate for $host is not trusted yet; sign in again to confirm it")
            }
            is TofuVerdict.Reject -> throw CertificateException(verdict.reason)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrustManager.acceptedIssuers
}

fun createTofuSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    return sslContext.socketFactory
}

/**
 * Restricts connections to the configured host. Certificate identity, including CA hostname
 * rules, is enforced by [TofuTrustManager]; a pinned self-signed certificate is bound to its
 * endpoint by the pin instead of its subject names.
 */
fun createTofuHostnameVerifier(host: String): HostnameVerifier {
    val expected = bareHost(host)
    return HostnameVerifier { hostname, _ -> bareHost(hostname) == expected }
}
