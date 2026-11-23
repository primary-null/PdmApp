package com.pdm.app.ui.home

import com.pdm.app.data.ops.RestartPhase
import com.pdm.app.data.ops.RestartProgress
import com.pdm.app.ui.Freshness
import com.pdm.app.ui.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncChipTest {
    private val wall = 1_700_000_000_000L

    @Test
    fun backgroundLoadsDoNotChangeTheWords() {
        val live = SyncState(lastSuccessAt = 0, lastAttemptAt = 0)
        assertEquals("LIVE", syncLabel(live, now = 10_000, wallNow = wall).text)
        assertEquals("LIVE", syncLabel(live.copy(running = true), now = 10_000, wallNow = wall).text)
    }

    @Test
    fun pulledLoadSaysSyncing() {
        val label = syncLabel(SyncState(lastSuccessAt = 0, running = true, manual = true), now = 10_000, wallNow = wall)
        assertEquals("SYNCING", label.text)
    }

    @Test
    fun oldLiveDataIsGrayWithItsAge() {
        val label = syncLabel(SyncState(lastSuccessAt = 0, lastAttemptAt = 0), now = 4 * 60_000, wallNow = wall)
        assertEquals("4m AGO", label.text)
        assertEquals(Freshness.AGING, label.freshness)
    }

    @Test
    fun cachedDataIsGrayWithTheCacheAge() {
        val label = syncLabel(SyncState(cachedAt = wall - 3 * 3_600_000L), now = 0, wallNow = wall)
        assertEquals("CACHED 3h", label.text)
        assertEquals(Freshness.CACHED, label.freshness)
    }

    @Test
    fun connectionProblemIsRedAndCountsDown() {
        // Failed once at t=100 s while showing data cached 2 h ago: next try at 160 s.
        val failing = SyncState(lastAttemptAt = 100_000, failures = 1, cachedAt = wall - 2 * 3_600_000L)
        val label = syncLabel(failing, now = 120_000, wallNow = wall)
        assertEquals("OFFLINE · RETRY 40s", label.text)
        assertEquals(Freshness.FAILING, label.freshness)
        assertTrue(label.spoken.contains("cached data from 2h ago"))
        assertEquals("RECONNECTING", syncLabel(failing.copy(running = true), now = 120_000, wallNow = wall).text)
        assertEquals("OFFLINE", syncLabel(failing.copy(paused = true), now = 120_000, wallNow = wall).text)
    }

    @Test
    fun q1_syncLabelNeverClaimsLiveDuringHostRestart() {
        val liveSync = SyncState(lastSuccessAt = 0, lastAttemptAt = 0)
        assertEquals("LIVE", livePanelSyncLabel(liveSync, now = 10_000, wallNow = wall, hostRestart = null).text)
        val restarting = RestartProgress("pdm-host", RestartPhase.GOING_DOWN, submittedAt = 0)
        assertEquals("RESTARTING", livePanelSyncLabel(liveSync, 10_000, wall, restarting).text)
        assertEquals("WAITING FOR SERVER", livePanelSyncLabel(liveSync, 10_000, wall, restarting.copy(phase = RestartPhase.DOWN)).text)
        assertEquals("RECONNECTING", livePanelSyncLabel(liveSync, 10_000, wall, restarting.copy(phase = RestartPhase.RECOVERING)).text)
        assertEquals("NOT BACK YET", livePanelSyncLabel(liveSync, 10_000, wall, restarting.copy(phase = RestartPhase.TIMED_OUT)).text)
        assertEquals(
            "NOT CONFIRMED",
            livePanelSyncLabel(liveSync, 10_000, wall, restarting.copy(phase = RestartPhase.RECOVERY_FAILED)).text,
        )
        assertEquals("LIVE", livePanelSyncLabel(liveSync, 10_000, wall, restarting.copy(phase = RestartPhase.DONE)).text)
    }

    @Test
    fun beforeTheFirstLoad() {
        assertEquals("LOADING", syncLabel(SyncState(running = true), now = 0, wallNow = wall).text)
    }
}
