package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.model.MigratePreflightDto
import com.pdm.app.data.model.MigrateRequest
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.model.check
import com.pdm.app.data.repo.FleetRepository
import com.pdm.app.data.repo.PdmException
import com.pdm.app.data.session.SessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MigrateTest {
    @Test
    fun localIsoBlocksAndLocalDiskNeedsMoving() {
        // Captured from PDM 1.1.7 for demo-guest (DEMO-CLUSTER qemu 104 -> NODE-D).
        val json = """{"data":{"allowed_nodes":[],"has-dbus-vmstate":true,
            "local_disks":[
              {"cdrom":true,"is_unused":false,"size":5702520832,"volid":"iso-pool:iso/ubuntu-25.10-desktop-amd64.iso"},
              {"cdrom":false,"is_unused":false,"size":68719476736,"volid":"iso-pool:104/vm-104-disk-0.qcow2"}],
            "local_resources":[],"mapped-resource-info":{},"mapped-resources":[],"not_allowed_nodes":{},"running":true}}"""
        val pre = AppJson.decodeFromString(PdmResponse.serializer(MigratePreflightDto.serializer()), json).data!!
        val check = pre.check("NODE-D")
        assertEquals(listOf("iso-pool:104/vm-104-disk-0.qcow2"), check.localDisks)
        assertEquals(1, check.blockers.size)
        assertTrue(check.blockers.single().contains("ubuntu-25.10-desktop-amd64.iso"))
        // The ISO stops a cross-remote move too, not only this target node.
        assertEquals(check.blockers, check.guestBlockers)
    }

    @Test
    fun notAllowedNodeReasonIsShown() {
        val json = """{"data":{"local_disks":[],"local_resources":["usb0"],
            "not_allowed_nodes":{"NODE-E":{"unavailable_storages":"iso-pool"}}}}"""
        val check = AppJson.decodeFromString(PdmResponse.serializer(MigratePreflightDto.serializer()), json).data!!.check("NODE-E")
        assertEquals(2, check.blockers.size)
        assertTrue(check.blockers.any { it.contains("usb0") })
        assertTrue(check.blockers.any { it.contains("unavailable_storages: iso-pool") })
        assertEquals(1, check.guestBlockers.size)
    }

    @Test
    fun demoInClusterMoveReturnsTaskAndRequiresTarget() = runTest {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        store.setSession(SessionState(config = ServerConfig(host = "demo", username = "root@pam")))
        val repo = FleetRepository(store, { DemoApi() })
        val guest = repo.load().guests.first()

        val upid = repo.startMigration(guest, MigrateRequest(crossRemote = false, targetNode = "other"))
        assertTrue(upid.contains("qmigrate"))

        try {
            repo.startMigration(guest, MigrateRequest(crossRemote = true, targetRemote = "x"))
            fail("cross-remote without storage must not be sent")
        } catch (_: PdmException) {
        }
    }
}
