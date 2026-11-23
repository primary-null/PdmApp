package com.pdm.app.ui

import com.pdm.app.data.repo.LoadDepth

/** Why a fleet load was asked for. Only [PULL] holds the pull band open. */
enum class RefreshReason { AUTO, PULL, ACTION, TIMEFRAME }

/**
 * Where the fleet data stands. Times are `SystemClock.elapsedRealtime()` ms so a wall-clock change
 * cannot make data look fresh or stale.
 */
data class SyncState(
    val running: Boolean = false,
    /** The running load was pulled by hand; the pull band stays open until it lands. */
    val manual: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastFullAt: Long? = null,
    /** When the last load ended, good or bad. The next automatic load counts from here. */
    val lastAttemptAt: Long? = null,
    /** Loads that failed in a row; drives the backoff. Any success resets it. */
    val failures: Int = 0,
    /**
     * Wall-clock save time (epoch ms) of the oldest cached answer on screen; null when what is shown
     * came live. Wall clock because the cache outlives reboots.
     */
    val cachedAt: Long? = null,
    /** Automatic tries stopped: reconnecting needs a person (rejected credential, TOTP, new certificate). */
    val paused: Boolean = false,
)

/**
 * Header chip states. Green: [LIVE]. Gray: not live but nothing is wrong ([LOADING], [AGING],
 * [STALE], [CACHED]). Red: [FAILING], the server cannot be reached or refused the saved login.
 */
/** What a journal line is: a load starting, how a load ended, or something worth noticing. */
enum class NoteKind { TRIGGER, RESULT, EVENT }

/** How a line should read: live (good), saved data (muted), failed (bad). */
enum class NoteTone { NEUTRAL, GOOD, MUTED, BAD }

/** One line of the LIVE panel's decision journal. [at] is wall-clock ms. */
data class SyncNote(
    val at: Long,
    val text: String,
    val kind: NoteKind = NoteKind.EVENT,
    val tone: NoteTone = NoteTone.NEUTRAL,
)

enum class Freshness { LOADING, LIVE, AGING, STALE, CACHED, FAILING }

/**
 * Cadence for the fleet view. The quick tier (inventory, health, leaders, tasks, host load) runs
 * every [INTERVAL_MS]; the slow tier (permissions, subscriptions, update summary, task statistics,
 * host packages) only on a full load: sign-in, a pull, or once [FULL_EVERY_MS] has passed.
 */
internal object RefreshPolicy {
    const val INTERVAL_MS = 30_000L
    const val FULL_EVERY_MS = 5 * 60_000L
    const val MAX_BACKOFF_MS = 5 * 60_000L

    /** 30 s, then 1, 2, 4 and at most 5 min between automatic tries after failures. */
    fun interval(failures: Int): Long {
        if (failures <= 0) return INTERVAL_MS
        val shift = failures.coerceAtMost(8)
        return (INTERVAL_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** How long until the next automatic load is due; 0 or less means now. */
    fun dueIn(sync: SyncState, now: Long): Long {
        if (sync.paused) return Long.MAX_VALUE
        val from = sync.lastAttemptAt ?: return 0L
        return from + interval(sync.failures) - now
    }

    fun depth(reason: RefreshReason, sync: SyncState, now: Long): LoadDepth = when {
        reason == RefreshReason.PULL -> LoadDepth.FULL
        sync.lastFullAt == null -> LoadDepth.FULL
        now - sync.lastFullAt >= FULL_EVERY_MS -> LoadDepth.FULL
        else -> LoadDepth.QUICK
    }

    fun freshness(sync: SyncState, now: Long): Freshness {
        val success = sync.lastSuccessAt
        return when {
            sync.failures > 0 || sync.paused -> Freshness.FAILING
            sync.cachedAt != null -> Freshness.CACHED
            success == null -> Freshness.LOADING
            now - success < INTERVAL_MS * 2 -> Freshness.LIVE
            now - success < FULL_EVERY_MS -> Freshness.AGING
            else -> Freshness.STALE
        }
    }

    /** `12s`, `4m`, `2h`: the age of the data, short enough for the header. */
    fun age(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            else -> "${s / 86_400}d"
        }
    }
}
