package com.pdm.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHelpersTest {
    @Test
    fun shortFingerprintKeepsShortValues() {
        assertEquals("abc", shortFingerprint("abc"))
    }

    @Test
    fun shortFingerprintClipsLongValues() {
        val fp = "0123456789abcdef0123456789abcdef"
        assertEquals("01234567…89abcdef", shortFingerprint(fp))
    }
}
