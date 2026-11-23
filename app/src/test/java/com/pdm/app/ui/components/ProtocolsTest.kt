package com.pdm.app.ui.components

import com.pdm.app.data.api.ActivityRoute
import com.pdm.app.data.api.ApiActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolsTest {
    private fun activity(code: Int? = null, networkError: Boolean = false, cancelled: Boolean = false) =
        ApiActivity(1, ActivityRoute("GET", "/x", "x"), httpCode = code, networkError = networkError, cancelled = cancelled)

    @Test
    fun tlsVersionsReadPlainlyAndOldOnesAreFlagged() {
        assertEquals(Protocol("TLS 1.3", ProtocolFamily.SECURE), Protocols.tls("TLSv1.3"))
        assertEquals(Protocol("TLS 1.2", ProtocolFamily.SECURE), Protocols.tls("TLSv1.2"))
        assertEquals(Protocol("TLS 1.1", ProtocolFamily.BROKEN), Protocols.tls("TLSv1.1"))
        assertEquals(Protocol("SSL 3.0", ProtocolFamily.BROKEN), Protocols.tls("SSLv3.0"))
    }

    @Test
    fun readsAreGreenAndChangesAreWrites() {
        assertEquals(ProtocolFamily.READ, Protocols.method("get").family)
        assertEquals("GET", Protocols.method("get").label)
        listOf("POST", "PUT", "DELETE").forEach { assertEquals(ProtocolFamily.WRITE, Protocols.method(it).family) }
    }

    @Test
    fun outcomesShowTheCodeOrWhatHappenedInstead() {
        assertEquals(Protocol("200", ProtocolFamily.READ), Protocols.outcome(activity(200)))
        assertEquals(Protocol("503", ProtocolFamily.BROKEN), Protocols.outcome(activity(503)))
        assertEquals(Protocol("401", ProtocolFamily.BROKEN), Protocols.outcome(activity(401)))
        assertEquals(ProtocolFamily.QUIET, Protocols.outcome(activity(304)).family)
        assertEquals(Protocol("NO RESPONSE", ProtocolFamily.BROKEN), Protocols.outcome(activity(networkError = true)))
        assertEquals(Protocol("CANCELLED", ProtocolFamily.QUIET), Protocols.outcome(activity(cancelled = true)))
        assertEquals(ProtocolFamily.QUIET, Protocols.outcome(activity()).family)
    }

    @Test
    fun onlyProtocolZonesAreTagged() {
        assertEquals("VXLAN", Protocols.zone("vxlan")?.label)
        assertEquals("EVPN", Protocols.zone("EVPN")?.label)
        assertEquals("VLAN", Protocols.zone("vlan")?.label)
        assertEquals("QinQ", Protocols.zone("qinq")?.label)
        assertNull(Protocols.zone("simple"))
        assertNull(Protocols.zone(null))
    }

    @Test
    fun vlanIdComesFromPveOrTheInterfaceName() {
        assertEquals("30", Protocols.vlanId("vmbr0.20", "30"))
        assertEquals("20", Protocols.vlanId("vmbr0.20", null))
        assertEquals("20", Protocols.vlanId("vlan20", ""))
        assertNull(Protocols.vlanId("vmbr0", null))
        assertEquals("VLAN 20", Protocols.vlan("20").label)
        assertEquals("VLAN", Protocols.vlan(null).label)
    }

    @Test
    fun labelsStayInsideTheBundledFontsAsciiSubset() {
        val labels = listOf(Protocols.HTTPS, Protocols.tls("TLSv1.3"), Protocols.ip(true), Protocols.ip(false)) +
            listOf("vxlan", "evpn", "vlan", "qinq").mapNotNull { Protocols.zone(it) } +
            listOf(activity(200), activity(networkError = true), activity(cancelled = true), activity()).map { Protocols.outcome(it) }
        labels.forEach { p -> assert(p.label.all { it.code in 0x20..0x7e }) { p.label } }
    }
}
