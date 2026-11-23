package com.pdm.app.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdmFingerprintTest {
    @Test
    fun pingRecognizesWrappedPong() {
        assertTrue(PdmFingerprint.pingIsPong("""{"data":"pong"}"""))
        assertTrue(PdmFingerprint.pingIsPong("pong"))
        assertFalse(PdmFingerprint.pingIsPong("""{"data":"ok"}"""))
        assertFalse(PdmFingerprint.pingIsPong(null))
    }

    @Test
    fun pdmHtmlIsVerified() {
        val html = "<title>Proxmox Datacenter Manager</title>"
        assertEquals(ProbeResult.Verified("PDM"), PdmFingerprint.classify(html = html))
    }

    @Test
    fun pveHtmlIsNotPdmEvenOn8443() {
        val html = "<title>Proxmox Virtual Environment</title><script src=\"pvemanagerlib.js\"></script>"
        assertEquals(ProbeResult.NotPdm, PdmFingerprint.classify(html = html, pingBody = """{"data":"pong"}"""))
        assertTrue(PdmFingerprint.isPveSurface("Server: pve-api-daemon/8.3"))
    }

    @Test
    fun pbsHtmlIsNotPdm() {
        assertEquals(
            ProbeResult.NotPdm,
            PdmFingerprint.classify(html = "<title>Proxmox Backup Server</title>"),
        )
    }

    @Test
    fun pingPongWithRemotesUnauthorizedIsPdmDetected() {
        assertEquals(
            ProbeResult.PdmDetected,
            PdmFingerprint.classify(pingBody = """{"data":"pong"}""", remotesCode = 401),
        )
    }

    @Test
    fun pingPongWithMissingRemotesEndpointIsNotPdm() {
        assertEquals(
            ProbeResult.NotPdm,
            PdmFingerprint.classify(pingBody = """{"data":"pong"}""", remotesCode = 404),
        )
    }
}
