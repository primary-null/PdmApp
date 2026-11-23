package com.pdm.app.data.api

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** CA pins: a publicly trusted server is bound to the authority that issued its certificate. */
class CaPinTest {
    private val host = "pve.example"
    private val authorityA = Authority("CN=Authority A")
    private val authorityB = Authority("CN=Authority B")
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply {
            init(
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("a", authorityA.cert)
                    setCertificateEntry("b", authorityB.cert)
                },
            )
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
    private val pinA = caPinOf(CertUtils.computeKeySha256(authorityA.cert))
    private val pinB = caPinOf(CertUtils.computeKeySha256(authorityB.cert))

    private fun manager(pin: String?, captured: MutableList<String>, seen: MutableList<String>) =
        TofuTrustManager(
            host,
            allowUnconfirmed = false,
            pinLookup = { pin },
            onCertCaptured = { _, fp -> captured += fp },
            defaultTrustManagerOverride = system,
            onAuthoritySeen = { seen += it },
        )

    @Test
    fun anchorIsTheAuthorityThatIssuedTheChain() {
        val leaf = authorityA.issue(host)
        assertEquals(pinA, trustAnchorPin(system, arrayOf(leaf)))
        assertEquals(pinA, trustAnchorPin(system, arrayOf(leaf, authorityA.cert)))
        assertNull(trustAnchorPin(system, arrayOf(Authority("CN=Stranger").issue(host))))
    }

    @Test
    fun unpinnedPublicCertificateReportsItsAuthorityWithoutCapture() {
        val captured = mutableListOf<String>()
        val seen = mutableListOf<String>()
        manager(null, captured, seen).checkServerTrusted(arrayOf(authorityA.issue(host)), "RSA")
        assertEquals(listOf(pinA), seen)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun renewalFromTheSameAuthorityIsAcceptedSilently() {
        val captured = mutableListOf<String>()
        val seen = mutableListOf<String>()
        val trust = manager(pinA, captured, seen)
        trust.checkServerTrusted(arrayOf(authorityA.issue(host)), "RSA")
        trust.checkServerTrusted(arrayOf(authorityA.issue(host)), "RSA")
        assertTrue(captured.isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun certificateFromAnotherAuthorityNeedsConfirmationOfTheNewAuthority() {
        val captured = mutableListOf<String>()
        assertThrows(CertificateException::class.java) {
            manager(pinA, captured, mutableListOf()).checkServerTrusted(arrayOf(authorityB.issue(host)), "RSA")
        }
        assertEquals(listOf(pinB), captured)
    }

    @Test
    fun selfSignedCertificateUnderAnAuthorityPinNeedsConfirmationOfThatCertificate() {
        val stranger = Authority("CN=$host").cert
        val captured = mutableListOf<String>()
        assertThrows(CertificateException::class.java) {
            manager(pinA, captured, mutableListOf()).checkServerTrusted(arrayOf(stranger), "RSA")
        }
        assertEquals(listOf(CertUtils.computeSha256Fingerprint(stranger)), captured)
    }

    @Test
    fun authorityPinDoesNotCoverAnotherHostName() {
        val captured = mutableListOf<String>()
        assertThrows(CertificateException::class.java) {
            manager(pinA, captured, mutableListOf())
                .checkServerTrusted(arrayOf(authorityA.issue("other.example")), "RSA")
        }
    }

    @Test
    fun decisionRulesForAuthorityPins() {
        fun decide(pin: String?, anchor: String, trusted: Boolean) =
            TofuDecision.evaluate(pin, "AA", anchorPin = { anchor }) { trusted }
        assertEquals(TofuVerdict.SystemTrusted(pinA), decide(null, pinA, trusted = true))
        assertEquals(TofuVerdict.Allow, decide(pinA, pinA, trusted = true))
        assertEquals(TofuVerdict.NeedsConfirmation(pinB), decide(pinA, pinB, trusted = true))
        assertEquals(TofuVerdict.NeedsConfirmation("AA"), decide(pinA, pinA, trusted = false))
        // A captured value equal to the saved authority pin is that pin, not a change.
        assertEquals(TofuVerdict.Allow, TofuDecision.evaluate(pinA, pinA) { false })
        // A certificate pin still rejects a different certificate, even a CA-valid one.
        assertTrue(TofuDecision.evaluate("AA:BB", "CC:DD", anchorPin = { pinA }) { true } is TofuVerdict.Reject)
    }

    private class Authority(subject: String) {
        val keys: KeyPair = newKeys()
        val name = X500Name(subject)
        val cert: X509Certificate = sign(name, keys, name, keys, ca = true, dns = subject.removePrefix("CN="))

        fun issue(dns: String): X509Certificate {
            return sign(X500Name("CN=$dns"), newKeys(), name, keys, ca = false, dns = dns)
        }

        companion object {
            private var serial = 1L

            fun newKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

            @Suppress("LongParameterList")
            fun sign(
                subject: X500Name,
                subjectKeys: KeyPair,
                issuer: X500Name,
                issuerKeys: KeyPair,
                ca: Boolean,
                dns: String,
            ): X509Certificate {
                val now = System.currentTimeMillis()
                val builder = JcaX509v3CertificateBuilder(
                    issuer, BigInteger.valueOf(serial++), Date(now - 60_000), Date(now + 86_400_000),
                    subject, subjectKeys.public,
                )
                builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
                builder.addExtension(
                    Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, dns)),
                )
                val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.private)
                return JcaX509CertificateConverter().getCertificate(builder.build(signer))
            }
        }
    }
}
