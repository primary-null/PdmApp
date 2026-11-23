package com.pdm.app.data.api

import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TlsHelpersTest {
    private fun fakeCert(derBytes: ByteArray): X509Certificate = object : X509Certificate() {
        override fun getEncoded(): ByteArray = derBytes
        override fun checkValidity() {}
        override fun checkValidity(date: Date) {}
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = X500Principal("CN=pdm-test")
        override fun getSubjectDN(): Principal = X500Principal("CN=pdm-test")
        override fun getNotBefore(): Date = Date(0)
        override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getSignature(): ByteArray = byteArrayOf(1, 2, 3)
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getExtendedKeyUsage(): List<String>? = null
        override fun getBasicConstraints(): Int = -1
        override fun getTBSCertificate(): ByteArray = derBytes
        override fun verify(key: PublicKey) {}
        override fun verify(key: PublicKey, sigProvider: String) {}
        override fun toString(): String = "fake-cert"
        override fun getPublicKey(): PublicKey = throw UnsupportedOperationException()
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): Set<String> = HashSet()
        override fun getNonCriticalExtensionOIDs(): Set<String> = HashSet()
        override fun getExtensionValue(oid: String): ByteArray? = null
    }

    @Test
    fun fingerprintIsStableColonSeparatedSha256() {
        val cert = fakeCert(ByteArray(32) { it.toByte() })
        val fp = CertUtils.computeSha256Fingerprint(cert)
        assertEquals(95, fp.length)
        assertEquals(fp, CertUtils.computeSha256Fingerprint(cert))
        val hexOnly = CertUtils.normalizeFingerprint(fp)
        assertEquals(64, hexOnly.length)
        assertTrue(hexOnly.all { it in '0'..'9' || it in 'A'..'F' })
    }

    @Test
    fun decisionAllowsUnpinnedCaValidCertificateWithoutPinning() {
        assertEquals(TofuVerdict.SystemTrusted(null), TofuDecision.evaluate(null, "AA:BB") { true })
    }

    @Test
    fun decisionAsksBeforeTrustingAnUnpinnedCertificateTheDeviceCannotVerify() {
        assertEquals(TofuVerdict.NeedsConfirmation("AA:BB"), TofuDecision.evaluate(null, "AA:BB") { false })
    }

    @Test
    fun decisionAllowsMatchingPinIgnoringFormatting() {
        assertEquals(TofuVerdict.Allow, TofuDecision.evaluate("aa:bb:cc", "AA BB CC") { false })
    }

    @Test
    fun decisionRejectsChangedCertificateEvenWhenCaValid() {
        val verdict = TofuDecision.evaluate("AA:BB", "CC:DD") { true }
        assertTrue(verdict is TofuVerdict.Reject)
        assertTrue((verdict as TofuVerdict.Reject).reason.contains("changed", ignoreCase = true))
    }

    @Test
    fun credentialClientsRefuseAnUnconfirmedCertificateAndCaptureIt() {
        val captured = mutableListOf<String>()
        val tm = TofuTrustManager(
            host = "pdm.example",
            allowUnconfirmed = false,
            pinLookup = { null },
            onCertCaptured = { _, fp -> captured += fp },
            defaultTrustManagerOverride = NothingTrusted,
        )
        val cert = fakeCert(byteArrayOf(1, 2, 3, 4))
        try {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
            fail("an unconfirmed certificate must be refused before credentials are sent")
        } catch (e: CertificateException) {
            assertTrue(e.message.orEmpty().contains("not trusted yet"))
        }
        assertEquals(listOf(CertUtils.computeSha256Fingerprint(cert)), captured)
    }

    @Test
    fun discoveryMayReadAnUnconfirmedCertificateButNeverAChangedPin() {
        val discovery = TofuTrustManager(
            host = "pdm.example",
            allowUnconfirmed = true,
            pinLookup = { null },
            defaultTrustManagerOverride = NothingTrusted,
        )
        discovery.checkServerTrusted(arrayOf(fakeCert(byteArrayOf(1, 2, 3, 4))), "RSA")
        val pinned = TofuTrustManager(
            host = "pdm.example",
            allowUnconfirmed = true,
            pinLookup = { CertUtils.computeSha256Fingerprint(fakeCert(byteArrayOf(9, 9))) },
            defaultTrustManagerOverride = NothingTrusted,
        )
        try {
            pinned.checkServerTrusted(arrayOf(fakeCert(byteArrayOf(1, 2, 3, 4))), "RSA")
            fail("a changed pin is refused even for discovery")
        } catch (e: CertificateException) {
            assertTrue(e.message.orEmpty().contains("changed", ignoreCase = true))
        }
    }

    @Test
    fun hostnameVerifierOnlyAcceptsTheConfiguredHost() {
        val verifier = createTofuHostnameVerifier("https://PDM.example:8443/")
        assertTrue(verifier.verify("pdm.example", null))
        assertTrue(!verifier.verify("other.example", null))
        assertEquals("fd00::1", bareHost("[fd00::1]:8443"))
        assertEquals("fd00::1", bareHost("fd00::1"))
        assertEquals("198.51.100.34", bareHost("198.51.100.34:8443"))
    }

    /** A device CA store that trusts nothing: every certificate here is "self-signed". */
    private object NothingTrusted : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("untrusted")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("untrusted")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
