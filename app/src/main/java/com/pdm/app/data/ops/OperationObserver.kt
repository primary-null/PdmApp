package com.pdm.app.data.ops

import com.pdm.app.data.model.GuestAction
import com.pdm.app.data.model.OperationKind
import com.pdm.app.data.model.OperationReceipt
import com.pdm.app.data.model.OperationSubmissionState
import com.pdm.app.data.model.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * How long and how often an accepted operation is watched. Quick at first (most guest actions end
 * in seconds), then slower, then the app stops watching and says so; the task itself goes on.
 */
data class ObservePolicy(
    val fastEveryMs: Long = 1_000,
    val fastForMs: Long = 15_000,
    val slowEveryMs: Long = 3_000,
    val maxWatchMs: Long = 10 * 60_000L,
    val maxConsecutiveFailures: Int = 5,
)

/**
 * Follows one operation's task until it ends, using live status reads only (never the offline
 * cache, never a fabricated "OK" for a missing answer). Losing sight of the task pauses the watch;
 * it never marks the operation failed and never sends anything again.
 */
class OperationObserver(
    /** Live task status for the receipt's task; may throw. Null data is "no answer", not success. */
    private val fetch: suspend (OperationReceipt) -> TaskStatus?,
    /** Persist a change to the receipt; returns the stored receipt, null once it is gone. */
    private val update: (String, (OperationReceipt) -> OperationReceipt) -> OperationReceipt?,
    private val wallClock: () -> Long,
    /** Sign-in, certificate or session errors: stop at once and leave the receipt for later. */
    private val isFatal: (Exception) -> Boolean,
    private val policy: ObservePolicy = ObservePolicy(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** Watch until the task ends or watching pauses; returns the receipt as last stored. */
    suspend fun observe(receipt: OperationReceipt): OperationReceipt {
        if (receipt.upid.isNullOrBlank() || receipt.isTerminal) return receipt
        val started = wallClock()
        var failures = 0
        var current = receipt
        while (true) {
            val status = try {
                fetch(current)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isFatal(e)) throw e
                null.also { failures++ }
            }
            if (status != null) {
                failures = 0
                val at = wallClock()
                current = update(current.id) { r ->
                    if (status.isRunning) {
                        r.copy(state = OperationSubmissionState.RUNNING, lastObservedAt = at, error = null)
                    } else {
                        val ok = status.isOk || status.exitstatus.orEmpty().startsWith("WARNINGS", ignoreCase = true)
                        r.copy(
                            state = if (ok) OperationSubmissionState.SUCCEEDED else OperationSubmissionState.FAILED,
                            lastObservedAt = at,
                            error = if (ok) null else status.exitstatus?.takeIf { it.isNotBlank() } ?: "The task ended without a result.",
                        )
                    }
                } ?: return current
                if (current.isTerminal) return current
            } else if (failures == 0) {
                // An answer without a status: as good as no answer.
                failures++
            }
            val watched = wallClock() - started
            if (failures >= policy.maxConsecutiveFailures || watched >= policy.maxWatchMs) {
                return update(current.id) { it.copy(state = OperationSubmissionState.OBSERVATION_PAUSED) } ?: current
            }
            sleep(if (watched < policy.fastForMs) policy.fastEveryMs else policy.slowEveryMs)
        }
    }
}

/**
 * A request whose answer was lost: look for its task in the server's own task list. Found means
 * accepted (and now observable); not found is no proof either way, so it stays unknown.
 */
object OperationReconcile {
    /** Worker types Proxmox uses for each operation, to recognise its task. */
    fun workerTypes(receipt: OperationReceipt): Set<String> {
        val lxc = receipt.guestType == "lxc"
        return when (receipt.kind) {
            OperationKind.power(GuestAction.START) -> setOf(if (lxc) "vzstart" else "qmstart")
            OperationKind.power(GuestAction.STOP) -> setOf(if (lxc) "vzstop" else "qmstop")
            OperationKind.power(GuestAction.SHUTDOWN) -> setOf(if (lxc) "vzshutdown" else "qmshutdown")
            OperationKind.power(GuestAction.RESUME) -> setOf("qmresume")
            OperationKind.SNAPSHOT_CREATE -> setOf(if (lxc) "vzsnapshot" else "qmsnapshot")
            OperationKind.SNAPSHOT_ROLLBACK -> setOf(if (lxc) "vzrollback" else "qmrollback")
            OperationKind.SNAPSHOT_DELETE -> setOf(if (lxc) "vzdelsnapshot" else "qmdelsnapshot")
            OperationKind.MIGRATE -> setOf(if (lxc) "vzmigrate" else "qmigrate", "remote-migrate")
            OperationKind.HOST_APT_REFRESH -> setOf("aptupdate")
            else -> emptySet()
        }
    }

    data class Candidate(val upid: String, val workerType: String, val workerId: String?, val startTime: Long, val node: String?)

    /**
     * The task in [tasks] that is this receipt's: same worker type, same guest, same node when
     * known, and started no earlier than [SLACK_S] seconds before the request was sent. More than
     * one match is ambiguous and yields none.
     */
    fun match(receipt: OperationReceipt, tasks: List<Candidate>): String? {
        val types = workerTypes(receipt)
        if (types.isEmpty()) return null
        val since = receipt.submittedAt / 1000 - SLACK_S
        val hits = tasks.filter { t ->
            t.workerType in types &&
                t.startTime >= since &&
                (receipt.vmid == null || t.workerId == receipt.vmid.toString()) &&
                (receipt.node == null || t.node == null || t.node.equals(receipt.node, ignoreCase = true))
        }
        return hits.singleOrNull()?.upid
    }

    private const val SLACK_S = 10L
}
