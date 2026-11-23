package com.pdm.app.data

import com.pdm.app.data.settings.UiPrefs
import com.pdm.app.data.settings.WatermarkMode
import com.pdm.app.data.settings.WatermarkTone
import org.junit.Assert.assertEquals
import org.junit.Test

class UiPrefsTest {
    @Test
    fun defaultsThenRoundTrip() {
        val prefs = UiPrefs(FakeSharedPreferences())
        assertEquals(WatermarkMode.FOLLOW, prefs.watermarkMode)
        assertEquals(WatermarkTone.AMBER, prefs.watermarkTone)
        prefs.motionStyle = com.pdm.app.data.settings.MotionStyle.GLIDE
        prefs.watermarkStrength = com.pdm.app.data.settings.WatermarkStrength.SUBTLE
        assertEquals(com.pdm.app.data.settings.MotionStyle.GLIDE, prefs.motionStyle)
        assertEquals(com.pdm.app.data.settings.WatermarkStrength.SUBTLE, prefs.watermarkStrength)
        prefs.watermarkMode = WatermarkMode.PDM
        prefs.watermarkTone = WatermarkTone.NEUTRAL
        assertEquals(WatermarkMode.PDM, prefs.watermarkMode)
        assertEquals(WatermarkTone.NEUTRAL, prefs.watermarkTone)
    }

    @Test
    fun verboseModePersistsAndUnknownPreferenceFallsBack() {
        val backing = FakeSharedPreferences()
        val prefs = UiPrefs(backing)
        prefs.watermarkMode = WatermarkMode.VERBOSE
        assertEquals(WatermarkMode.VERBOSE, UiPrefs(backing).watermarkMode)
        backing.edit().putString("watermark_mode", "FUTURE_MODE").commit()
        assertEquals(WatermarkMode.FOLLOW, UiPrefs(backing).watermarkMode)
    }

    @Test
    fun switchesOffWithoutForgettingTheLastChoice() {
        val prefs = UiPrefs(FakeSharedPreferences())
        prefs.watermarkModeLatent = WatermarkMode.API
        prefs.watermarkMode = WatermarkMode.OFF
        prefs.watermarkStrengthLatent = com.pdm.app.data.settings.WatermarkStrength.BOLD
        prefs.watermarkStrength = com.pdm.app.data.settings.WatermarkStrength.OFF
        assertEquals(WatermarkMode.API, prefs.watermarkModeLatent)
        assertEquals(com.pdm.app.data.settings.WatermarkStrength.BOLD, prefs.watermarkStrengthLatent)
        assertEquals(0f, prefs.watermarkStrength.multiplier, 0f)
    }
}
