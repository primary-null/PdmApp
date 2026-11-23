package com.pdm.app.ui.home

import com.pdm.app.data.ops.RestartBlocker
import com.pdm.app.data.ops.RestartPhase
import com.pdm.app.data.ops.RestartProgress

/**
 * Everything the screen says about a restart, from one place, so the header chip, its spoken
 * label, the banner and the pull word always agree (Q1). [problem] is red; otherwise amber/grey.
 */
internal data class RestartWords(
    val chip: String,
    val spoken: String,
    val banner: String,
    val pullWord: String,
    val problem: Boolean,
    /** Offer Retry (a read: look again now). */
    val retry: Boolean,
)

internal fun restartWords(p: RestartProgress, elapsedSeconds: Long): RestartWords {
    val name = p.node
    val clock = "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    val blocker = when (p.blocker) {
        RestartBlocker.SIGN_IN -> " Sign in again to finish reconnecting."
        RestartBlocker.CERTIFICATE -> " Its certificate changed; review it to continue."
        null -> ""
    }
    return when (p.phase) {
        RestartPhase.SUBMITTING -> RestartWords(
            "RESTARTING", "Asking $name to restart",
            "Asking $name to restart. Changes are paused.", "RESTARTING", problem = false, retry = false,
        )
        RestartPhase.GOING_DOWN -> RestartWords(
            "RESTARTING", "$name is restarting",
            "${name.uppercase()} IS GOING DOWN · $clock · showing the last data. Changes are paused.$blocker",
            "RESTARTING", problem = false, retry = false,
        )
        RestartPhase.DOWN -> RestartWords(
            "WAITING FOR SERVER", "$name is restarting; waiting for it to come back",
            "${name.uppercase()} IS RESTARTING · $clock · showing the last data. Reconnects by itself when it's back.$blocker",
            "WAITING", problem = false, retry = false,
        )
        RestartPhase.RECOVERING -> RestartWords(
            "RECONNECTING", "$name is back; reloading",
            "${name.uppercase()} IS BACK · reloading everything before changes are allowed again.",
            "RECONNECTING", problem = false, retry = false,
        )
        RestartPhase.RECOVERY_FAILED -> RestartWords(
            "NOT CONFIRMED", "$name is back, but fresh data didn't load",
            "$name is back, but ${p.reason?.trimEnd('.')?.replaceFirstChar { it.lowercase() } ?: "fresh data didn't load"}. Showing the last data; changes stay paused.",
            "RETRY", problem = true, retry = true,
        )
        RestartPhase.TIMED_OUT -> RestartWords(
            "NOT BACK YET", "$name hasn't come back",
            "$name hasn't come back after $clock. Still checking every 30 seconds; check the machine if it stays down.$blocker",
            "RETRY", problem = true, retry = true,
        )
        RestartPhase.REJECTED -> RestartWords(
            "LIVE", "$name refused the restart",
            "$name didn't restart: ${p.reason.orEmpty()}", "REFRESH", problem = true, retry = false,
        )
        RestartPhase.DONE -> RestartWords("LIVE", "$name is back", "$name is back.", "REFRESH", problem = false, retry = false)
    }
}
