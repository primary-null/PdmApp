package com.pdm.app.data.ops

import com.pdm.app.data.HostProbe
import com.pdm.app.data.RestartWatch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Where a planned restart of the PDM machine stands (Q1). One value drives header, banner and writes. */
enum class RestartPhase {
    /** The restart request is on its way. */
    SUBMITTING,

    /** Accepted; the machine still answers with its old uptime. */
    GOING_DOWN,

    /** It stopped answering (or answers without a status): it is restarting. */
    DOWN,

    /** It answered with a fresh boot; the app is reloading everything before going live. */
    RECOVERING,

    /** It is back, but the reload could not confirm fresh data; still read-only. */
    RECOVERY_FAILED,

    /** It did not come back in time. Still watched slowly; Retry looks again now. */
    TIMED_OUT,

    /** The server refused the restart. Nothing restarted. */
    REJECTED,

    /** Back and reloaded live. */
    DONE,
}

/** Why the last look did not settle it, when that is something the person can act on. */
enum class RestartBlocker { SIGN_IN, CERTIFICATE }

data class RestartProgress(
    val node: String,
    val phase: RestartPhase,
    /** Wall-clock time the request was sent (survives process restarts). */
    val submittedAt: Long,
    val reason: String? = null,
    val blocker: RestartBlocker? = null,
) {
    /** Writes stay blocked until the restart is settled one way or the other. */
    val blocksWrites: Boolean get() = phase != RestartPhase.DONE && phase != RestartPhase.REJECTED
    val settled: Boolean get() = phase == RestartPhase.DONE || phase == RestartPhase.REJECTED
}

/** What the request itself got back. */
sealed interface SubmitOutcome {
    data object Accepted : SubmitOutcome

    /** The server answered with an error: it did not restart. */
    data class Rejected(val reason: String) : SubmitOutcome

    /** The answer never arrived. It may have restarted; watch, never resend. */
    data object Unknown : SubmitOutcome
}

/** The full reload after the machine came back. */
sealed interface RecoveryLoad {
    /** Every read answered live, including the host's own status. */
    data object Fresh : RecoveryLoad

    data class Stale(val reason: String) : RecoveryLoad
}

/** The planned reboot's evidence, written to its receipt so a restored watch keeps it. */
data class RestartEvidence(
    val node: String,
    val submittedAt: Long,
    val baselineUptime: Long?,
    val sawDown: Boolean,
    val phase: RestartPhase,
)

data class RestartTimings(
    val firstLookMs: Long = 4_000,
    val probeEveryMs: Long = 3_000,
    val probeTimeoutMs: Long = 6_000,
    val waitMs: Long = 15 * 60_000L,
    /** After [waitMs]: keep looking, slowly, so a late return still recovers by itself. */
    val slowProbeEveryMs: Long = 30_000,
    val recoveryAttempts: Int = 3,
    val recoveryRetryMs: Long = 5_000,
)

/**
 * Restart one PDM machine and wait for it, or (after a process restart) only wait for it.
 * Everything runs in one coroutine, [run]; [probeNow] and [retry] just wake it, so a pull or a
 * header tap can never start a second watcher, a second reload or a second restart request.
 * The restart request is sent at most once, and only by [run] with a non-null submit.
 */
