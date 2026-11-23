package com.pdm.app.ui

import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.canManageGuest
import com.pdm.app.data.model.canManageRemote
import org.junit.Assert.assertEquals
import org.junit.Test

class WritableTest {
    private val root = Permissions(mapOf("/" to mapOf(Permissions.RESOURCE_MANAGE to true)))

    @Test
    fun offlineHidesWritesWhateverThePermissionsSay() {
        val offline = FleetUiState(offline = true, permissions = root)
        assertEquals(false, offline.writable.canManageGuest("NODE-C", 100))
        assertEquals(false, offline.writable.canManageRemote("NODE-C"))
    }

    @Test
    fun cachedDataInALiveSessionIsReadOnlyToo() {
        val unreachable = FleetUiState(permissions = root, sync = SyncState(cachedAt = 1L, failures = 1))
        assertEquals(false, unreachable.writable.canManageGuest("NODE-C", 100))
    }

    @Test
    fun restartingAndTimedOutHostsKeepCachedScreensReadOnly() {
        val restarting = FleetUiState(
            permissions = root,
            hostRestart = com.pdm.app.data.ops.RestartProgress("Overseer", com.pdm.app.data.ops.RestartPhase.DOWN, 1L),
        )
        assertEquals(true, restarting.readOnly)
        assertEquals(false, restarting.writable.canManageGuest("remote", 100))
        val timedOut = restarting.copy(hostRestart = restarting.hostRestart?.copy(phase = com.pdm.app.data.ops.RestartPhase.TIMED_OUT))
        assertEquals(true, timedOut.readOnly)
        assertEquals(false, timedOut.writable.canManageRemote("remote"))
        assertEquals(false, timedOut.copy(hostRestart = null).readOnly)
    }

    @Test
    fun liveFollowsThePermissions() {
        assertEquals(true, FleetUiState(permissions = root).writable.canManageGuest("NODE-C", 100))
        // Unknown permissions (read failed) still leave controls visible; PDM checks the call.
        assertEquals(true, FleetUiState(permissions = null).writable.canManageGuest("NODE-C", 100))
    }
}
