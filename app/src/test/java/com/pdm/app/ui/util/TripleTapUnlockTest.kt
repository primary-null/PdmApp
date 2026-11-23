package com.pdm.app.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripleTapUnlockTest {
    @Test
    fun thirdTapWithinWindowUnlocks() {
        val unlock = TripleTapUnlock(windowMs = 1_200L)
        assertFalse(unlock.tap(1_000L))
        assertFalse(unlock.tap(1_500L))
        assertTrue(unlock.tap(2_000L))
    }

    @Test
    fun gapResetsTheStreak() {
        val unlock = TripleTapUnlock(windowMs = 1_200L)
        assertFalse(unlock.tap(1_000L))
        assertFalse(unlock.tap(1_500L))
        assertFalse(unlock.tap(4_000L))
        assertFalse(unlock.tap(4_300L))
        assertTrue(unlock.tap(4_500L))
    }
}
