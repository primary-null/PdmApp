package com.pdm.app.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Plain on/off pulses support motors without haptic primitives or amplitude control. Different
 * pulse lengths distinguish tick, confirm and refusal. Silent when the user turned touch
 * feedback off in system settings.
 */
class Haptics(private val context: Context) {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val enabled: Boolean
        get() = vibrator?.hasVibrator() == true &&
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    /** One letter of the pull band filled. */
    fun tick() = play(VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    /** The band is full: let go to refresh. */
    fun confirm() = play(VibrationEffect.createOneShot(CONFIRM_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    /** Still offline after the try: two short knocks. */
    fun refuse() = play(VibrationEffect.createWaveform(longArrayOf(0, 35, 70, 35), -1))

    private fun play(effect: VibrationEffect) {
        if (!enabled) return
        runCatching { vibrator?.vibrate(effect) }
    }

    companion object {
        const val TICK_MS = 14L
        const val CONFIRM_MS = 55L

        /**
         * Ten-second hold ramp as on/off timings (starting with an off gap), for motors without
         * amplitude control: four pulses a second, each second's pulses longer than the last, so the
         * hold grows from a light patter to a near-solid buzz. Every quarter second has some pulse.
         */
        fun holdRampTimings(seconds: Int = 10, pulsesPerSecond: Int = 4): LongArray {
            val slot = 1000L / pulsesPerSecond
            val out = ArrayList<Long>(seconds * pulsesPerSecond * 2)
            for (s in 0 until seconds) {
                // 40 ms on at first, up to slot - 50 ms: the last second still pulses, 50 ms gaps.
                val on = 40L + ((slot - 50L - 40L) * s) / (seconds - 1).coerceAtLeast(1)
                repeat(pulsesPerSecond) {
                    out += slot - on
                    out += on
                }
            }
            return out.toLongArray()
        }
    }
}
