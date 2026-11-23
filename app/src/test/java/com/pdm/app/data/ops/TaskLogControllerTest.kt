package com.pdm.app.data.ops

import com.pdm.app.data.model.TaskLogLine
import com.pdm.app.data.model.TaskStatus
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real log controller against a fake server that numbers lines like PVE or like PDM/PBS (Q2). */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskLogControllerTest {
    /** [skipStyle]: PVE's `start` skips that many lines; otherwise lines with n >= start come back. */
    private class FakeLog(var lines: Int, var running: Boolean, private val skipStyle: Boolean, private val withTotal: Boolean = true) : TaskLogSource {
        val requests = mutableListOf<Pair<Int, Int>>()
        var failNext = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun status() = TaskStatus(status = if (running) "running" else "stopped", exitstatus = if (running) null else "OK")
        override suspend fun page(start: Int, limit: Int): Pair<List<TaskLogLine>, Long?> {
            gate?.await()
            requests += start to limit
            if (failNext) { failNext = false; throw IOException("connection reset") }
            val first = if (skipStyle) start + 1 else maxOf(1, start)
            val out = (first..minOf(lines, first + limit - 1)).map { TaskLogLine(it.toLong(), text(it)) }
            return out to (if (withTotal) lines.toLong() else null)
        }
        fun text(n: Int) = if (!running && n == lines) "TASK OK" else "line $n"
    }

    @Test
    fun aFinishedLogFarBeyondTheBoundShowsItsRealEnd() = runTest {
        for (skip in listOf(true, false)) {
            val log = FakeLog(3_500, running = false, skipStyle = skip)
            val c = TaskLogController(log)
            c.open()
            c.poll()
            val s = c.state.value
            assertEquals("TASK OK", s.lines.last().t)
            assertEquals(3_500L, s.lastN)
            assertTrue(s.complete)
            assertTrue("window stays bounded", s.lines.size <= TaskLogController.RETAIN)
            assertTrue(s.hasEarlier)
            // No request ever asked for an unbounded or oversized page.
            assertTrue(log.requests.all { it.second in 1..TaskLogController.PAGE + 1 })
            // It jumped near the end instead of walking the whole log.
            assertTrue("requests=${log.requests.size}", log.requests.size <= 3)
        }
    }

    @Test
    fun followingARunningTaskAppendsOnlyNewLinesWithoutDuplicates() = runTest {
        for (skip in listOf(true, false)) {
            val log = FakeLog(40, running = true, skipStyle = skip)
            val c = TaskLogController(log)
            c.open()
            assertEquals(40L, c.state.value.lastN)
            log.lines = 55
            c.poll()
            val s = c.state.value
            assertEquals((1L..55L).toList(), s.lines.map { it.n })
            // The poll asked from the last line, not from the start again.
            assertTrue("${log.requests.last()}", log.requests.last().first >= 39)
        }
    }

    @Test
    fun theFinalTailIsReadWhenATaskEndsBetweenPolls() = runTest {
        val log = FakeLog(100, running = true, skipStyle = true)
        val c = TaskLogController(log)
        c.open()
        log.lines = 1_800; log.running = false
        assertFalse("nothing left to poll", c.poll())
        val s = c.state.value
        assertEquals("TASK OK", s.lines.last().t)
        assertEquals(1_800L, s.lastN)
    }

    @Test
    fun followOffKeepsTheWindowAndOnlyLearnsThatMoreExists() = runTest {
        val log = FakeLog(1_200, running = true, skipStyle = false)
        val c = TaskLogController(log)
        c.open()
        c.loadEarlier() // reading back turns Follow off
        val window = c.state.value.lines.map { it.n }
        assertFalse(c.state.value.follow)
        log.lines = 1_400; log.running = false
        c.poll()
        val s = c.state.value
        assertEquals("window unchanged", window, s.lines.map { it.n })
        assertEquals(1_400L, s.total)
        assertTrue(s.hasLater)
        c.loadLater()
        assertEquals(1_200L + TaskLogController.PAGE.coerceAtMost(200), c.state.value.lastN)
    }

    @Test
    fun loadingEarlierPrependsInOrderAndRespectsTheBound() = runTest {
        val log = FakeLog(5_000, running = false, skipStyle = true)
        val c = TaskLogController(log)
        c.open()
        repeat(6) { c.loadEarlier() }
        val s = c.state.value
        assertEquals(s.lines.map { it.n }, s.lines.map { it.n }.sorted())
        assertEquals(s.lines.size, s.lines.distinctBy { it.n }.size)
        assertTrue(s.lines.size <= TaskLogController.RETAIN)
        // The lines just read stay; the newest ones made room.
        assertEquals(5_000L - TaskLogController.PAGE - 6 * TaskLogController.PAGE + 1, s.firstN)
    }

    @Test
    fun aFailedReadKeepsTheLinesAndRetryRecovers() = runTest {
        val log = FakeLog(30, running = true, skipStyle = true)
        val c = TaskLogController(log)
        c.open()
        log.lines = 45
        log.failNext = true
        c.poll()
        val failed = c.state.value
        assertEquals(30L, failed.lastN)
        assertTrue(failed.error != null)
        c.retry()
        assertEquals(45L, c.state.value.lastN)
        assertEquals(null, c.state.value.error)
    }

    @Test
    fun anEmptyLogIsEmptyNotLoadingForever() = runTest {
        val c = TaskLogController(FakeLog(0, running = false, skipStyle = true))
        c.open()
        c.poll()
        val s = c.state.value
        assertTrue(s.lines.isEmpty())
        assertFalse(s.loading)
        assertTrue(s.complete)
    }

    @Test
    fun withoutATotalAFinishedLogIsStillReadToItsEnd() = runTest {
        val log = FakeLog(2_300, running = false, skipStyle = false, withTotal = false)
        val c = TaskLogController(log)
        c.open()
        c.poll()
        assertEquals("TASK OK", c.state.value.lines.last().t)
    }

    @Test
    fun aReadThatFinishesAfterTheScreenClosedChangesNothing() = runTest {
        val log = FakeLog(20, running = true, skipStyle = true)
        val c = TaskLogController(log)
        c.open()
        val gate = CompletableDeferred<Unit>()
        log.gate = gate
        log.lines = 60
        val job = launch { c.poll() }
        runCurrent()
        c.close()
        gate.complete(Unit)
        job.join()
        assertEquals(20L, c.state.value.lastN)
    }

    @Test
    fun pageButtonsAndPollsNeverOverlap() = runTest {
        val log = FakeLog(3_000, running = true, skipStyle = true)
        val c = TaskLogController(log)
        c.open()
        val gate = CompletableDeferred<Unit>()
        log.gate = gate
        val a = launch { c.loadEarlier() }
        val b = launch { c.poll() }
        runCurrent()
        // Only one read is in flight while the gate is shut.
        assertTrue(log.requests.size <= 2)
        gate.complete(Unit)
        a.join(); b.join()
        val s = c.state.value
        assertEquals(s.lines.size, s.lines.distinctBy { it.n }.size)
        assertEquals(s.lines.map { it.n }, s.lines.map { it.n }.sorted())
    }
}
