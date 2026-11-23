package com.pdm.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.ui.components.TechColors
import kotlin.random.Random
import kotlinx.coroutines.delay

/** A one-off remark; [id] makes the same text twice in a row still a new toast. */
data class Quip(val text: String, val id: Long)

/** The app's honest opinion of daylight. */
internal val LIGHT_MODE_QUIPS = listOf(
    "Ew.",
    "Y?",
    "Yuck..",
    "U ok?",
    "My retinas.",
    "Who hurt you?",
    "Bold. Wrong, but bold.",
    "Sunglasses advised.",
    "The servers are in a dark room. Just saying.",
)

/** A quip, never the one shown last time. */
internal fun pickQuip(last: String?, random: Random = Random): String {
    val pool = LIGHT_MODE_QUIPS.filter { it != last }.ifEmpty { LIGHT_MODE_QUIPS }
    return pool[random.nextInt(pool.size)]
}

private const val QUIP_SHOW_MS = 2_600L

/** Bottom-centre plate over everything; leaves by itself or on a tap. */
@Composable
fun ThemeQuipToast(quip: Quip?, onDone: (Long) -> Unit) {
    // Keep the last text while it animates out.
    var shown by remember { mutableStateOf<Quip?>(null) }
    if (quip != null) shown = quip
    LaunchedEffect(quip?.id) {
        val id = quip?.id ?: return@LaunchedEffect
        delay(QUIP_SHOW_MS)
        onDone(id)
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 48.dp)) {
        AnimatedVisibility(
            visible = quip != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val shape = CutCornerShape(bottomEnd = 14.dp)
            Row(
                Modifier
                    // Floats over plates of the same colour; the shadow lifts it off them.
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(TechColors.Hull)
                    .border(1.dp, TechColors.Amber, shape)
                    .clickable { shown?.let { onDone(it.id) } }
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(5.dp).height(44.dp).background(TechColors.Amber))
                Text(
                    shown?.text.orEmpty(),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = TechColors.Ink,
                )
            }
        }
    }
}
