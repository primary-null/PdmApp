package com.pdm.app.ui.adaptive

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/** Keyboard commands for a desk window. Touch-only use never sees them. */
enum class DeskCommand {
    REFRESH, FOCUS_FILTER, BACK,
    /** Ctrl+1 / 2 / 3: the leaders' and graphs' 1H / 1D / 1W. */
    TIMEFRAME_HOUR, TIMEFRAME_DAY, TIMEFRAME_WEEK,
}

/**
 * Plain `/` only reaches this when no text field took it, because a focused field consumes typed
 * characters first; Ctrl+F is the same command for a user who reaches for it by habit.
 */
fun deskCommand(key: Key, ctrl: Boolean, alt: Boolean, meta: Boolean, shift: Boolean): DeskCommand? = when {
    alt || meta -> null
    key == Key.F5 -> DeskCommand.REFRESH
    ctrl && key == Key.R -> DeskCommand.REFRESH
    ctrl && key == Key.F -> DeskCommand.FOCUS_FILTER
    !ctrl && !shift && key == Key.Slash -> DeskCommand.FOCUS_FILTER
    !ctrl && key == Key.Escape -> DeskCommand.BACK
    ctrl && !shift && key == Key.One -> DeskCommand.TIMEFRAME_HOUR
    ctrl && !shift && key == Key.Two -> DeskCommand.TIMEFRAME_DAY
    ctrl && !shift && key == Key.Three -> DeskCommand.TIMEFRAME_WEEK
    else -> null
}

/**
 * Key handling for a desk window; a no-op modifier otherwise. The handler sits above a focusable
 * node so it still hears keys when nothing more specific holds focus, and holding a key does not
 * repeat a refresh.
 */
fun Modifier.deskShortcuts(
    enabled: Boolean,
    focusRequester: FocusRequester,
    onCommand: (DeskCommand) -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    onKeyEvent { event ->
        val command = if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
            deskCommand(event.key, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed, event.isShiftPressed)
        } else {
            null
        }
        command?.also(onCommand) != null
    }
        .focusRequester(focusRequester)
        .focusable()
}
