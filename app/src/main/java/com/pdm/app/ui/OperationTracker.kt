package com.pdm.app.ui

import com.pdm.app.data.model.OperationKind
import com.pdm.app.data.model.OperationReceipt
import com.pdm.app.data.model.OperationSubmissionState
import com.pdm.app.data.model.RemoteTask
import com.pdm.app.data.model.TaskOrigin
import com.pdm.app.data.model.TaskStatus
import com.pdm.app.data.ops.ObservePolicy
import com.pdm.app.data.ops.OperationObserver
import com.pdm.app.data.ops.OperationReconcile
import com.pdm.app.data.ops.SubmitFailure
import com.pdm.app.data.ops.Submission
import com.pdm.app.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where receipts live: the encrypted session store, scoped to one connection identity. */
interface ReceiptStore {
    fun all(identity: SessionIdentity): List<OperationReceipt>
    fun save(identity: SessionIdentity, receipt: OperationReceipt)
    fun update(identity: SessionIdentity, id: String, transform: (OperationReceipt) -> OperationReceipt)
}

/** The reads the tracker needs: live task status, and recent tasks to reconcile a lost answer. */
interface OperationReads {
    suspend fun liveStatus(receipt: OperationReceipt): TaskStatus?
    suspend fun recentTasks(receipt: OperationReceipt): List<OperationReconcile.Candidate>
}

/** What became of a submission, for the screen that sent it. */
sealed interface Submitted {
    /** Accepted; [upid] is its task (null when the server started none). */
    data class Accepted(val receipt: OperationReceipt, val upid: String?) : Submitted

    data class Rejected(val receipt: OperationReceipt, val reason: String) : Submitted

    /** The answer was lost; the tracker is looking for the task. */
    data class Unknown(val receipt: OperationReceipt) : Submitted

    /** Sign-in/certificate/session trouble; [error] goes to the normal sign-in path. */
    data class Fatal(val error: Exception) : Submitted
}

/**
 * Operations the app sent, for one signed-in session (Q5). Records a receipt before each request,
 * keeps it through the request and its task, and after a process restart only looks again: it
 * never sends an operation twice. One observation job per receipt; all of them stop with the
 * session ([bind] / [unbind]).
 */
