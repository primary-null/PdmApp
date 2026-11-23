package com.pdm.app.ui.home

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.settings.WatermarkMode
import com.pdm.app.data.settings.WatermarkTone
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.components.TechColors

/** Watermark strength in open black; panels pass about a quarter of it through. */
private const val WATERMARK_ALPHA_AMBER = 0.16f
private const val WATERMARK_ALPHA_NEUTRAL = 0.11f

/** What the screen is "about": the manager on the overview, the remote/node/guest once you drill in. */
internal fun watermarkName(state: FleetUiState): String? {
    val pdm = state.host?.node ?: state.hostLabel.substringBefore(':')
    when (state.watermarkMode) {
        WatermarkMode.OFF -> return null
        WatermarkMode.PDM -> return pdm
        WatermarkMode.FOLLOW -> Unit
        WatermarkMode.ACTIVITY, WatermarkMode.API, WatermarkMode.VERBOSE -> return null
    }
    val focus = state.nav.focus
    return when (focus) {
        is DashFocus.RemoteDetail -> state.remotes.firstOrNull { it.id == focus.id }?.name
        is DashFocus.NodeDetail -> state.nodes.firstOrNull { it.id == focus.id }?.name
        is DashFocus.GuestDetail -> state.guests.firstOrNull { it.id == focus.id }?.name
        is DashFocus.NodeUpdates -> focus.node
        else -> null
    } ?: state.selectedRemoteId
        ?: pdm
}

/**
 * The server's name drawn huge behind everything, blurred like frosted glass. The background stays
 * plain; only a soft shape of the name shows through. Blur needs Android 12+; older devices get the
 * same text a touch fainter instead.
 */
@Composable
internal fun ServerWatermark(name: String?, tone: WatermarkTone, strength: com.pdm.app.data.settings.WatermarkStrength) {
    if (name.isNullOrBlank()) return
    val base = if (tone == WatermarkTone.AMBER) TechColors.Amber else TechColors.Ink
    val alpha = (if (tone == WatermarkTone.AMBER) WATERMARK_ALPHA_AMBER else WATERMARK_ALPHA_NEUTRAL) *
        strength.multiplier * TechColors.Watermark
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        // Span the screen: as wide as the name allows (monospace glyph ~0.62 em), capped by height.
        val chars = name.length.coerceAtLeast(3)
        val size = with(LocalDensity.current) { minOf(heightPx * 0.42f, widthPx * 0.92f / (chars * 0.62f)).toSp() }
        AnimatedContent(
            targetState = name.uppercase(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.align(Alignment.Center),
            label = "watermark",
        ) { text ->
            Box {
                Text(
                    text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = size,
                    letterSpacing = (-0.02).sp * size.value,
                    color = base.copy(alpha = if (Build.VERSION.SDK_INT >= 31) alpha else alpha * 0.5f),
                    modifier = Modifier.blur(18.dp),
                )
            }
        }
    }
}

/**
 * The count behind a heading, set large at the right edge: a crisp numeral over a blurred copy of
 * itself, so it glows slightly without needing a box around it (fainter on paper, where glow stains).
 */
@Composable
internal fun HeroCount(count: Int) {
    Box(contentAlignment = Alignment.CenterEnd) {
        Text(
            count.toString(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 56.sp,
            color = TechColors.Amber.copy(alpha = 0.35f * TechColors.Glow),
            modifier = Modifier.blur(10.dp),
        )
        Text(
            count.toString(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            fontSize = 56.sp,
            color = TechColors.Ink.copy(alpha = 0.92f),
        )
    }
}
