package com.pdm.app.ui.adaptive

import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Reads the window, so it follows a window moved to another display or resized in place. */
@Composable
fun rememberUiPosture(): UiPosture {
    val tier = windowTier(LocalConfiguration.current.screenWidthDp)
    val captionTop = WindowInsets.captionBar.getTop(LocalDensity.current)
    val display = LocalView.current.display
    val desktop = isDesktopWindow(display?.displayId ?: 0, display?.flags ?: 0, captionTop)
    val pointer = rememberExternalPointer(enabled = desktop)
    return remember(tier, desktop, pointer) { UiPosture(tier, desktop, pointer) }
}

/**
 * Dormant unless [enabled]: outside a desktop window nothing is registered and no device is
 * scanned. Otherwise one scan on start, then only the system's add / remove callbacks, and the
 * listener is dropped again on stop.
 */
@Composable
private fun rememberExternalPointer(enabled: Boolean): Boolean {
    if (!enabled) return false
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var present by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val inputs = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { present = scanExternalPointer() }
            override fun onInputDeviceRemoved(deviceId: Int) { present = scanExternalPointer() }
            override fun onInputDeviceChanged(deviceId: Int) = Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    present = scanExternalPointer()
                    inputs.registerInputDeviceListener(listener, null)
                }
                Lifecycle.Event.ON_STOP -> inputs.unregisterInputDeviceListener(listener)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            inputs.unregisterInputDeviceListener(listener)
        }
    }
    return present
}

private fun scanExternalPointer(): Boolean = InputDevice.getDeviceIds().any { id ->
    InputDevice.getDevice(id)?.let { isExternalPointer(it.sources, it.vendorId, it.isVirtual) } == true
}
