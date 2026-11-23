package com.pdm.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.settings.WatermarkMode
import com.pdm.app.data.settings.WatermarkTone
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.components.TechColors

/**
 * Collects the request feed here, not in the home screen: each API call then redraws only the
 * background, not every pane above it.
 */
@Composable
internal fun AtmosphereBackground(
    state: FleetUiState,
    feed: kotlinx.coroutines.flow.StateFlow<List<com.pdm.app.data.api.ApiActivity>>,
) {
    if (!state.watermarkMode.showsActivity) {
        ServerWatermark(watermarkName(state), state.watermarkTone, state.watermarkStrength)
        return
    }
    val activity by feed.collectAsStateWithLifecycle()
    val mode = state.watermarkMode
    val base = if (state.watermarkTone == WatermarkTone.AMBER) TechColors.Amber else TechColors.Ink
    val color = base.copy(alpha = 0.30f * state.watermarkStrength.multiplier * TechColors.Watermark)
    BoxWithConstraints(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        // Keep a stable, quiet window. No scrolling ticker, blinking cursor or fabricated work.
        val rowHeight = if (mode == WatermarkMode.VERBOSE) 102 else 80
        val capacity = ((maxHeight.value - 140) / rowHeight).toInt().coerceIn(1, 6)
        val events = activity.takeLast(capacity)
        val wide = maxWidth >= 700.dp
        Column(
            Modifier.fillMaxWidth().align(Alignment.CenterStart).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (state.demo) "APP ACTIVITY / DEMO" else "APP ACTIVITY / RECENT REQUESTS",
                color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
            )
            if (events.isEmpty()) {
                Text(
                    when {
                        state.demo -> "Demo is local. No API requests are sent."
                        state.offline -> "Offline view. Answers come from the cache; nothing is sent."
                        else -> "Waiting for the next request..."
                    },
                    color = color, fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                )
            }
            events.forEach { event ->
                val outcome = event.outcome + (event.elapsedMs?.let { " / ${it}ms to headers" } ?: "")
                if (mode == WatermarkMode.VERBOSE && wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Text(event.route.action, modifier = Modifier.weight(0.4f), color = color,
                            fontFamily = FontFamily.Monospace, fontSize = 20.sp, lineHeight = 24.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Column(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${event.route.method} ${event.route.endpoint}", color = color,
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 18.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(outcome, color = color, fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (mode != WatermarkMode.API) {
                            Text(event.route.action, color = color, fontFamily = FontFamily.Monospace,
                                fontSize = 20.sp, lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (mode != WatermarkMode.ACTIVITY) {
                            Text("${event.route.method} ${event.route.endpoint}", color = color,
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 18.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(outcome,
                            color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
