package com.pdm.app.ui.adaptive

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeskShortcutsTest {
    private fun cmd(key: Key, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false, shift: Boolean = false) =
        deskCommand(key, ctrl, alt, meta, shift)

    @Test
    fun refreshOnF5AndCtrlR() {
        assertEquals(DeskCommand.REFRESH, cmd(Key.F5))
        assertEquals(DeskCommand.REFRESH, cmd(Key.R, ctrl = true))
        assertNull(cmd(Key.R))
    }

    @Test
    fun filterOnSlashAndCtrlF() {
        assertEquals(DeskCommand.FOCUS_FILTER, cmd(Key.Slash))
        assertEquals(DeskCommand.FOCUS_FILTER, cmd(Key.F, ctrl = true))
        assertNull(cmd(Key.F))
        assertNull(cmd(Key.Slash, shift = true)) // `?`
        assertNull(cmd(Key.Slash, ctrl = true))
    }

    @Test
    fun escapeGoesBack() {
        assertEquals(DeskCommand.BACK, cmd(Key.Escape))
    }

    @Test
    fun ctrlDigitsPickTheTimeframe() {
        assertEquals(DeskCommand.TIMEFRAME_HOUR, cmd(Key.One, ctrl = true))
        assertEquals(DeskCommand.TIMEFRAME_DAY, cmd(Key.Two, ctrl = true))
        assertEquals(DeskCommand.TIMEFRAME_WEEK, cmd(Key.Three, ctrl = true))
        assertNull(cmd(Key.One))
        assertNull(cmd(Key.One, ctrl = true, shift = true))
    }

    @Test
    fun altAndMetaChordsBelongToTheSystem() {
        assertNull(cmd(Key.F5, alt = true))
        assertNull(cmd(Key.R, ctrl = true, meta = true))
        assertNull(cmd(Key.Escape, alt = true))
    }
}
