package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.RemoteResourcesGroup
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdmResourceJsonTest {
    @Test
    fun resourcesListAcceptsTagsArrayAndTemplateBool() {
        val json = """
            {"data":[{"remote":"NODE-C","resources":[{
              "cpu":0.1,"id":"remote/NODE-C/guest/100","maxcpu":8,
              "name":"web","node":"pve","status":"running",
              "tags":["infra"],"template":false,"type":"qemu","vmid":100
            }]}]}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(
            PdmResponse.serializer(ListSerializer(RemoteResourcesGroup.serializer())),
            json,
        )
        val guest = parsed.data!!.single().resources.single()
        assertEquals("qemu", guest.type)
        assertEquals(100L, guest.vmid)
        assertEquals("running", guest.status)
        assertFalse(guest.isTemplate)
    }

    @Test
    fun resourcesListAcceptsTagsString() {
        val json = """
            {"data":[{"remote":"edge-a","resources":[{
              "id":"qemu/101","name":"db","status":"stopped",
              "tags":"prod;linux","template":0,"type":"qemu","vmid":101
            }]}]}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(
            PdmResponse.serializer(ListSerializer(RemoteResourcesGroup.serializer())),
            json,
        )
        val guest = parsed.data!!.single().resources.single()
        assertEquals(101L, guest.vmid)
        assertFalse(guest.isTemplate)
    }

    @Test
    fun resourcesListAcceptsPvePrefixedTypesAndUsage() {
        val json = """
            {"data":[{"remote":"NODE-C","resources":[
              {"cpu":0.1,"id":"remote/NODE-C/guest/100","maxcpu":8,"mem":1024,"maxmem":2048,
               "name":"web","node":"pve","status":"running","type":"pve-qemu","vmid":100,"template":false},
              {"cpu":0.2,"id":"node/pve","maxcpu":16,"mem":4096,"maxmem":8192,
               "node":"pve","status":"online","type":"pve-node"}
            ]}]}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(
            PdmResponse.serializer(ListSerializer(RemoteResourcesGroup.serializer())),
            json,
        )
        val resources = parsed.data!!.single().resources
        val guest = resources[0]
        val node = resources[1]
        assertEquals("qemu", guest.kind)
        assertTrue(guest.isGuest)
        assertEquals(0.1, guest.cpu!!, 0.0)
        assertEquals("pve", guest.node)
        assertTrue(node.isNode)
        assertEquals("node", node.kind)
        val guests = FleetMapper.guestsFromResources(parsed.data!!)
        val nodes = FleetMapper.nodesFromResources(parsed.data!!)
        assertEquals(1, guests.size)
        assertEquals("pve", guests.single().node)
        assertEquals(1, nodes.size)
        assertEquals("pve", nodes.single().name)
    }
}
