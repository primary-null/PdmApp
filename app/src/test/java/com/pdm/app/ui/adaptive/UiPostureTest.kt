package com.pdm.app.ui.adaptive

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPostureTest {
    @Test
    fun tiersSwitchAtTheBreakpoints() {
        assertEquals(WindowTier.COMPACT, windowTier(386))
        assertEquals(WindowTier.COMPACT, windowTier(599))
        assertEquals(WindowTier.TWO_PANE, windowTier(600))
        assertEquals(WindowTier.TWO_PANE, windowTier(1199))
        assertEquals(WindowTier.THREE_PANE, windowTier(1200))
        assertEquals(WindowTier.THREE_PANE, windowTier(1599))
        assertEquals(WindowTier.WIDE, windowTier(1600))
        assertEquals(WindowTier.WIDE, windowTier(1920))
    }

    @Test
    fun phoneAndPlainTabletAreNotDesktop() {
        assertFalse(isDesktopWindow(Display.DEFAULT_DISPLAY, displayFlags = 0xc083, captionBarTopPx = 0))
    }

    @Test
    fun captionBarMarksDesktopOnTheDefaultDisplay() {
        // Standalone tablet DeX has no second display; the caption bar is the only sign.
        assertTrue(isDesktopWindow(Display.DEFAULT_DISPLAY, displayFlags = 0xc083, captionBarTopPx = 40))
    }

    @Test
    fun presentationDisplayMarksDesktopEvenMaximized() {
        // Probe values: the DeX monitor was display 16 with flags 0x2808b (PRESENTATION set).
        assertTrue(isDesktopWindow(displayId = 16, displayFlags = 0x2808b, captionBarTopPx = 0))
    }

    @Test
    fun plainSecondaryDisplayIsNotDesktop() {
        assertFalse(isDesktopWindow(displayId = 15, displayFlags = 0x4, captionBarTopPx = 0))
    }

    @Test
    fun builtInSamsungTouchpadIsNotAPointer() {
        assertFalse(isExternalPointer(sources = 0x10210b, vendorId = 0, isVirtual = false))
    }

    @Test
    fun realMouseIsAPointer() {
        assertTrue(isExternalPointer(sources = 0x1002313, vendorId = 1133, isVirtual = false))
    }

    @Test
    fun touchscreenKeysAndVirtualDevicesAreNotPointers() {
        assertFalse(isExternalPointer(sources = 0x1103, vendorId = 0, isVirtual = false)) // sec_touchscreen
        assertFalse(isExternalPointer(sources = 0x101, vendorId = 1, isVirtual = false)) // gpio-keys
        assertFalse(isExternalPointer(sources = 0x301, vendorId = 0, isVirtual = true)) // Virtual
        assertFalse(isExternalPointer(sources = 0x1002313, vendorId = 1133, isVirtual = true))
    }
}
