package com.pdm.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdm.app.data.settings.ThemeMode
import com.pdm.app.data.settings.UiPrefs
import com.pdm.app.system.LaunchAction
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.PdmAppRoot
import com.pdm.app.ui.adaptive.LocalUiPosture
import com.pdm.app.ui.adaptive.rememberUiPosture
import com.pdm.app.ui.components.DarkTechPalette
import com.pdm.app.ui.components.LightTechPalette
import com.pdm.app.ui.theme.PdmTheme

class MainActivity : ComponentActivity() {
    private val fleet: FleetViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The XML window is black; a light theme repaints it before the first frame, so no flash.
        window.setBackgroundDrawable(ColorDrawable(startBackground(UiPrefs(this).themeMode)))
        // A recreated activity already handled the intent that first opened it.
        if (savedInstanceState == null) LaunchAction.from(intent)?.let(fleet::handleLaunch)
        setContent {
            val state by fleet.fleet.collectAsStateWithLifecycle()
            PdmTheme(state.themeMode) {
                CompositionLocalProvider(LocalUiPosture provides rememberUiPosture()) {
                    PdmAppRoot(fleet)
                }
            }
        }
    }

    /** singleTask: shortcuts, the tile, the widget and device controls land here while open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LaunchAction.from(intent)?.let(fleet::handleLaunch)
    }

    private fun startBackground(mode: ThemeMode): Int {
        val nightUi = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val light = mode == ThemeMode.LIGHT || (mode == ThemeMode.SYSTEM && !nightUi)
        return (if (light) LightTechPalette else DarkTechPalette).background.toArgb()
    }
}
