package com.pdm.app.ui.home

import kotlin.math.abs

internal const val REFRESH_WORD = "REFRESH"
internal const val OFFLINE_WORD = "OFFLINE"

/**
 * What a pull shows. [refusal]: the screen holds saved data, so the word is OFFLINE and a pull
 * that still cannot reach the server ends in a shake instead of a quiet close. [failing]: the
 * server is not answering (red), as opposed to cached data on the way to live (gray).
 */
internal data class PullLook(val word: String, val refusal: Boolean, val failing: Boolean)

internal val LIVE_PULL = PullLook(REFRESH_WORD, refusal = false, failing = false)

internal fun pullLook(state: com.pdm.app.ui.FleetUiState): PullLook {
    val restart = state.hostRestart
    if (restart != null && !restart.settled) {
        // A pull during a restart only makes the watcher look now; the word says what it waits for.
        val words = restartWords(restart, 0)
        return PullLook(words.pullWord, refusal = false, failing = words.problem)
    }
    val failing = state.sync.failures > 0 || state.sync.paused
    return if (state.readOnly || failing) PullLook(OFFLINE_WORD, refusal = true, failing = failing) else LIVE_PULL
}

/** Half-width of the lit window that sweeps over the word while a refresh runs, in glyphs. */
internal const val SWEEP_WIDTH = 1.5f

/**
 * How full glyph [index] is for a 0..1 pull.
 * Glyphs fill in order, each taking an equal slice of the gesture.
 */
internal fun refreshGlyphFill(progress: Float, index: Int, count: Int = REFRESH_WORD.length): Float {
    if (count <= 0 || index !in 0 until count) return 0f
    val span = 1f / count
    return ((progress.coerceIn(0f, 1f) - index * span) / span).coerceIn(0f, 1f)
}

/** Glyphs completely filled at [progress]: the pull ticks once as each one completes. */
internal fun refreshGlyphsFilled(progress: Float, count: Int = REFRESH_WORD.length): Int {
    if (count <= 0) return 0
    // A hair of slack so float error at exactly k/count still counts letter k as full.
    return ((progress.coerceIn(0f, 1f) + 1e-4f) * count).toInt().coerceAtMost(count)
}

/** 1 for the glyph under the sweep at [phase] (a glyph index, may be fractional), 0 outside it. */
internal fun refreshSweepGlow(phase: Float, index: Int): Float =
    (1f - abs(phase - index) / SWEEP_WIDTH).coerceIn(0f, 1f)
