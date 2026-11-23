package com.pdm.app.ui.adaptive

import android.view.Display
import android.view.InputDevice
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

const val OPERATOR_THREE_PANE_MIN_WIDTH_DP = 1200
const val OPERATOR_WIDE_MIN_WIDTH_DP = 1600

/** Window width class: the same 600 / 1200 / 1600 dp breakpoints as the Android window size classes. */
enum class WindowTier { COMPACT, TWO_PANE, THREE_PANE, WIDE }

fun windowTier(widthDp: Int): WindowTier = when {
    widthDp >= OPERATOR_WIDE_MIN_WIDTH_DP -> WindowTier.WIDE
    widthDp >= OPERATOR_THREE_PANE_MIN_WIDTH_DP -> WindowTier.THREE_PANE
    widthDp >= OPERATOR_TWO_PANE_MIN_WIDTH_DP -> WindowTier.TWO_PANE
    else -> WindowTier.COMPACT
}

/**
 * Where the app is running, derived from the window rather than from vendor flags. Measured on a
 * Some desktop modes omit both the DESK uiMode and a platform desktop flag, so
 * neither is used. [desktopWindow] is a freeform or external-display window, [externalPointer] a
 * mouse or trackpad that is not the built-in Samsung one. Holds the tier, not the raw width, so
 * dragging a window edge does not invalidate readers until a breakpoint is crossed.
 */
@Immutable
data class UiPosture(
    val tier: WindowTier,
    val desktopWindow: Boolean,
    val externalPointer: Boolean,
)

val PhonePosture = UiPosture(WindowTier.COMPACT, desktopWindow = false, externalPointer = false)

val LocalUiPosture = compositionLocalOf { PhonePosture }

/**
 * A desktop window has a caption bar (inset present in DeX freeform and maximized windows alike),
 * or lives on a non-default presentation display such as a DeX monitor.
 */
fun isDesktopWindow(displayId: Int, displayFlags: Int, captionBarTopPx: Int): Boolean =
    captionBarTopPx > 0 ||
        (displayId != Display.DEFAULT_DISPLAY && displayFlags and Display.FLAG_PRESENTATION != 0)

/**
 * A real mouse or trackpad. Samsung's built-in `sec_touchpad` reports mouse and touchpad sources
 * on every device with vendor id 0, so a bare source test would always be true.
 */
fun isExternalPointer(sources: Int, vendorId: Int, isVirtual: Boolean): Boolean =
    !isVirtual && vendorId != 0 && sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE
