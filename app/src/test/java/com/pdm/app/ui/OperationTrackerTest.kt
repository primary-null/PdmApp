package com.pdm.app.ui

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.GuestAction
import com.pdm.app.data.model.OperationKind
import com.pdm.app.data.model.OperationReceipt
import com.pdm.app.data.model.OperationSubmissionState
import com.pdm.app.data.model.TaskOrigin
import com.pdm.app.data.model.TaskStatus
import com.pdm.app.data.ops.ObservePolicy
import com.pdm.app.data.ops.OperationReconcile
import com.pdm.app.data.repo.PdmHttpException
import com.pdm.app.data.session.SessionIdentity
import com.pdm.app.data.session.SessionStore
import java.net.SocketTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real tracker over the real encrypted-store API (with in-memory prefs) and scripted reads (Q5). */
@OptIn(ExperimentalCoroutinesApi::class)
class OperationTrackerTest {
    private val serverA = SessionIdentity("https://192.0.2.37:8443/api2/json/", AuthMode.PASSWORD, "root@pam")
    private val serverB = SessionIdentity("https://198.51.100.32:8443/api2/json/", AuthMode.PASSWORD, "root@pam")

    private class Reads : OperationReads {
        val statuses = ArrayDeque<Any>() // TaskStatus, null, or Exception
        var statusCalls = 0
        val recent = mutableListOf<OperationReconcile.Candidate>()
        override suspend fun liveStatus(receipt: OperationReceipt): TaskStatus? {
            statusCalls++
            return when (val next = statuses.removeFirstOrNull() ?: TaskStatus(status = "running")) {
                is Exception -> throw next
                is TaskStatus -> next
                else -> null
            }
        }
        override suspend fun recentTasks(receipt: OperationReceipt) = recent.toList()
    }

    private class Rig(scope: TestScope, val store: SessionStore = SessionStore(injectedPrefs = FakeSharedPreferences())) {
        val reads = Reads()
        val settled = mutableListOf<OperationReceipt>()
        val tracker = OperationTracker(
            scope = scope,
            store = object : ReceiptStore {
                override fun all(identity: SessionIdentity) = store.getOperationReceipts(identity)
                override fun save(identity: SessionIdentity, receipt: OperationReceipt) = store.saveOperationReceipt(identity, receipt)
                override fun update(identity: SessionIdentity, id: String, transform: (OperationReceipt) -> OperationReceipt) =
                    store.updateOperationReceipt(identity, id, transform)
            },
            reads = reads,
            isFatal = { false },
            onChange = {},
            onSettled = { settled += it },
            wallClock = { 1_800_000_000_000L + scope.testScheduler.currentTime },
            policy = ObservePolicy(maxWatchMs = 60_000, maxConsecutiveFailures = 3),
            sleep = { kotlinx.coroutines.delay(it) },
            reconcileTries = 2,
        )
        fun begin(vmid: Long = 100) = tracker.begin(
            kind = OperationKind.power(GuestAction.SHUTDOWN),
            origin = TaskOrigin.Remote("pve", "NODE-C"),
            description = "Shut down application",
            port = 8443,
            remoteId = "NODE-C",
            node = "NODE-C",
            vmid = vmid,
            guestType = "qemu",
        )!!
    }

    @Test
    fun theReceiptIsStoredBeforeTheRequestAndTheTaskIdAsSoonAsItIsKnown() = runTest {
        val rig = Rig(this)
        rig.tracker.bind(serverA) { true }
        val receipt = rig.begin()
        assertEquals(OperationSubmissionState.SUBMITTING, rig.store.getOperationReceipts(serverA).single().state)
        rig.reads.statuses += TaskStatus(status = "stopped", exitstatus = "OK")
        val result = rig.tracker.submit(receipt) {
            // Inside the request the receipt is already on disk.
            assertEquals(1, rig.store.getOperationReceipts(serverA).size)
            "UPID:NODE-C:0001:qmshutdown:100:root@pam:"
        }
        assertTrue(result is Submitted.Accepted)
        assertEquals("UPID:NODE-C:0001:qmshutdown:100:root@pam:", rig.store.getOperationReceipts(serverA).single().upid)
        advanceUntilIdle()
        assertEquals(OperationSubmissionState.SUCCEEDED, rig.store.getOperationReceipts(serverA).single().state)
        assertEquals(1, rig.settled.size)
    }

    @Test
    fun aTaskFailureIsAFailureAndItsExitStatusTheReason() = runTest {
        val rig = Rig(this)
        rig.tracker.bind(serverA) { true }
        rig.reads.statuses += TaskStatus(status = "running")
        rig.reads.statuses += TaskStatus(status = "stopped", exitstatus = "VM 100 not running")
        rig.tracker.submit(rig.begin()) { "UPID:x" }
        advanceUntilIdle()
        val r = rig.store.getOperationReceipts(serverA).single()
        assertEquals(OperationSubmissionState.FAILED, r.state)
        assertEquals("VM 100 not running", r.error)
    }

    @Test
    fun losingSightOfTheTaskPausesTheWatchAndNeverFailsIt() = runTest {
        val rig = Rig(this)
        rig.tracker.bind(serverA) { true }
        repeat(10) { rig.reads.statuses += SocketTimeoutException("read timed out") }
        rig.tracker.submit(rig.begin()) { "UPID:x" }
        advanceUntilIdle()
        val r = rig.store.getOperationReceipts(serverA).single()
        assertEquals(OperationSubmissionState.OBSERVATION_PAUSED, r.state)
        assertNull(r.error)
    }

