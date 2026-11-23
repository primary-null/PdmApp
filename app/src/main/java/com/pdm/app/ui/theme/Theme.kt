package com.pdm.app.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.pdm.app.data.settings.ThemeMode
import com.pdm.app.ui.components.DarkTechPalette
import com.pdm.app.ui.components.LightTechPalette
import com.pdm.app.ui.components.LocalTechColors
import com.pdm.app.ui.components.TechPalette

private val OledOnSurface = Color(0xFFF2F2F2)

private val OledDarkColors = darkColorScheme(
    primary = Color(0xFFFF9800),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3D2200),
    onPrimaryContainer = Color(0xFFFF9800),
    secondary = Color(0xFF9E9E9E),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = OledOnSurface,
    surface = Color.Black,
    onSurface = OledOnSurface,
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
)

/** Built from [LightTechPalette] so Material parts (dialogs, fields, menus) match the plates. */
private val DatasheetLightColors = lightColorScheme(
    primary = LightTechPalette.amber,
    onPrimary = LightTechPalette.hull,
    primaryContainer = Color(0xFFF6E3CF),
    onPrimaryContainer = Color(0xFF5A2B00),
    secondary = LightTechPalette.mute,
    onSecondary = LightTechPalette.hull,
    background = LightTechPalette.background,
    onBackground = LightTechPalette.ink,
    surface = LightTechPalette.background,
    onSurface = LightTechPalette.ink,
    surfaceVariant = LightTechPalette.deck,
    onSurfaceVariant = Color(0xFF4A463F),
    surfaceContainerHigh = LightTechPalette.hull,
    outline = LightTechPalette.edge,
    error = Color(0xFFB3261E),
    onError = LightTechPalette.hull,
)

/** The palette a theme choice resolves to; System follows the device's dark setting. */
@Composable
fun techPaletteFor(mode: ThemeMode): TechPalette = when (mode) {
    ThemeMode.DARK -> DarkTechPalette
    ThemeMode.LIGHT -> LightTechPalette
    ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkTechPalette else LightTechPalette
}

@Composable
fun PdmTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val palette = techPaletteFor(mode)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // The window shows through for a frame on resize and rotation; keep it the same colour.
            window.setBackgroundDrawable(ColorDrawable(palette.background.toArgb()))
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = palette.isLight
            controller.isAppearanceLightNavigationBars = palette.isLight
            // Edge-to-edge paints a contrast scrim under the gesture bar: unseen on black, a white
            // strip on paper. The bar icons already follow the palette, so the scrim is not needed.
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
    CompositionLocalProvider(LocalTechColors provides palette) {
        MaterialTheme(colorScheme = if (palette.isLight) DatasheetLightColors else OledDarkColors) {
            // Material3 LocalContentColor defaults to Black. Without a Surface,
            // uncolored Text (wordmark, setting titles, guest names) is invisible on OLED.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = palette.background,
                contentColor = if (palette.isLight) palette.ink else OledOnSurface,
                content = content,
            )
        }
    }
}
