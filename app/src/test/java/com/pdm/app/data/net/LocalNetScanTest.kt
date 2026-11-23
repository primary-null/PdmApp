package com.pdm.app.data.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetScanTest {
    @Test
    fun scanKeepsPdmAndSkipsHypervisor() = runBlocking {
        val net = object : LocalNet(null, null) {
            override fun isPrivateSubnet(ip: String): Boolean = true

            override fun generateSubnetIps(ip: String, prefixLength: Int): List<String> =
                listOf("192.0.2.10", "192.0.2.20", "192.0.2.30", "192.0.2.40")

            override suspend fun checkPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean =
                ip != "192.0.2.40"

            override suspend fun probePdm(host: String, port: Int, timeoutMs: Long, notes: ProbeNotes?): ProbeResult =
                when (host) {
                    "192.0.2.10" -> ProbeResult.Verified("PDM")
                    "192.0.2.20" -> ProbeResult.PdmDetected
                    "192.0.2.30" -> ProbeResult.NotPdm
                    else -> ProbeResult.Unreachable
                }
        }

        val final = net.scanSubnet(SubnetInfo("192.0.2.1", 24, "192.0.2.0/24"), port = 8443)
            .toList()
            .last()

        assertTrue(final.isFinished)
        assertEquals(4, final.scanned)
        assertEquals(listOf("192.0.2.10"), final.verified.map { it.ip })
        assertEquals(8443, final.verified.first().port)
        assertEquals(listOf("192.0.2.20"), final.pdmDetected.map { it.ip })
        assertTrue(final.unverified.isEmpty())
    }

    @Test
    fun logHasOnlyOpenPortsTlsAndVerdictsAndEndsWithDone() = runBlocking {
        val net = object : LocalNet(null, null) {
            override fun isPrivateSubnet(ip: String): Boolean = true

            override fun generateSubnetIps(ip: String, prefixLength: Int): List<String> =
                listOf("192.0.2.10", "192.0.2.20", "192.0.2.30", "192.0.2.40", "192.0.2.50")

            override suspend fun checkPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean =
                ip == "192.0.2.10" || ip == "192.0.2.30" || ip == "192.0.2.50"

            override suspend fun probePdm(host: String, port: Int, timeoutMs: Long, notes: ProbeNotes?): ProbeResult {
                if (host == "192.0.2.10") {
                    notes?.tls = "TLSv1.3"
                    notes?.pinned = true
                    return ProbeResult.Verified("PDM")
                }
                if (host == "192.0.2.50") notes?.tls = "TLSv1.3"
                return ProbeResult.Unreachable
            }
        }

        val final = net.scanSubnet(SubnetInfo("192.0.2.1", 24, "192.0.2.0/24"), port = 8443).toList().last()
        val lines = final.log.map { "${it.kind} ${it.target} ${it.text}" }

        assertEquals("net 192.0.2.0/24 this device 192.0.2.1 · 5 addresses · port 8443", lines.first())
        assertTrue(lines.contains("tls 192.0.2.10 TLS 1.3 · pinned"))
        assertTrue(lines.any { it.startsWith("pdm 192.0.2.10 verified") })
        assertTrue(lines.contains("tls 192.0.2.30 no answer in 3 s · retrying with 8 s"))
        assertTrue(lines.contains("? 192.0.2.30 port open · not confirmed"))
        // A TLS answer that is not recognisable is not a timeout.
        assertTrue(lines.contains("tls 192.0.2.50 unclear answer · checking again"))
        // Closed addresses are counted, never logged.
        assertTrue(lines.none { it.contains("192.0.2.20") || it.contains("192.0.2.40") })
        assertTrue(lines.last().startsWith("done 5 checked"))
        assertEquals(3, final.openCount)
        assertEquals(listOf("192.0.2.0/24"), final.networks)
        assertEquals("TLSv1.3", final.verified.single().tls)
        assertTrue(final.elapsedMs != null)
    }

    @Test
    fun probeBodyIsCappedWithoutASocket() {
        val huge = "x".repeat(80_000)
        val response = Response.Builder()
            .request(Request.Builder().url("https://192.0.2.10/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(huge.toResponseBody(null))
            .build()
        val text = boundedProbeBody(response)
        assertEquals(LocalNet.PROBE_BODY_CAP.toInt(), text?.length)
        assertEquals(null, boundedProbeBody(
            response.newBuilder().body(null).build(),
        ))
        assertNull(boundedProbeBody(response.newBuilder().body(null).build()))
    }

    @Test
    fun generateSubnetIpsCapsToSlash24() {
        val ips = LocalNet(null, null).generateSubnetIps("192.0.2.9", 16)
        assertEquals(254, ips.size)
        assertEquals("192.0.2.1", ips.first())
        assertEquals("192.0.2.254", ips.last())
    }
}
