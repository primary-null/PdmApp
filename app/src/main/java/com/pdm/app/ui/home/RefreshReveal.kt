package com.pdm.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.pdm.app.ui.util.Haptics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.ui.components.TechColors
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The band stays open at least this long, so a fast answer still reads as "it refreshed". */
private const val MIN_HOLD_MS = 450L

/** A slow load stops holding the band after this; the header chip keeps saying it is syncing. */
private const val MAX_HOLD_MS = 15_000L

/** Finger travel per pixel of band: a little resistance, so the word fills on purpose. */
private const val PULL_RESISTANCE = 0.6f

/**
 * Pull down from the top of [content] to reveal REFRESH behind it. Letters fill in order with the
 * pull; a tick marks the point where letting go refreshes, and releasing earlier does nothing.
 * After a refresh the band stays open with a sweep across the word until [refreshing] goes false.
 *
 * Offline or on cached data ([look] refusing), the word is OFFLINE, red while the server is not
 * answering. Letting go still tries to reconnect; if the answer is still no, the word shakes once
 * with a reject haptic before the band closes.
 *
 * [pullBusy] means a pulled load is already running (maybe from the other pane); a new pull only
 * springs back.
 */
@Composable
internal fun RefreshReveal(
    refreshing: Boolean,
    pullBusy: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    look: PullLook = LIVE_PULL,
    content: @Composable () -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val letterSize = if (wide) 64.sp else 32.sp
    val letterGap = if (wide) 8.dp else 3.dp
    val band = if (wide) 128.dp else 72.dp
    val maxPx = with(LocalDensity.current) { band.toPx() }
    val pullPx = remember { mutableFloatStateOf(0f) }
    val holding = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = remember(context) { Haptics(context.applicationContext) }
    val refreshingNow by rememberUpdatedState(refreshing)
    val refusingNow by rememberUpdatedState(look.refusal)
    val shakePx = remember { Animatable(0f) }
    val shake = with(LocalDensity.current) { 14.dp.toPx() }
    val busyNow by rememberUpdatedState(pullBusy)
    val refreshNow by rememberUpdatedState(onRefresh)
    val connection = remember(maxPx, scope) {
        PullConnection(
            pullPx = pullPx,
            maxPx = maxPx,
            holding = holding,
            scope = scope,
            busy = { busyNow },
            onArmed = { haptics.confirm() },
            onRefresh = { refreshNow() },
        )
    }
    LaunchedEffect(holding.value) {
        if (!holding.value) return@LaunchedEffect
        delay(MIN_HOLD_MS)
        withTimeoutOrNull(MAX_HOLD_MS) { snapshotFlow { refreshingNow }.first { !it } }
        if (refusingNow) {
            // Still no server: say no, then close.
            haptics.refuse()
            shakePx.animateTo(
                0f,
                keyframes {
                    durationMillis = 420
                    -shake at 70
                    shake at 140
                    -shake * 0.7f at 210
                    shake * 0.7f at 280
                    -shake * 0.3f at 350
                },
            )
        }
        connection.settle(0f)
        holding.value = false
    }
    val progress = if (maxPx <= 0f) 0f else (pullPx.floatValue / maxPx).coerceIn(0f, 1f)
    // A tick per letter while the finger fills the word; the last letter is the armed confirm,
    // springing back and the held band stay quiet.
    val letters = look.word.length
    LaunchedEffect(letters) {
        var last = 0
        snapshotFlow { refreshGlyphsFilled(pullPx.floatValue / maxPx.coerceAtLeast(1f), letters) to holding.value }
            .collect { (filled, held) ->
                if (!held && filled > last && filled < letters) haptics.tick()
                last = filled
            }
    }
    Box(modifier.nestedScroll(connection).clipToBounds()) {
        if (progress > 0f) {
            val fill = when {
                !look.refusal -> TechColors.Amber
                look.failing -> TechColors.Danger
                else -> TechColors.Mute
            }
            RefreshWord(
                word = look.word,
                fill = fill,
                progress = progress,
                sweeping = holding.value,
                letterSize = letterSize,
                gap = letterGap,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(band)
                    .offset { IntOffset(shakePx.value.roundToInt(), 0) }
                    .clearAndSetSemantics {
                        contentDescription = when {
                            look.refusal && holding.value -> "Offline, trying to reconnect"
                            look.refusal -> "Offline, showing saved data. Pull to try again"
                            holding.value -> "Refreshing"
                            else -> "Pull to refresh"
                        }
                    },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, pullPx.floatValue.roundToInt()) },
        ) {
            content()
        }
    }
}