class OperationTracker(
    private val scope: CoroutineScope,
    private val store: ReceiptStore,
    private val reads: OperationReads,
    private val isFatal: (Exception) -> Boolean,
    private val onChange: (List<OperationReceipt>) -> Unit,
    private val onSettled: (OperationReceipt) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val policy: ObservePolicy = ObservePolicy(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val reconcileTries: Int = 4,
    private val reconcileEveryMs: Long = 5_000,
) {
    private var identity: SessionIdentity? = null
    private var isCurrent: () -> Boolean = { false }
    private val jobs = mutableMapOf<String, Job>()

    /** Follow receipts of [identity] while [current] says the session is still the one signed in. */
    fun bind(identity: SessionIdentity, current: () -> Boolean) {
        if (this.identity != identity) unbind()
        this.identity = identity
        isCurrent = current
        publish()
    }

    /** Stop watching (sign-out, switch). Receipts stay stored for the next sign-in to this server. */
    fun unbind() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        identity = null
        isCurrent = { false }
        onChange(emptyList())
    }

    fun receipts(): List<OperationReceipt> = identity?.let(store::all).orEmpty()

    /** Written before the request goes out, so a crash mid-request still leaves evidence. */
    fun begin(
        kind: String,
        origin: TaskOrigin?,
        description: String,
        port: Int,
        remoteId: String? = null,
        node: String? = null,
        vmid: Long? = null,
        guestType: String? = null,
    ): OperationReceipt? {
        val id = identity ?: return null
        val receipt = OperationReceipt(
            id = java.util.UUID.randomUUID().toString(),
            kind = kind,
            targetEndpoint = id.endpoint,
            targetPort = port,
            authMode = id.authMode.name,
            account = id.account,
            origin = origin,
            remoteId = remoteId,
            node = node,
            vmid = vmid,
            guestType = guestType,
            state = OperationSubmissionState.SUBMITTING,
            submittedAt = wallClock(),
            description = description,
        )
        store.save(id, receipt)
        publish()
        return receipt
    }

    /** Send [request] for [receipt] once, then follow it. Never retried here. */
    suspend fun submit(receipt: OperationReceipt, request: suspend () -> String?): Submitted {
        val outcome = try {
            val upid = request()
            change(receipt.id) { it.copy(state = OperationSubmissionState.ACCEPTED, upid = upid, lastObservedAt = wallClock()) }
            if (upid == null) {
                // Accepted without a task: nothing further to watch.
                change(receipt.id) { it.copy(state = OperationSubmissionState.SUCCEEDED) }
            }
            Submitted.Accepted(current(receipt.id) ?: receipt, upid)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when (val failure = Submission.classify(e)) {
                is SubmitFailure.Rejected -> {
                    change(receipt.id) { it.copy(state = OperationSubmissionState.REJECTED, error = failure.reason) }
                    Submitted.Rejected(current(receipt.id) ?: receipt, failure.reason)
                }
                is SubmitFailure.NotSent -> {
                    val reason = "Nothing was sent: ${failure.reason}"
                    change(receipt.id) { it.copy(state = OperationSubmissionState.REJECTED, error = reason) }
                    Submitted.Rejected(current(receipt.id) ?: receipt, reason)
                }
                SubmitFailure.Unknown -> {
                    change(receipt.id) {
                        it.copy(state = OperationSubmissionState.UNKNOWN, error = "The answer was lost. It may have started.")
                    }
                    Submitted.Unknown(current(receipt.id) ?: receipt)
                }
                SubmitFailure.Fatal -> {
                    // The server refused the sign-in, so it did not act on the request.
                    change(receipt.id) {
                        it.copy(state = OperationSubmissionState.REJECTED, error = "Sign in again, then check before retrying.")
                    }
                    Submitted.Fatal(e)
                }
            }
        }
        current(receipt.id)?.let { watch(it) }
        return outcome
    }

    /** Look again at one receipt (the person asked, or after a restore). Reads only. */
    fun watch(receipt: OperationReceipt) {
        if (receipt.isTerminal || identity == null) return
        if (jobs[receipt.id]?.isActive == true) return
        if (receipt.kind == OperationKind.REBOOT) return // the restart controller owns reboots
        jobs[receipt.id] = scope.launch {
            try {
                var target = receipt
                if (target.upid.isNullOrBlank()) target = reconcile(target) ?: return@launch
                val observer = OperationObserver(
                    fetch = { if (!isCurrent()) throw CancellationException("session changed") else reads.liveStatus(it) },
                    update = { id, transform -> if (isCurrent()) change(id, transform) else null },
                    wallClock = wallClock,
                    isFatal = isFatal,
                    policy = policy,
                    sleep = sleep,
                )
                val last = observer.observe(target)
                if (isCurrent()) onSettled(last)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Sign-in trouble mid-watch: the receipt stays for the next sign-in.
            } finally {
                jobs.remove(receipt.id)
            }
        }
    }

    /** After sign-in or a process restart: look again at everything unresolved. Never resends. */
    fun restore() {
        receipts().filterNot { it.isTerminal || it.kind == OperationKind.REBOOT }.forEach { r ->
            if (r.state == OperationSubmissionState.SUBMITTING) {
                // The app stopped mid-request: the outcome is unknown, not "still sending".
                change(r.id) { it.copy(state = OperationSubmissionState.UNKNOWN, error = "The app closed before the answer arrived.") }
            }
            current(r.id)?.let { watch(it) }
        }
    }

    /** True while an operation of [kind] on this guest may still be running or its outcome is unknown. */
    fun mayBeRunning(remoteId: String, vmid: Long, kind: String? = null): Boolean =
        receipts().any {
            it.mayBeActive && it.remoteId == remoteId && it.vmid == vmid && (kind == null || it.kind == kind)
        }

    private suspend fun reconcile(receipt: OperationReceipt): OperationReceipt? {
        repeat(reconcileTries) { attempt ->
            if (!isCurrent()) return null
            val found = try {
                OperationReconcile.match(receipt, reads.recentTasks(receipt))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isFatal(e)) throw e
                null
            }
            if (found != null) {
                change(receipt.id) { it.copy(state = OperationSubmissionState.ACCEPTED, upid = found, error = null) }
                return current(receipt.id)
            }
            if (attempt < reconcileTries - 1) sleep(reconcileEveryMs)
        }
        change(receipt.id) {
            it.copy(error = "No task for it showed up. Check the guest before trying again.")
        }
        return null
    }

    private fun current(id: String): OperationReceipt? = receipts().firstOrNull { it.id == id }

    private fun change(id: String, transform: (OperationReceipt) -> OperationReceipt): OperationReceipt? {
        val who = identity ?: return null
        store.update(who, id, transform)
        publish()
        return current(id)
    }

    private fun publish() = onChange(receipts())

    companion object {
        /** The task a receipt is about, for the task detail screen. */
        fun taskOf(receipt: OperationReceipt): RemoteTask? {
            val upid = receipt.upid?.takeIf { it.isNotBlank() } ?: return null
            val origin = receipt.origin ?: return null
            return RemoteTask(
                upid = upid,
                remoteId = (origin as? TaskOrigin.Remote)?.remoteId.orEmpty(),
                node = (origin as? TaskOrigin.Host)?.node ?: receipt.node.orEmpty(),
                workerType = receipt.kind,
                workerId = receipt.vmid?.toString(),
                user = receipt.account,
                startTime = receipt.submittedAt / 1000,
                status = when (receipt.state) {
                    OperationSubmissionState.SUCCEEDED -> "OK"
                    OperationSubmissionState.FAILED -> receipt.error ?: "error"
                    else -> null
                },
                origin = origin,
            )
        }
    }
}
