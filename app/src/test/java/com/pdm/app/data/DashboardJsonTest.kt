package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.model.PdmRemote
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.RemoteResourcesGroup
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.model.ResourcesStatus
import com.pdm.app.data.model.TopEntities
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardJsonTest {
    @Test
    fun resourcesStatusUsesSnakeCaseAndRemoteListKebab() {
        val json = """
            {"data":{
              "failed_remotes":0,
              "lxc":{"running":5,"stopped":2,"template":0,"unknown":0},
              "pve_cpu_stats":{"allocated":72.0,"max":52.0,"used":0.5},
              "pve_memory_stats":{"avail":1,"total":2,"used":1},
              "pve_nodes":{"offline":0,"online":5,"unknown":0},
              "pve_storage_stats":{"avail":1,"total":3,"used":2},
              "qemu":{"running":9,"stopped":2,"template":0,"unknown":0},
              "remote-list":[{"name":"NODE-C","status":"Good","ty":"pve"}],
              "remotes":3,
              "sdn_zones":{"available":6,"error":0,"pending":0,"unknown":0},
              "storages":{"available":16,"unknown":0},
              "pbs_nodes":{"offline":0,"online":0,"unknown":0},
              "pbs_cpu_stats":{"allocated":null,"max":0.0,"used":0.0},
              "pbs_memory_stats":{"avail":0,"total":0,"used":0},
              "pbs_storage_stats":{"avail":0,"total":0,"used":0},
              "pbs_datastores":{"online":0,"under_maintenance":null}
            }}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(PdmResponse.serializer(ResourcesStatus.serializer()), json)
        val status = parsed.data!!
        assertEquals(3, status.remotes)
        assertEquals(5, status.pveNodes.online)
        assertEquals(9, status.qemu.running)
        assertEquals(72.0, status.pveCpuStats.allocated!!, 0.0)
        assertEquals("NODE-C", status.remoteList.single().name)
        assertEquals("Good", status.remoteList.single().status)
    }

    @Test
    fun topEntitiesUsesKebabKeysAndNullableRrd() {
        val json = """
            {"data":{
              "guest-cpu":[{
                "remote":"NODE-C",
                "resource":{"type":"pve-qemu","name":"web","node":"pve","vmid":100,"cpu":0.2},
                "rrd-data":{"start":1,"resolution":60,"data":[0.1,null,0.2]}
              }],
              "node-cpu":[],
              "node-memory":[]
            }}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(PdmResponse.serializer(TopEntities.serializer()), json)
        val top = parsed.data!!
        assertEquals("web", top.guestCpu.single().resource.name)
        assertEquals(listOf(0.1, null, 0.2), top.guestCpu.single().rrdData.data)
        val boards = FleetMapper.leaderboards(top)
        assertEquals("web", boards.guestCpu.single().name)
        assertEquals(0.2, boards.guestCpu.single().last!!, 0.0)
        assertEquals("NODE-C/qemu/100", boards.guestCpu.single().entityId)
    }

    @Test
    fun remotesIgnoreTokenAndParseNodeStrings() {
        val json = """
            {"data":[{"id":"NODE-C","type":"pve","token":"secret-value",
              "nodes":["192.0.2.31:8006,fingerprint=AA:BB:CC"],
              "authid":"root@pam!example-token"}]}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(
            PdmResponse.serializer(ListSerializer(PdmRemote.serializer())),
            json,
        )
        val remote = parsed.data!!.single()
        assertEquals("NODE-C", remote.id)
        val endpoint = FleetMapper.parseRemoteNode(remote.nodeStrings.single())
        assertEquals("192.0.2.31", endpoint.host)
        assertEquals(8006, endpoint.port)
        assertEquals("AA:BB:CC", endpoint.fingerprint)
        val encoded = AppJson.encodeToString(ListSerializer(PdmRemote.serializer()), parsed.data!!)
        assertTrue(!encoded.contains("secret-value"))
        assertTrue(!encoded.contains("token"))
    }

    @Test
    fun remoteNodesAcceptKeyedObjectAndIpv6Forms() {
        val json = """
            {"data":[{"id":"r","type":"pve","nodes":[
              "hostname=node-a.example:8006,fingerprint=AA:BB",
              {"hostname":"198.51.100.35","fingerprint":"CC:DD"},
              "[2001:db8::5]:8006",
              "node-d.example"
            ]}]}
        """.trimIndent()
        val remote = AppJson.decodeFromString(
            PdmResponse.serializer(ListSerializer(PdmRemote.serializer())),
            json,
        ).data!!.single()
        val endpoints = FleetMapper.remotesFromApi(listOf(remote)).single().endpoints
        assertEquals("node-a.example", endpoints[0].host)
        assertEquals(8006, endpoints[0].port)
        assertEquals("AA:BB", endpoints[0].fingerprint)
        assertEquals("198.51.100.35", endpoints[1].host)
        assertEquals(null, endpoints[1].port)
        assertEquals("CC:DD", endpoints[1].fingerprint)
        assertEquals("2001:db8::5", endpoints[2].host)
        assertEquals(8006, endpoints[2].port)
        assertEquals("node-d.example", endpoints[3].host)
        assertEquals(null, endpoints[3].port)
    }

    @Test
    fun upidOnlyFromStringsOrUpidFields() {
        assertEquals("UPID:a", FleetMapper.asUpidString(kotlinx.serialization.json.JsonPrimitive("UPID:a")))
        assertEquals("", FleetMapper.asUpidString(AppJson.parseToJsonElement("""{"ok":true}""")))
        assertEquals("", FleetMapper.asUpidString(AppJson.parseToJsonElement("""[1,2]""")))
        assertEquals("", FleetMapper.asUpidString(null))
    }

    @Test
    fun subscriptionDecodesStateAndSkipsNone() {
        val json = """
            {"data":[
              {"remote":"NODE-C","state":"none"},
              {"remote":"backup","state":"active"}
            ]}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(
            PdmResponse.serializer(ListSerializer(RemoteSubscription.serializer())),
            json,
        )
        val rows = parsed.data!!
        assertEquals(2, rows.size)
        assertEquals("none", rows[0].state)
        assertTrue(!rows[0].isNotable())
        assertTrue(rows[1].isNotable())
    }

    @Test
    fun leaderEntityIdMatchesGuestAndNodeMapperIds() {
        val resourcesJson = """
            {"data":[
              {"remote":"NODE-B","resources":[
                {"name":"dns-service","node":"NODE-B","status":"stopped","type":"pve-lxc","vmid":104,"template":false},
                {"name":"demo-vm","node":"NODE-B","status":"running","type":"pve-qemu","vmid":101,"template":false},
                {"node":"NODE-B","status":"online","type":"pve-node"}
              ]},
              {"remote":"backup","resources":[
                {"name":"pbs-1","node":"pbs-1","status":"online","type":"pbs-node"}
              ]}
            ]}
        """.trimIndent()
        val groups = AppJson.decodeFromString(
            PdmResponse.serializer(ListSerializer(RemoteResourcesGroup.serializer())),
            resourcesJson,
        ).data!!
        val guests = FleetMapper.guestsFromResources(groups)
        val nodes = FleetMapper.nodesFromResources(groups)
        val topJson = """
            {"data":{
              "guest-cpu":[
                {"remote":"NODE-B","resource":{"type":"pve-lxc","name":"dns-service","node":"NODE-B","vmid":104},"rrd-data":{"start":1,"resolution":60,"data":[0.1]}},
                {"remote":"NODE-B","resource":{"type":"pve-qemu","name":"demo-vm","node":"NODE-B","vmid":101},"rrd-data":{"start":1,"resolution":60,"data":[0.2]}}
              ],
              "node-cpu":[
                {"remote":"NODE-B","resource":{"type":"pve-node","name":"NODE-B","node":"NODE-B"},"rrd-data":{"start":1,"resolution":60,"data":[0.3]}},
                {"remote":"backup","resource":{"type":"pbs-node","name":"pbs-1","node":"pbs-1"},"rrd-data":{"start":1,"resolution":60,"data":[0.1]}}
              ],
              "node-memory":[]
            }}
        """.trimIndent()
        val boards = FleetMapper.leaderboards(
            AppJson.decodeFromString(PdmResponse.serializer(TopEntities.serializer()), topJson).data,
        )
        // Ranked busiest-first: demo-vm (0.2) before dns-service (0.1).
        assertEquals(guests.first { it.vmid == 101 }.id, boards.guestCpu[0].entityId)
        assertEquals(guests.first { it.vmid == 104 }.id, boards.guestCpu[1].entityId)
        assertEquals(nodes.first { it.name == "NODE-B" }.id, boards.nodeCpu[0].entityId)
        assertEquals(nodes.first { it.name == "pbs-1" }.id, boards.nodeCpu[1].entityId)
        assertEquals("NODE-B/qemu/101", boards.guestCpu[0].entityId)
        assertEquals("NODE-B/lxc/104", boards.guestCpu[1].entityId)
        assertEquals("NODE-B/node/NODE-B", boards.nodeCpu[0].entityId)
        assertEquals("backup/node/pbs-1", boards.nodeCpu[1].entityId)
    }

    @Test
    fun leaderboardsKeepEveryTopEntityBusiestFirst() {
        val rows = (1..7).joinToString(",") { i ->
            """{"remote":"NODE-B","resource":{"type":"pve-qemu","name":"vm$i","node":"NODE-B","vmid":${100 + i}},"rrd-data":{"start":1,"resolution":60,"data":[0.$i]}}"""
        }
        val json = """{"data":{"guest-cpu":[$rows],"node-cpu":[],"node-memory":[]}}"""
        val boards = FleetMapper.leaderboards(
            AppJson.decodeFromString(PdmResponse.serializer(TopEntities.serializer()), json).data,
        )
        assertEquals(7, boards.guestCpu.size)
        // PDM sends these lowest-first; the app must rank them busiest-first.
        assertEquals("vm7", boards.guestCpu.first().name)
        assertEquals("vm1", boards.guestCpu.last().name)
        assertEquals("NODE-B/qemu/107", boards.guestCpu.first().entityId)
        assertEquals(0.7, boards.guestCpu.first().average!!, 1e-9)
    }

    @Test
    fun downsampleKeepsNullBucketsAndShortSeries() {
        assertEquals(listOf(1f, 2f), FleetMapper.downsample(listOf(1.0, 2.0), target = 72))
        val long = List(1440) { it.toDouble() }
        val down = FleetMapper.downsample(long, target = 72)
        assertEquals(72, down.size)
        assertEquals(listOf<Float?>(null, null), FleetMapper.downsample(listOf(null, null), 2))
    }
}