private class PullConnection(
    private val pullPx: MutableFloatState,
    private val maxPx: Float,
    private val holding: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val busy: () -> Boolean,
    private val onArmed: () -> Unit,
    private val onRefresh: () -> Unit,
) : NestedScrollConnection {
    private var settleJob: Job? = null
    private val armed get() = pullPx.floatValue >= maxPx - 0.5f

    /** Close or open the band to [target] and wait for it. Drags are ignored while holding. */
    suspend fun settle(target: Float) {
        settleJob?.cancel()
        animateTo(target)
    }

    /** The same from a gesture callback; the next drag cancels it. */
    private fun settleAsync(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch { animateTo(target) }
    }

    private suspend fun animateTo(target: Float) {
        animate(pullPx.floatValue, target, animationSpec = tween(200)) { value, _ -> pullPx.floatValue = value }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Pushing back up closes the band before the list scrolls.
        if (holding.value || source != NestedScrollSource.UserInput || available.y >= 0f || pullPx.floatValue <= 0f) {
            return Offset.Zero
        }
        settleJob?.cancel()
        val next = (pullPx.floatValue + available.y).coerceAtLeast(0f)
        val consumed = next - pullPx.floatValue
        pullPx.floatValue = next
        return Offset(0f, consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // The list is at its top and the finger keeps going down: that is the pull.
        if (holding.value || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        settleJob?.cancel()
        val wasArmed = armed
        pullPx.floatValue = (pullPx.floatValue + available.y * PULL_RESISTANCE).coerceAtMost(maxPx)
        if (!wasArmed && armed) onArmed()
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (holding.value || pullPx.floatValue <= 0f) return Velocity.Zero
        if (armed && !busy()) {
            onRefresh()
            holding.value = true
            settleAsync(maxPx)
        } else {
            settleAsync(0f)
        }
        return available
    }
}

@Composable
private fun RefreshWord(
    word: String,
    fill: Color,
    progress: Float,
    sweeping: Boolean,
    letterSize: TextUnit,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    val count = word.length
    val phase = if (sweeping) {
        val transition = rememberInfiniteTransition(label = "refreshSweep")
        transition.animateFloat(
            initialValue = -SWEEP_WIDTH,
            targetValue = count - 1 + SWEEP_WIDTH,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "refreshSweepPhase",
        ).value
    } else {
        null
    }
    Row(
        modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        word.forEachIndexed { index, glyph ->
            RefreshGlyph(
                glyph = glyph.toString(),
                color = fill,
                fill = if (phase == null) refreshGlyphFill(progress, index, count) else 1f,
                glow = phase?.let { refreshSweepGlow(it, index) },
                letterSize = letterSize,
                modifier = Modifier.padding(horizontal = gap),
            )
        }
    }
}

/**
 * [fill] is how much of the glyph is [color], bottom up. [glow], while refreshing, mixes the
 * glyph from a dim [color] to full [color] as the sweep passes over it.
 */
@Composable
private fun RefreshGlyph(
    glyph: String,
    color: Color,
    fill: Float,
    glow: Float?,
    letterSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val style = FontWeight.Bold
    Box(modifier) {
        if (glow != null) {
            Text(
                glyph,
                color = lerp(color.copy(alpha = 0.35f), color, glow),
                fontFamily = FontFamily.Monospace,
                fontWeight = style,
                fontSize = letterSize,
            )
            return@Box
        }
        Text(
            glyph,
            color = TechColors.Edge,
            fontFamily = FontFamily.Monospace,
            fontWeight = style,
            fontSize = letterSize,
        )
        if (fill > 0f) {
            Text(
                glyph,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontWeight = style,
                fontSize = letterSize,
                modifier = Modifier.drawWithContent {
                    clipRect(top = size.height * (1f - fill)) {
                        this@drawWithContent.drawContent()
                    }
                },
            )
        }
    }
}
