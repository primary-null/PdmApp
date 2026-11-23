package com.pdm.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** What the blurred background name shows. */
enum class WatermarkMode {
    OFF, PDM, FOLLOW, ACTIVITY, API, VERBOSE;
    val showsActivity: Boolean get() = this == ACTIVITY || this == API || this == VERBOSE
}

/** Watermark colour: amber matches the rails; neutral keeps amber for things that need attention. */
enum class WatermarkTone { AMBER, NEUTRAL }
enum class MotionStyle { OFF, GLIDE, FOCUS }

/** Night console (the original look), daylight datasheet, or whatever the system is set to. */
enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class WatermarkStrength(val multiplier: Float) { SUBTLE(0.5f), STANDARD(1f), BOLD(1.5f), OFF(0f) }

/** Look-and-feel choices from Settings > Extras. Plain prefs: nothing here is secret. */
class UiPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(NAME, Context.MODE_PRIVATE))

    var watermarkMode: WatermarkMode
        get() = enumOr(prefs.getString(KEY_MODE, null), WatermarkMode.FOLLOW)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    var watermarkTone: WatermarkTone
        get() = enumOr(prefs.getString(KEY_TONE, null), WatermarkTone.AMBER)
        set(value) = prefs.edit { putString(KEY_TONE, value.name) }

    var themeMode: ThemeMode
        get() = enumOr(prefs.getString("theme_mode", null), ThemeMode.DARK)
        set(value) = prefs.edit { putString("theme_mode", value.name) }

    var motionStyle: MotionStyle
        get() = enumOr(prefs.getString("motion_style", null), MotionStyle.OFF)
        set(value) = prefs.edit { putString("motion_style", value.name) }

    var watermarkStrength: WatermarkStrength
        get() = enumOr(prefs.getString("watermark_strength", null), WatermarkStrength.STANDARD)
        set(value) = prefs.edit { putString("watermark_strength", value.name) }

    /** Dashboard as full-width plates with graphs (the EXPAND toggle). */
    var dashboardExpanded: Boolean
        get() = prefs.getBoolean("dashboard_expanded", false)
        set(value) = prefs.edit { putBoolean("dashboard_expanded", value) }

    /** Keep an encrypted offline copy of what logins with a saved credential read. Default on. */
    var offlineCache: Boolean
        get() = prefs.getBoolean("offline_cache", true)
        set(value) = prefs.edit { putBoolean("offline_cache", value) }

    /** Ask for the screen lock before Stop, Shutdown, rollback, task stop and PDM restart. Default off. */
    var screenLockConfirm: Boolean
        get() = prefs.getBoolean("screen_lock_confirm", false)
        set(value) = prefs.edit { putBoolean("screen_lock_confirm", value) }

    /** Last background that was actually on. Turning the switch off does not forget it. */
    var watermarkModeLatent: WatermarkMode
        get() {
            val saved = enumOr(prefs.getString(KEY_MODE_LATENT, null), WatermarkMode.OFF)
            if (saved != WatermarkMode.OFF) return saved
            return if (watermarkMode != WatermarkMode.OFF) watermarkMode else WatermarkMode.FOLLOW
        }
        set(value) {
            if (value != WatermarkMode.OFF) prefs.edit { putString(KEY_MODE_LATENT, value.name) }
        }

    /** Last strength that was actually on. */
    var watermarkStrengthLatent: WatermarkStrength
        get() {
            val saved = enumOr(prefs.getString(KEY_STRENGTH_LATENT, null), WatermarkStrength.OFF)
            if (saved != WatermarkStrength.OFF) return saved
            return if (watermarkStrength != WatermarkStrength.OFF) watermarkStrength else WatermarkStrength.STANDARD
        }
        set(value) {
            if (value != WatermarkStrength.OFF) prefs.edit { putString(KEY_STRENGTH_LATENT, value.name) }
        }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        const val NAME = "pdm_ui"
        private const val KEY_MODE = "watermark_mode"
        private const val KEY_TONE = "watermark_tone"
        private const val KEY_MODE_LATENT = "watermark_mode_latent"
        private const val KEY_STRENGTH_LATENT = "watermark_strength_latent"
    }
}
