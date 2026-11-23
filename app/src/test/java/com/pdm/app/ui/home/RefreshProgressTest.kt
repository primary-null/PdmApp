package com.pdm.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshProgressTest {
    @Test
    fun aTickPerCompletedLetter() {
        assertEquals(0, refreshGlyphsFilled(0f, 7))
        assertEquals(0, refreshGlyphsFilled(0.99f / 7f, 7))
        // Float error at exactly 3/7 must still count three letters.
        assertEquals(3, refreshGlyphsFilled(3f / 7f, 7))
        assertEquals(6, refreshGlyphsFilled(0.99f, 7))
        assertEquals(7, refreshGlyphsFilled(1f, 7))
        assertEquals(7, refreshGlyphsFilled(1.4f, 7))
        assertEquals(0, refreshGlyphsFilled(0.5f, 0))
    }

    @Test
    fun holdRampPulsesLengthenAndFillTenSeconds() {
        val t = com.pdm.app.ui.util.Haptics.holdRampTimings()
        assertEquals(80, t.size)
        assertEquals(10_000L, t.sum())
        val onTimes = t.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(40L, onTimes.first())
        assertEquals(200L, onTimes.last())
        assertEquals(onTimes, onTimes.sorted())
        // Never a dead quarter second: every off gap is shorter than the slot.
        org.junit.Assert.assertTrue(t.filterIndexed { i, _ -> i % 2 == 0 }.all { it in 1..249 })
    }

    @Test
    fun wordFillsLeftToRight() {
        assertEquals(0f, refreshGlyphFill(0f, 0), 0f)
        assertEquals(1f, refreshGlyphFill(1f / 7f, 0), 0.001f)
        assertEquals(0f, refreshGlyphFill(1f / 7f, 1), 0.001f)
        assertEquals(0.5f, refreshGlyphFill(0.5f / 7f, 0), 0.001f)
        assertEquals(1f, refreshGlyphFill(1f, REFRESH_WORD.lastIndex), 0.001f)
        assertEquals(0f, refreshGlyphFill(1f, REFRESH_WORD.length), 0f)
    }

    @Test
    fun sweepLightsTheGlyphUnderItAndFadesOut() {
        assertEquals(1f, refreshSweepGlow(phase = 3f, index = 3), 0.001f)
        assertEquals(1f - 1f / SWEEP_WIDTH, refreshSweepGlow(phase = 3f, index = 2), 0.001f)
        assertEquals(0f, refreshSweepGlow(phase = 3f, index = 0), 0f)
        assertEquals(0f, refreshSweepGlow(phase = -SWEEP_WIDTH, index = 0), 0f)
    }

    @Test
    fun pullSaysRefreshWhenLiveAndOfflineOnSavedData() {
        val live = com.pdm.app.ui.FleetUiState(sync = com.pdm.app.ui.SyncState(lastSuccessAt = 1))
        assertEquals(LIVE_PULL, pullLook(live))

        // Server not answering: OFFLINE, red, refuses.
        val down = live.copy(sync = live.sync.copy(failures = 1, cachedAt = 5))
        assertEquals(PullLook(OFFLINE_WORD, refusal = true, failing = true), pullLook(down))

        // Offline view on its way to live: OFFLINE, gray, still refuses until it gets there.
        val cached = com.pdm.app.ui.FleetUiState(offline = true, sync = com.pdm.app.ui.SyncState(cachedAt = 5))
        assertEquals(PullLook(OFFLINE_WORD, refusal = true, failing = false), pullLook(cached))

        // Saved login rejected: retries paused, still OFFLINE and red.
        val paused = cached.copy(sync = cached.sync.copy(paused = true))
        assertEquals(true, pullLook(paused).failing)
    }

    @Test
    fun offlineWordFillsAcrossItsOwnLength() {
        assertEquals(1f, refreshGlyphFill(1f, OFFLINE_WORD.lastIndex, OFFLINE_WORD.length), 0.001f)
        assertEquals(0f, refreshGlyphFill(0.5f, OFFLINE_WORD.lastIndex, OFFLINE_WORD.length), 0f)
    }
}
