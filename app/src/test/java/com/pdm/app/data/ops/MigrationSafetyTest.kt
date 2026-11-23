package com.pdm.app.data.ops

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.MigrateRequest
import com.pdm.app.data.model.PdmResource
import com.pdm.app.ui.MigrateView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q3: HA state in the node model and in choosing where a guest may go. */
class MigrationSafetyTest {
    private fun node(name: String, ha: String? = null, status: String = "online") =
        FleetNode(id = "r/$name", remoteId = "r", name = name, status = status, haState = ha)

    @Test
    fun realResourceJsonKeepsTheHaState() {
        val json = """{"type":"node","id":"remote/r/node/node-d","node":"node-d","status":"online","ha-state":"maintenance"}"""
        val res = AppJson.decodeFromString(PdmResource.serializer(), json)
        assertEquals("maintenance", res.haState)
        val old = AppJson.decodeFromString(PdmResource.serializer(), """{"type":"node","id":"remote/r/node/node-e","node":"node-e","status":"online"}""")
        assertNull("an older server simply doesn't report it", old.haState)
    }

    @Test
    fun aMaintenanceNodeIsNeverPreselected() {
        val c = MigrationSafety.inCluster(listOf(node("a", "maintenance"), node("b", "online"), node("src")), "r", "src")
        assertEquals("b", c.preselected)
        assertEquals(listOf("b", "a"), c.nodes)
        assertNull(c.note)
    }

    @Test
    fun whenEveryTargetIsUnsuitableNothingIsPickedAndThePersonIsTold() {
        val c = MigrationSafety.inCluster(listOf(node("a", "maintenance"), node("b", "fence"), node("src")), "r", "src")
        assertNull(c.preselected)
        assertEquals(listOf("a", "b"), c.nodes)
        assertNotNull(c.note)
    }

    @Test
    fun offlineNodesAndTheCurrentNodeAreNotTargets() {
        val c = MigrationSafety.inCluster(listOf(node("a", status = "offline"), node("src"), node("b")), "r", "src")
        assertEquals(listOf("b"), c.nodes)
    }

    @Test
    fun knownFaultStatesNeedASecondThoughtButAnUnknownFutureStateDoesNot() {
        assertNotNull(MigrationSafety.concern(node("a", "maintenance")))
        assertNotNull(MigrationSafety.concern(node("a", "fence")))
        assertNotNull(MigrationSafety.concern(node("a", "gone")))
        assertNotNull(MigrationSafety.concern(node("a", "unknown")))
        assertNull(MigrationSafety.concern(node("a", "online")))
        assertNull(MigrationSafety.concern(node("a", null)))
        assertNull("a state added in a later PDM is shown, not blocked", MigrationSafety.concern(node("a", "draining")))
    }

    @Test
    fun aConcernBlocksStartUntilConfirmed() {
        val base = MigrateView(guestId = "g", request = MigrateRequest(crossRemote = false, targetNode = "a"), nodes = listOf("a"), remotes = emptyList())
        assertTrue(base.canStart)
        val concerned = base.copy(haConcern = "a is in HA maintenance.")
        assertFalse(concerned.canStart)
        assertTrue(concerned.copy(haConfirmed = true).canStart)
    }

    @Test
    fun storagesAreReadFromASoundNodeOfTheTargetRemote() {
        val nodes = listOf(node("a", "maintenance"), node("b"), node("x").copy(remoteId = "other"))
        assertEquals("b", MigrationSafety.queryNode(nodes, "r")?.name)
        assertEquals("a", MigrationSafety.queryNode(listOf(node("a", "maintenance")), "r")?.name)
    }
}