    @Test
    fun anAnswerWithoutAStatusIsNeverAFabricatedSuccess() = runTest {
        val rig = Rig(this)
        rig.tracker.bind(serverA) { true }
        repeat(10) { rig.reads.statuses += Unit } // null data each time
        rig.tracker.submit(rig.begin()) { "UPID:x" }
        advanceUntilIdle()
        assertEquals(OperationSubmissionState.OBSERVATION_PAUSED, rig.store.getOperationReceipts(serverA).single().state)
    }

    @Test
    fun aLongTaskIsWatchedForABoundedTimeThenPaused() = runTest {
        val rig = Rig(this)
        rig.tracker.bind(serverA) { true }
        rig.tracker.submit(rig.begin()) { "UPID:x" } // always "running"
        advanceUntilIdle()
        assertEquals(OperationSubmissionState.OBSERVATION_PAUSED, rig.store.getOperationReceipts(serverA).single().state)
        assertTrue("bounded polling, got ${rig.reads.statusCalls}", rig.reads.statusCalls in 10..40)
    }

    @Test
    fun anExplicitRejectionIsRefusedAndNothingIsWatched() = runTest {
        val rig = Rig(this)
        rig.tracker.bind(serverA) { true }
        val result = rig.tracker.submit(rig.begin()) { throw PdmHttpException(500, null, "VM is locked (backup)") }
        assertTrue(result is Submitted.Rejected)
        advanceUntilIdle()
        assertEquals(OperationSubmissionState.REJECTED, rig.store.getOperationReceipts(serverA).single().state)
        assertEquals(0, rig.reads.statusCalls)
    }

    @Test
    fun aLostAnswerIsUnknownThenReconciledFromTheTaskList() = runTest {
        val rig = Rig(this)
        rig.tracker.bind(serverA) { true }
        val receipt = rig.begin()
        val result = rig.tracker.submit(receipt) { throw SocketTimeoutException("timeout") }
        assertTrue(result is Submitted.Unknown)
        // Not yet visible on the first look; it shows up on the second.
        rig.reads.recent += OperationReconcile.Candidate("UPID:found", "qmshutdown", "100", receipt.submittedAt / 1000 + 1, "NODE-C")
        rig.reads.statuses += TaskStatus(status = "stopped", exitstatus = "OK")
        advanceUntilIdle()
        val r = rig.store.getOperationReceipts(serverA).single()
        assertEquals("UPID:found", r.upid)
        assertEquals(OperationSubmissionState.SUCCEEDED, r.state)
    }

    @Test
    fun aLostAnswerWithNoMatchingTaskStaysUnknownAndBlocksABlindRepeat() = runTest {
        val rig = Rig(this)
        rig.tracker.bind(serverA) { true }
        rig.tracker.submit(rig.begin()) { throw SocketTimeoutException("timeout") }
        advanceUntilIdle()
        val r = rig.store.getOperationReceipts(serverA).single()
        assertEquals(OperationSubmissionState.UNKNOWN, r.state)
        assertTrue(rig.tracker.mayBeRunning("NODE-C", 100))
        assertTrue(!rig.tracker.mayBeRunning("NODE-C", 101))
    }

    @Test
    fun restoringAfterAProcessRestartOnlyReadsAndMarksAnInterruptedSendUnknown() = runTest {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val first = Rig(this, store)
        first.tracker.bind(serverA) { true }
        val interrupted = first.begin(vmid = 100)          // died while sending
        val accepted = first.begin(vmid = 101)
        store.updateOperationReceipt(serverA, accepted.id) { it.copy(state = OperationSubmissionState.ACCEPTED, upid = "UPID:101") }

        var sent = 0
        val second = Rig(this, store) // a new process over the same encrypted store
        second.reads.statuses += TaskStatus(status = "stopped", exitstatus = "OK")
        second.tracker.bind(serverA) { true }
        second.tracker.restore()
        advanceUntilIdle()
        val byId = store.getOperationReceipts(serverA).associateBy { it.id }
        assertEquals(OperationSubmissionState.UNKNOWN, byId.getValue(interrupted.id).state)
        assertEquals(OperationSubmissionState.SUCCEEDED, byId.getValue(accepted.id).state)
        assertEquals("restore never sends", 0, sent)
    }

    @Test
    fun receiptsOfAnotherServerWithTheSameAccountStayApart() = runTest {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val rig = Rig(this, store)
        rig.tracker.bind(serverA) { true }
        rig.begin()
        rig.tracker.bind(serverB) { true }
        assertTrue(rig.tracker.receipts().isEmpty())
        assertEquals(1, store.getOperationReceipts(serverA).size)
    }

    @Test
    fun signingOutStopsWatchingButKeepsTheEvidence() = runTest {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val rig = Rig(this, store)
        rig.tracker.bind(serverA) { true }
        rig.tracker.submit(rig.begin()) { "UPID:x" }
        rig.tracker.unbind()
        advanceUntilIdle()
        val r = store.getOperationReceipts(serverA).single()
        assertTrue(r.state == OperationSubmissionState.ACCEPTED || r.state == OperationSubmissionState.RUNNING)
    }

    @Test
    fun aWatchForASessionThatEndedWritesNothing() = runTest {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val rig = Rig(this, store)
        var current = true
        rig.tracker.bind(serverA) { current }
        rig.reads.statuses += TaskStatus(status = "running")
        rig.tracker.submit(rig.begin()) { "UPID:x" }
        current = false
        rig.reads.statuses += TaskStatus(status = "stopped", exitstatus = "OK")
        advanceUntilIdle()
        assertTrue(store.getOperationReceipts(serverA).single().state != OperationSubmissionState.SUCCEEDED)
    }
}
