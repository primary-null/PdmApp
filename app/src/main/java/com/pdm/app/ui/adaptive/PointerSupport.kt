package com.pdm.app.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Right-click (the secondary button), which touch has no equivalent for. A no-op modifier unless
 * [enabled], so a touch-only window installs no pointer handler at all.
 */
@Composable
fun Modifier.secondaryClick(enabled: Boolean, onSecondaryClick: () -> Unit): Modifier {
    if (!enabled) return this
    val latest by rememberUpdatedState(onSecondaryClick)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    latest()
                }
            }
        }
    }
}

/** Hand cursor over something clickable, for a mouse only. */
fun Modifier.handOnHover(enabled: Boolean): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this
