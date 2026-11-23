package com.pdm.app.ui.util

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Optional second check before a destructive action: fingerprint, face or the device PIN, via
 * Android's own prompt (Android 10+). Nothing is stored or unlocked; it only proves the person
 * holding the tablet knows the screen lock.
 */
object ScreenLock {
    /** Android 10+ with a screen lock set: the prompt can fall back to the PIN. */
    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Shows the prompt. [onPass] runs after a success; a cancel does nothing; any other failure
     * (no lock set any more, too many tries) goes to [onRefused] and the action does not run.
     */
    fun confirm(context: Context, title: String, onPass: () -> Unit, onRefused: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onRefused("Screen lock check needs Android 10 or later. Turn it off in Settings > Extras.")
            return
        }
        val builder = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle("Confirm with your screen lock")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onPass()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) {
                        return
                    }
                    onRefused(errString?.toString()?.takeIf { it.isNotBlank() } ?: "Screen lock check failed.")
                }
            },
        )
    }
}

/**
 * Wraps a confirm callback: with [enabled], the action runs only after [ScreenLock.confirm]
 * passes; otherwise it runs at once, exactly as before.
 */
@Composable
fun rememberScreenLockGate(enabled: Boolean, onRefused: (String) -> Unit): (title: String, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val on by rememberUpdatedState(enabled)
    val refused by rememberUpdatedState(onRefused)
    return remember(context) {
        { title, action ->
            if (on) ScreenLock.confirm(context, title, onPass = action, onRefused = { refused(it) }) else action()
        }
    }
}
