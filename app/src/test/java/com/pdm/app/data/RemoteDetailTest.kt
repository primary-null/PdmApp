package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.model.ClusterStatusDto
import com.pdm.app.data.model.FirewallRemoteDto
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.model.asFlag
import com.pdm.app.data.model.summary
import com.pdm.app.data.repo.FleetRepository
import com.pdm.app.data.session.SessionStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDetailTest {
    @Test
    fun clusterStatusSplitsHeaderAndNodes() {
        // Synthetic fixture using the PDM 1.1.7 schema for a 3-node cluster.
        val json = """{"data":[
            {"id":"cluster","name":"demo-cluster","nodes":3,"quorate":true,"type":"cluster","version":7},
            {"id":"node/NODE-D","ip":"192.0.2.33","level":"","local":false,"name":"NODE-D","nodeid":2,"online":true,"type":"node"},
            {"id":"node/NODE-A","ip":"192.0.2.34","level":"","local":true,"name":"NODE-A","nodeid":1,"online":true,"type":"node"}
        ]}"""
        val rows = AppJson.decodeFromString(PdmResponse.serializer(ListSerializer(ClusterStatusDto.serializer())), json).data!!
        assertEquals(true, rows.first { it.type == "cluster" }.quorate.asFlag())
        val nodes = FleetMapper.clusterNodes(rows)
        assertEquals(listOf("NODE-A", "NODE-D"), nodes.map { it.name })
        assertTrue(nodes.first().local)
        assertTrue(nodes.all { it.online })
    }

    @Test
    fun flagsAcceptZeroOne() {
        assertEquals(true, JsonPrimitive(1).asFlag())
        assertEquals(false, JsonPrimitive(0).asFlag())
        assertEquals(null, (null as JsonPrimitive?).asFlag())
    }

    @Test
    fun firewallSummaryCountsNodesGuestsAndRules() {
        val json = """{"data":[{"remote":"DEMO-CLUSTER","status":{"active":0,"all":0,"enabled":false},"nodes":[
            {"node":"NODE-A","status":{"active":0,"all":0,"enabled":true},"guests":[
                {"kind":"qemu","name":"demo-guest","vmid":104,"status":{"active":2,"all":3,"enabled":true}},
                {"kind":"lxc","name":"demo-media","vmid":112,"status":{"active":0,"all":0,"enabled":false}}]},
            {"node":"NODE-E","status":{"active":1,"all":1,"enabled":false},"guests":[]}
        ]}]}"""
        val fw = AppJson.decodeFromString(PdmResponse.serializer(ListSerializer(FirewallRemoteDto.serializer())), json).data!!.single()
        val s = fw.summary()
        assertFalse(s.clusterEnabled)
        assertEquals(1, s.nodesEnabled)
        assertEquals(2, s.nodes)
        assertEquals(1, s.guestsEnabled)
        assertEquals(2, s.guests)
        assertEquals(3, s.activeRules)
    }

    @Test
    fun demoRemoteDetailReadsEveryPart() = runTest {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        store.setSession(SessionState(config = ServerConfig(host = "demo", username = "root@pam")))
        val repo = FleetRepository(store, { DemoApi() })
        val remote = repo.load().remotes.first { it.kind == "pve" }
        val detail = repo.remoteDetail(remote)
        assertEquals(1, detail.nodes?.size)
        assertNotNull(detail.version)
        assertNotNull(detail.firewall)
        assertNotNull(detail.lastMetricCollection)
    }
}
