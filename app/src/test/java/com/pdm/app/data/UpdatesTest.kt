package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.model.AptUpdateDto
import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.RemoteEndpoint
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.data.model.canManageNodeUpdates
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.UpdateSummaryDto
import com.pdm.app.data.model.rollup
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    // Synthetic fixture using the PDM 1.1.7 schema `remotes/updates/summary` with two example remotes.
    private val summaryJson = """
        {"data":{"remotes":{
          "DEMO-CLUSTER":{"remote-type":"pve","status":"success","nodes":{
            "NODE-A":{"last-refresh":1792735200,"number-of-updates":31,"repository-status":"ok","status":"success",
                    "versions":[{"package":"pve-manager","version":"9.2.20"}]},
            "NODE-D":{"last-refresh":1792735200,"number-of-updates":7,"repository-status":"ok","status":"success","versions":[]}
          }},
          "NODE-B":{"remote-type":"pve","status":"success","nodes":{
            "NODE-B":{"last-refresh":1792735200,"number-of-updates":3,"repository-status":"non-production-ready",
                    "status":"success","versions":[{"package":"pve-manager","version":"9.2.21"}]}
          }},
          "NODE-C":{"remote-type":"pve","status":"success","nodes":{
            "NODE-C":{"last-refresh":1792735200,"number-of-updates":0,"repository-status":"ok","status":"success","versions":[]}
          }}
        }}}
    """.trimIndent()

    @Test
    fun summaryFlattensBusiestFirstWithRepoWarnings() {
        val summary = AppJson.decodeFromString(PdmResponse.serializer(UpdateSummaryDto.serializer()), summaryJson).data!!
        val nodes = FleetMapper.updatesFromApi(summary)
        assertEquals(listOf("NODE-A", "NODE-D", "NODE-B", "NODE-C"), nodes.map { it.node })
        assertEquals("9.2.20", nodes[0].version)
        assertTrue(nodes[0].repoOk)
        assertFalse(nodes.first { it.node == "NODE-B" }.repoOk)
        assertNull(nodes[0].error)

        val roll = nodes.rollup()
        assertEquals(41, roll.pending)
        assertEquals(3, roll.nodesWithUpdates)
        assertEquals(1, roll.repoWarnings)
    }

    @Test
    fun errorNodeCarriesItsMessage() {
        val json = """{"data":{"remotes":{"r":{"status":"error","status-message":"connection refused","nodes":{
            "n":{"status":"error","number-of-updates":0}}}}}}"""
        val summary = AppJson.decodeFromString(PdmResponse.serializer(UpdateSummaryDto.serializer()), json).data!!
        val node = FleetMapper.updatesFromApi(summary).single()
        assertEquals("connection refused", node.error)
        assertEquals(1, listOf(node).rollup().errors)
    }

    @Test
    fun aptUpdateUsesDebianFieldNames() {
        val json = """{"data":[{"Arch":"amd64","Description":"x","OldVersion":"1:9.20.29-1~deb13u1","Origin":"Debian",
            "Package":"bind9-host","Priority":"standard","Section":"net","Title":"DNS lookup","Version":"1:9.20.30-1~deb13u1"}]}"""
        val pkg = AppJson.decodeFromString(PdmResponse.serializer(ListSerializer(AptUpdateDto.serializer())), json).data!!.single()
        assertEquals("bind9-host", pkg.name)
        assertEquals("1:9.20.29-1~deb13u1", pkg.oldVersion)
        assertEquals("1:9.20.30-1~deb13u1", pkg.version)
        assertEquals("Debian", pkg.origin)
    }

    @Test
    fun webUiPrefersTheNodesOwnEndpoint() {
        val remote = RemoteNode(
            id = "DEMO-CLUSTER", name = "DEMO-CLUSTER", kind = "pve",
            endpoints = listOf(RemoteEndpoint("node-a.example", 8006), RemoteEndpoint("NODE-D.example")),
        )
        assertEquals("https://NODE-D.example:8006/#v1:0:=node%2FNODE-D", remote.webUiUrl("NODE-D"))
        assertEquals("https://node-a.example:8006/#v1:0:=node%2Fnode-f", remote.webUiUrl("node-f"))
    }

    @Test
    fun webUiBracketsIpv6AndNeedsAnEndpoint() {
        val v6 = RemoteNode("r", "r", "pve", endpoints = listOf(RemoteEndpoint("fd00::10", 8443)))
        assertEquals("https://[fd00::10]:8443/#v1:0:=node%2Fn1", v6.webUiUrl("n1"))
        assertNull(RemoteNode("r", "r", "pve").webUiUrl("n1"))
    }

    @Test
    fun nodeUpdatesNeedModifyOnTheSystemPath() {
        val onNode = Permissions(mapOf("/resource/r/node/n1/system" to mapOf("Resource.Modify" to false)))
        assertTrue(onNode.canManageNodeUpdates("r", "n1"))
        assertFalse(onNode.canManageNodeUpdates("r", "n2"))
        val propagated = Permissions(mapOf("/resource" to mapOf("Resource.Modify" to true)))
        assertTrue(propagated.canManageNodeUpdates("r", "n2"))
        assertFalse(Permissions(mapOf("/resource" to mapOf("Resource.Audit" to true))).canManageNodeUpdates("r", "n1"))
        assertTrue((null as Permissions?).canManageNodeUpdates("r", "n1"))
    }
}
