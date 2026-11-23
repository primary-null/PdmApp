package com.pdm.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllocationScaleTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun largestFillsTheRowAndSmallestKeepsAReadableTrack() {
        assertEquals(1f, AllocationScale.span(32 * gib, 32 * gib), 0.001f)
        assertEquals(AllocationScale.MIN_SPAN, AllocationScale.span(gib / 2, 32 * gib), 0.001f)
        assertEquals(AllocationScale.MIN_SPAN, AllocationScale.span(128L * 1024 * 1024, 32 * gib), 0.001f)
    }

    @Test
    fun everyDoublingAddsTheSameLength() {
        val s2 = AllocationScale.span(2 * gib, 32 * gib)
        val s4 = AllocationScale.span(4 * gib, 32 * gib)
        val s8 = AllocationScale.span(8 * gib, 32 * gib)
        assertEquals(s4 - s2, s8 - s4, 0.001f)
        assertEquals(0.53f, s2, 0.01f)
        assertEquals(0.77f, s8, 0.01f)
        assertTrue(s2 < s4 && s4 < s8)
    }

    @Test
    fun unknownAllocationFallsBackToFullWidth() {
        assertEquals(1f, AllocationScale.span(null, 32 * gib), 0f)
        assertEquals(1f, AllocationScale.span(8 * gib, null), 0f)
        assertEquals(1f, AllocationScale.span(0L, 32 * gib), 0f)
    }

    @Test
    fun aFleetOfTinyGuestsStillScales() {
        // Largest is 1 GiB: 1 GiB fills, 512 MiB is the shortest.
        assertEquals(1f, AllocationScale.span(gib, gib), 0.001f)
        assertEquals(AllocationScale.MIN_SPAN, AllocationScale.span(gib / 2, gib), 0.001f)
    }

    @Test
    fun coresScaleTheSameWay() {
        assertEquals(1f, AllocationScale.cores(8.0, 8.0), 0.001f)
        assertEquals(AllocationScale.MIN_SPAN, AllocationScale.cores(1.0, 8.0), 0.001f)
        assertEquals(0.77f, AllocationScale.cores(4.0, 8.0), 0.01f)
        assertEquals(0.53f, AllocationScale.cores(2.0, 8.0), 0.01f)
        // A single-core fleet: the one core fills the row.
        assertEquals(1f, AllocationScale.cores(1.0, 1.0), 0.001f)
        assertEquals(1f, AllocationScale.cores(null, 8.0), 0f)
    }

    @Test
    fun cpuCaptionNamesTheCores() {
        assertEquals("12% · 8 cores", com.pdm.app.ui.home.cpuCaption(0.12, 8.0))
        assertEquals(true, com.pdm.app.ui.home.cpuCaption(0.5, 1.0).endsWith("· 1 core"))
        assertEquals(com.pdm.app.ui.util.formatPercent(0.12), com.pdm.app.ui.home.cpuCaption(0.12, null))
    }

    @Test
    fun storagePoolsScaleAgainstTheLargestOnTheNode() {
        val tib = 1024 * gib
        assertEquals(1f, AllocationScale.storage(4 * tib, 4 * tib), 0.001f)
        assertEquals(AllocationScale.MIN_SPAN, AllocationScale.storage(4 * gib, 4 * tib), 0.001f)
        val s100 = AllocationScale.storage(100 * gib, 4 * tib)
        val s1t = AllocationScale.storage(tib, 4 * tib)
        assertTrue(AllocationScale.MIN_SPAN < s100 && s100 < s1t && s1t < 1f)
    }

    @Test
    fun storageCaptionLeadsWithTheShare() {
        assertEquals(true, com.pdm.app.ui.home.storageCaption(61L * gib, 100L * gib).startsWith("61% · "))
        assertEquals(com.pdm.app.ui.util.formatRatio(null, null), com.pdm.app.ui.home.storageCaption(null, null))
    }

    @Test
    fun interfaceHelpersReadPveFields() {
        assertEquals(listOf("eno1", "eno2"), com.pdm.app.ui.home.splitIfaces("eno1 eno2"))
        assertEquals(listOf("eno1"), com.pdm.app.ui.home.splitIfaces(" eno1 "))
        assertEquals("BRIDGE", com.pdm.app.ui.home.ifaceTypeLabel("bridge"))
        assertEquals("BOND", com.pdm.app.ui.home.ifaceTypeLabel("OVSBond"))
        assertEquals("ALIAS", com.pdm.app.ui.home.ifaceTypeLabel("alias"))
    }
}
