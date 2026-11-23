package com.pdm.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pdm.app.data.settings.MotionStyle

/** One restrained entrance on navigation; polling and live count updates do not replay it. */
internal fun Modifier.tastefulMotion(key: Any?, style: MotionStyle): Modifier = composed {
    val progress = remember { Animatable(1f) }
    val distance = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(key, style) {
        if (style == MotionStyle.OFF) progress.snapTo(1f) else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(240))
        }
    }
    graphicsLayer {
        val p = if (style == MotionStyle.OFF) 1f else progress.value
        alpha = 0.75f + 0.25f * p
        translationY = if (style == MotionStyle.GLIDE) distance * (1f - p) else 0f
        val scale = if (style == MotionStyle.FOCUS) 0.985f + 0.015f * p else 1f
        scaleX = scale
        scaleY = scale
    }
}
