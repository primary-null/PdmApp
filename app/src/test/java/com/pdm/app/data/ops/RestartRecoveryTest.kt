package com.pdm.app.data.ops

import com.pdm.app.data.HostProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real restart controller, driven by scripted probes in virtual time (Q1). */
@OptIn(ExperimentalCoroutinesApi::class)
class RestartRecoveryTest {
    private class Rig(scope: TestScope, val looks: ArrayDeque<HostProbe>, val loads: ArrayDeque<RecoveryLoad>) {
        var submits = 0
        var probes = 0
        var recoveryLoads = 0
        val evidence = mutableListOf<RestartEvidence>()
        // Wall clock follows virtual time, starting at a realistic epoch.
        val start = 1_800_000_000_000L
        val recovery = RestartRecovery(
            node = "Overseer",
            probe = { probes++; looks.removeFirstOrNull() ?: HostProbe.Silent },
            recoveryLoad = { recoveryLoads++; loads.removeFirstOrNull() ?: RecoveryLoad.Fresh },
            persist = { evidence += it },
            wallClock = { start + scope.testScheduler.currentTime },
        )
    }

    private fun TestScope.rig(vararg looks: HostProbe, loads: List<RecoveryLoad> = emptyList()) =
        Rig(this, ArrayDeque(looks.toList()), ArrayDeque(loads))

    @Test
    fun aHealthyFreshBootAfterAnOutageRecoversAfterAFreshLoadOnly() = runTest {
        val r = rig(HostProbe.Up(190_000), HostProbe.Silent, HostProbe.Silent, HostProbe.Up(12))
        val result = r.recovery.run(submit = { r.submits++; SubmitOutcome.Accepted }, baselineUptime = 190_000)
        assertEquals(RestartPhase.DONE, result.phase)
        assertEquals("sent exactly once", 1, r.submits)
        assertEquals(1, r.recoveryLoads)
        // Evidence was written before the request and recorded the outage.
        assertEquals(RestartPhase.SUBMITTING, r.evidence.first().phase)
        assertTrue(r.evidence.any { it.sawDown })
        assertEquals(RestartPhase.DONE, r.evidence.last().phase)
    }

    @Test
    fun theOldUptimeAfterANetworkBlipIsNotABoot() = runTest {
        // Down once (network blip), then the machine still answers with its old uptime.
        val r = rig(HostProbe.Silent, HostProbe.Up(190_010), HostProbe.Up(190_013))
        val job = async { r.recovery.run(submit = { SubmitOutcome.Accepted }, baselineUptime = 190_000) }
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(RestartPhase.DOWN, r.recovery.progress.value?.phase)
        assertEquals(0, r.recoveryLoads)
        job.cancel()
    }

    @Test
    fun httpErrorsSignInAndCertificateRefusalsNeverCountAsBack() = runTest {
        val r = rig(
            HostProbe.Silent,
            HostProbe.Answering,
            HostProbe.SignInNeeded,
            HostProbe.CertificateRefused,
        )
        val job = async { r.recovery.run(submit = { SubmitOutcome.Accepted }, baselineUptime = 190_000) }
        advanceTimeBy(4_000 + 3 * 3_000 + 1_000)
        runCurrent()
        val p = r.recovery.progress.value!!
        assertEquals(RestartPhase.DOWN, p.phase)
        assertEquals(RestartBlocker.CERTIFICATE, p.blocker)
        assertTrue(p.blocksWrites)
        assertEquals(0, r.recoveryLoads)
        job.cancel()
    }

    @Test
    fun aFailedRecoveryLoadKeepsTheScreenReadOnlyAndRetriesLater() = runTest {
        val stale = RecoveryLoad.Stale("Some answers came from saved data.")
        val r = rig(HostProbe.Silent, HostProbe.Up(5), HostProbe.Up(40), loads = listOf(stale, stale, stale, RecoveryLoad.Fresh))
        val job = async { r.recovery.run(submit = { SubmitOutcome.Accepted }, baselineUptime = 190_000) }
        // First look 4 s, outage, boot seen at 7 s, three stale loads 5 s apart.
        advanceTimeBy(4_000 + 3_000 + 2 * 5_000 + 1_000)
        runCurrent()
        val failed = r.recovery.progress.value!!
        assertEquals(RestartPhase.RECOVERY_FAILED, failed.phase)
        assertEquals("Some answers came from saved data.", failed.reason)
        assertTrue("still read-only after a failed reload", failed.blocksWrites)
        // Retry looks again now; the next load is fresh.
        r.recovery.probeNow()
        val result = job.await()
        assertEquals(RestartPhase.DONE, result.phase)
        assertEquals(4, r.recoveryLoads)
    }