class RestartRecovery(
    private val node: String,
    private val probe: suspend () -> HostProbe,
    private val recoveryLoad: suspend () -> RecoveryLoad,
    private val persist: (RestartEvidence) -> Unit,
    private val wallClock: () -> Long,
    private val timings: RestartTimings = RestartTimings(),
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _progress = MutableStateFlow<RestartProgress?>(null)
    val progress: StateFlow<RestartProgress?> = _progress.asStateFlow()

    /** Look now instead of waiting for the next scheduled look (pull, header tap, Retry). */
    fun probeNow() {
        wake.trySend(Unit)
    }

    /**
     * Restart (when [submit] is given) and watch until settled. [evidence] restores a watch from
     * a receipt; then [submit] must be null: a restored restart is only observed.
     */
    suspend fun run(
        submit: (suspend () -> SubmitOutcome)?,
        baselineUptime: Long?,
        evidence: RestartEvidence? = null,
    ): RestartProgress {
        require(submit == null || evidence == null) { "A restored restart is never sent again" }
        val submittedAt = evidence?.submittedAt ?: wallClock()
        val baseline = evidence?.baselineUptime ?: baselineUptime
        var sawDown = evidence?.sawDown ?: false
        fun record(phase: RestartPhase) = persist(RestartEvidence(node, submittedAt, baseline, sawDown, phase))
        fun publish(phase: RestartPhase, reason: String? = null, blocker: RestartBlocker? = null): RestartProgress =
            RestartProgress(node, phase, submittedAt, reason, blocker).also { _progress.value = it }

        if (submit != null) {
            record(RestartPhase.SUBMITTING)
            publish(RestartPhase.SUBMITTING)
            when (val outcome = submit()) {
                is SubmitOutcome.Rejected -> {
                    record(RestartPhase.REJECTED)
                    return publish(RestartPhase.REJECTED, outcome.reason)
                }
                SubmitOutcome.Accepted -> Unit
                SubmitOutcome.Unknown -> Unit
            }
        }
        val watch = RestartWatch(baseline, { ((wallClock() - submittedAt) / 1000).coerceAtLeast(0) }, sawDown)
        record(if (sawDown) RestartPhase.DOWN else RestartPhase.GOING_DOWN)
        publish(if (sawDown) RestartPhase.DOWN else RestartPhase.GOING_DOWN)
        if (evidence == null) pause(timings.firstLookMs)
        val deadline = submittedAt + timings.waitMs
        var timedOut = false
        while (true) {
            val look = withTimeoutOrNull(timings.probeTimeoutMs) { probe() } ?: HostProbe.Silent
            val phase = watch.observe(look)
            if (watch.sawDown && !sawDown) {
                sawDown = true
                record(RestartPhase.DOWN)
            }
            val blocker = when (look) {
                HostProbe.SignInNeeded -> RestartBlocker.SIGN_IN
                HostProbe.CertificateRefused -> RestartBlocker.CERTIFICATE
                else -> null
            }
            if (phase == RestartWatch.Phase.BACK) {
                val settled = recover(::publish, ::record)
                if (settled != null) return settled
                // Recovery could not confirm fresh data: stay read-only, wait for Retry or the next look.
                pause(timings.slowProbeEveryMs)
                continue
            }
            if (!timedOut && wallClock() >= deadline) timedOut = true
            when {
                timedOut -> publish(RestartPhase.TIMED_OUT, blocker = blocker)
                phase == RestartWatch.Phase.DOWN -> publish(RestartPhase.DOWN, blocker = blocker)
                else -> publish(RestartPhase.GOING_DOWN, blocker = blocker)
            }
            pause(if (timedOut) timings.slowProbeEveryMs else timings.probeEveryMs)
        }
    }

    private suspend fun recover(
        publish: (RestartPhase, String?, RestartBlocker?) -> RestartProgress,
        record: (RestartPhase) -> Unit,
    ): RestartProgress? {
        record(RestartPhase.RECOVERING)
        var reason: String? = null
        repeat(timings.recoveryAttempts) { attempt ->
            publish(RestartPhase.RECOVERING, null, null)
            when (val load = recoveryLoad()) {
                RecoveryLoad.Fresh -> {
                    record(RestartPhase.DONE)
                    return publish(RestartPhase.DONE, null, null)
                }
                is RecoveryLoad.Stale -> reason = load.reason
            }
            if (attempt < timings.recoveryAttempts - 1) pause(timings.recoveryRetryMs)
        }
        record(RestartPhase.RECOVERY_FAILED)
        publish(RestartPhase.RECOVERY_FAILED, reason, null)
        return null
    }

    /** Wait [ms], or less when woken. */
    private suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }
}
