package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.model.NetIfaceDto
import com.pdm.app.data.model.NodeInventory
import com.pdm.app.data.model.PdmResponse
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeInventoryTest {
    @Test
    fun bridgesGetRowsPlainPortsGetSummarised() {
        // Synthetic network fixture using the PDM 1.1.7 schema.
        val json = """{"data":[
          {"iface":"nic0","type":"eth","active":true,"method":"manual"},
          {"iface":"nic5","type":"eth","method":"manual"},
          {"iface":"vmbr1","type":"bridge","active":true,"cidr":"198.51.100.31/24","bridge_ports":"nic1","method":"static","autostart":true},
          {"iface":"nic6","type":"eth","method":"manual"},
          {"iface":"vmbr0","type":"bridge","active":true,"cidr":"192.0.2.31/24","gateway":"192.0.2.32","bridge_ports":"nic0","method":"static"}
        ]}"""
        val ifaces = AppJson.decodeFromString(PdmResponse.serializer(ListSerializer(NetIfaceDto.serializer())), json).data!!
        val inv = NodeInventory(storages = emptyList(), interfaces = ifaces)
        assertEquals(listOf("vmbr0", "vmbr1"), inv.logical.map { it.iface })
        assertEquals("nic0", inv.logical.first().bridgePorts)
        assertEquals(listOf("nic0", "nic5", "nic6"), inv.physical.map { it.iface })
    }
}
