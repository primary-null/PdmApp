package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.model.GuestStatusDto
import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.model.SnapshotDto
import com.pdm.app.data.model.canManageGuest
import com.pdm.app.data.model.isValidSnapshotName
import com.pdm.app.data.repo.FleetRepository
import com.pdm.app.data.session.SessionStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestOpsTest {
    @Test
    fun rootPermissionsOnSlashCoverEveryGuest() {
        // Synthetic fixture using the PDM 1.1.7 schema `access/permissions` for root@pam.
        val json = """{"data":{"/":{"Resource.Manage":true,"Resource.Audit":true},"/resource":{"Resource.Manage":true}}}"""
        val raw = AppJson.decodeFromString(
            PdmResponse.serializer(MapSerializer(String.serializer(), JsonObject.serializer())),
            json,
        ).data!!
        val perms = FleetMapper.permissionsFromApi(raw)
        assertTrue(perms.allows(Permissions.RESOURCE_MANAGE, Permissions.guestPath("NODE-B", 101)))
        assertFalse(perms.allows("Sys.Console", "/resource/NODE-B"))
    }

    @Test
    fun nonPropagatingPrivilegeStaysOnItsOwnPath() {
        val perms = Permissions(
            mapOf(
                "/resource/NODE-B" to mapOf(Permissions.RESOURCE_MANAGE to false),
                "/resource/NODE-C/guest/100" to mapOf(Permissions.RESOURCE_MANAGE to false),
            ),
        )
        assertTrue(perms.allows(Permissions.RESOURCE_MANAGE, "/resource/NODE-B"))
        assertFalse(perms.allows(Permissions.RESOURCE_MANAGE, Permissions.guestPath("NODE-B", 101)))
        assertTrue(perms.allows(Permissions.RESOURCE_MANAGE, Permissions.guestPath("NODE-C", 100)))
        assertFalse(perms.allows(Permissions.RESOURCE_MANAGE, Permissions.guestPath("NODE-C", 101)))
    }

    @Test
    fun unknownPermissionsKeepControlsVisible() {
        val none: Permissions? = null
        assertTrue(none.canManageGuest("NODE-B", 101))
    }

    @Test
    fun snapshotListDropsCurrentAndMarksItsParent() {
        // Synthetic fixture using the PDM 1.1.7 schema for a VM with one snapshot.
        val json = """{"data":[
            {"description":"","name":"fresh-install","snaptime":1700000000,"vmstate":false},
            {"description":"You are here!","name":"current","parent":"fresh-install"}
        ]}"""
        val list = AppJson.decodeFromString(PdmResponse.serializer(ListSerializer(SnapshotDto.serializer())), json).data!!
        val snaps = FleetMapper.snapshotsFromApi(list)
        assertEquals(listOf("fresh-install"), snaps.map { it.name })
        assertTrue(snaps.single().isCurrentBase)
        assertFalse(snaps.single().withRam)
    }

    @Test
    fun vmstateAcceptsIntegerForm() {
        val list = listOf(SnapshotDto(name = "a", vmstate = kotlinx.serialization.json.JsonPrimitive(1)))
        assertTrue(FleetMapper.snapshotsFromApi(list).single().withRam)
    }

    @Test
    fun snapshotNamesFollowPveRules() {
        assertTrue(isValidSnapshotName("before-upgrade_2"))
        assertFalse(isValidSnapshotName("2fast"))
        assertFalse(isValidSnapshotName("a"))
        assertFalse(isValidSnapshotName("has space"))
        assertFalse(isValidSnapshotName("current"))
        assertFalse(isValidSnapshotName("x".repeat(41)))
    }

    @Test
    fun pausedComesFromQmpStatus() {
        assertTrue(GuestStatusDto(status = "running", qmpstatus = "paused").isPaused)
        assertTrue(GuestStatusDto(status = "running", qmpstatus = "suspended").isPaused)
        assertFalse(GuestStatusDto(status = "running", qmpstatus = "running").isPaused)
    }

    @Test
    fun demoSnapshotRoundTrip() = runTest {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        store.setSession(SessionState(config = ServerConfig(host = "demo", username = "root@pam")))
        val api = DemoApi()
        val repo = FleetRepository(store, { api }, TaskPoller(maxAttempts = 2, intervalMs = 1L, wait = {}))
        val guest = repo.load().guests.first { it.type == "qemu" }

        repo.createSnapshot(guest, "before-test", "note", withRam = false)
        val after = repo.snapshots(guest)
        assertEquals(listOf("before-test"), after.map { it.name })
        assertTrue(after.single().isCurrentBase)

        repo.deleteSnapshot(guest, "before-test")
        assertTrue(repo.snapshots(guest).isEmpty())
    }
}