    @Test
    fun aRejectedRestartReleasesAtOnceAndNeverWatches() = runTest {
        val r = rig()
        val result = r.recovery.run(submit = { SubmitOutcome.Rejected("Permission check failed") }, baselineUptime = 1)
        assertEquals(RestartPhase.REJECTED, result.phase)
        assertFalse(result.blocksWrites)
        assertEquals(0, r.probes)
    }

    @Test
    fun aLostAnswerIsWatchedNotResent() = runTest {
        val r = rig(HostProbe.Silent, HostProbe.Up(9))
        val result = r.recovery.run(submit = { r.submits++; SubmitOutcome.Unknown }, baselineUptime = 190_000)
        assertEquals(RestartPhase.DONE, result.phase)
        assertEquals(1, r.submits)
    }

    @Test
    fun repeatedPullsCoalesceIntoOneWatcherAndNeverResend() = runTest {
        val r = rig(*Array(20) { HostProbe.Up(190_000 + it.toLong()) })
        val job = async { r.recovery.run(submit = { r.submits++; SubmitOutcome.Accepted }, baselineUptime = 190_000) }
        runCurrent()
        repeat(10) { r.recovery.probeNow() }
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, r.submits)
        // Ten taps while waiting woke it once (a conflated wake), not ten parallel probes.
        assertTrue("probes=${r.probes}", r.probes <= 2)
        job.cancel()
    }

    @Test
    fun afterTheDeadlineItSaysSoButKeepsWatchingSlowly() = runTest {
        val r = rig(*Array(400) { HostProbe.Silent })
        val job = async { r.recovery.run(submit = { SubmitOutcome.Accepted }, baselineUptime = 190_000) }
        advanceTimeBy(15 * 60_000L + 1_000)
        runCurrent()
        val p = r.recovery.progress.value!!
        assertEquals(RestartPhase.TIMED_OUT, p.phase)
        assertTrue(p.blocksWrites)
        val before = r.probes
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("one slow look per 30 s", before + 1, r.probes)
        job.cancel()
    }

    @Test
    fun aRestoredWatchOnlyObservesAndRecognisesABootThatHappenedWhileClosed() = runTest {
        // The app died mid-reboot; no baseline, no outage seen. The machine is now up 60 s,
        // and the request went out 5 minutes ago: it booted after the request.
        val r = rig(HostProbe.Up(60))
        advanceTimeBy(5 * 60_000L)
        val evidence = RestartEvidence("Overseer", submittedAt = r.start, baselineUptime = null, sawDown = false, phase = RestartPhase.GOING_DOWN)
        val result = r.recovery.run(submit = null, baselineUptime = null, evidence = evidence)
        assertEquals(RestartPhase.DONE, result.phase)
        assertEquals("restored watches never send", 0, r.submits)
    }

    @Test
    fun aRestoredWatchIgnoresAMachineThatNeverRestarted() = runTest {
        // Up for days, request sent 5 minutes ago: it did not reboot (yet).
        val r = rig(*Array(10) { HostProbe.Up(190_000) })
        advanceTimeBy(5 * 60_000L)
        val evidence = RestartEvidence("Overseer", submittedAt = r.start, baselineUptime = null, sawDown = false, phase = RestartPhase.GOING_DOWN)
        val job = async { r.recovery.run(submit = null, baselineUptime = null, evidence = evidence) }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(RestartPhase.GOING_DOWN, r.recovery.progress.value?.phase)
        assertEquals(0, r.recoveryLoads)
        job.cancel()
    }

    @Test
    fun restoringASentRestartIsRefused() = runTest {
        val r = rig()
        val evidence = RestartEvidence("Overseer", 0, null, false, RestartPhase.DOWN)
        val thrown = runCatching { r.recovery.run(submit = { SubmitOutcome.Accepted }, baselineUptime = null, evidence = evidence) }
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
        assertNull(r.recovery.progress.value)
    }
}
