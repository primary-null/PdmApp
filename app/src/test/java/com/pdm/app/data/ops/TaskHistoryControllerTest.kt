package com.pdm.app.data.ops

import com.pdm.app.data.model.RemoteTask
import com.pdm.app.data.model.TaskFilters
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real history controller against a fake that filters like PDM's remote_tasks::get_tasks (F1). */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskHistoryControllerTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = 1_800_000_000L

    /** 130 tasks, newest first; every 7th failed, every 11th still running. */
    private val all = (0 until 130).map { i ->
        RemoteTask(
            upid = "NODE-C!UPID:NODE-C:$i:task",
            remoteId = "NODE-C",
            node = "NODE-C",
            workerType = if (i % 3 == 0) "vzdump" else "qmstart",
            user = if (i % 2 == 0) "root@pam" else "ops@pdm",
            startTime = now - i * 3600L,
            endTime = if (i % 11 == 0) null else now - i * 3600L + 60,
            status = when {
                i % 11 == 0 -> null
                i % 7 == 0 -> "command failed"
                else -> "OK"
            },
        )
    }
    val sent = mutableListOf<TaskFilters>()

    private fun serve(f: TaskFilters): HistoryPage {
        sent += f
        val rows = all.filter { t ->
            (f.running != true || t.endTime == null) &&
                (f.until == null || t.startTime <= f.until!!) &&
                (f.since == null || t.startTime >= f.since!!) &&
                (f.userfilter == null || t.user.contains(f.userfilter!!)) &&
                (f.typefilter == null || t.workerType.contains(f.typefilter!!)) &&
                (f.errors != true || (t.status != null && t.status != "OK")) &&
                (f.statusfilter == null || (t.status != null && (if (f.statusfilter == "ok") t.status == "OK" else t.status != "OK")))
        }
        return HistoryPage(rows.drop(f.start!!.toInt()).take(f.limit!!))
    }

    private fun controller(fetch: suspend (TaskFilters) -> HistoryPage = { serve(it) }) =
        TaskHistoryController(fetch, nowEpochSeconds = { now }, zone = { zone })

    @Test
    fun pagesAdvanceByWhatTheServerHandedOutAndStopAtAShortPage() = runTest {
        val c = controller()
        c.apply(HistoryQuery())
        c.loadMore(); c.loadMore()
        val s = c.state.value
        assertEquals(130, s.tasks.size)
        assertTrue(s.endReached)
        assertEquals(listOf(0L, 50L, 100L), sent.map { it.start })
        assertTrue(sent.all { it.limit == 50 })
        c.loadMore()
        assertEquals("nothing after the end", 3, sent.size)
    }

    @Test
    fun failedIsPdmsErrorFilterNotItsBroadNotOkFlag() = runTest {
        val c = controller()
        c.apply(HistoryQuery(outcome = HistoryOutcome.FAILED))
        val s = c.state.value
        assertTrue(s.tasks.isNotEmpty())
        assertTrue(s.tasks.all { it.status != null && it.status != "OK" })
        // The same filter PDM's own web interface sends for "Error"; warnings are not failures.
        assertEquals("error", sent.single().statusfilter)
        assertNull(sent.single().errors)
        assertNull(sent.single().running)
    }

    @Test
    fun runningOnlyUsesTheServersRunningFlag() = runTest {
        val c = controller()
        c.apply(HistoryQuery(outcome = HistoryOutcome.RUNNING))
        assertTrue(c.state.value.tasks.all { it.endTime == null })
        assertEquals(true, sent.single().running)
    }

    @Test
    fun combinedFiltersSerialiseToTheServerContract() {
        val q = HistoryQuery(
            remote = "NODE-C",
            outcome = HistoryOutcome.WARNING,
            type = " vzdump ",
            user = "root@pam",
            range = HistoryRange.Days(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)),
        )
        val f = q.toFilters(start = 100, limit = 50, nowEpochSeconds = now, zone = zone)
        assertEquals("NODE-C", f.remote)
        assertEquals("warning", f.statusfilter)
        assertEquals("vzdump", f.typefilter)
        assertEquals("root@pam", f.userfilter)
        // Local midnight Oct 1 CDT to the last second of Oct 3 CDT, in UNIX seconds.
        assertEquals(1_790_830_800L, f.since)
        assertEquals(1_791_089_999L, f.until)
        assertEquals(100L, f.start)
        assertNull(f.errors); assertNull(f.running)
    }

    @Test
    fun lastHoursCountsBackFromNowInSeconds() {
        val f = HistoryQuery(range = HistoryRange.LastHours(24)).toFilters(0, 50, now, zone)
        assertEquals(now - 86_400L, f.since)
        assertNull(f.until)
    }

    @Test
    fun anInvertedRangeIsNotSent() = runTest {
        val c = controller()
        c.apply(HistoryQuery(range = HistoryRange.Days(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1))))
        assertEquals("The start date is after the end date.", c.state.value.error)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun overlappingPagesAreNotShownTwice() = runTest {
        // A new task arrived between pages: the second page repeats the last row of the first.
        var calls = 0
        val c = controller { f ->
            calls++
            val page = serve(f)
            if (calls == 2) HistoryPage(listOf(all[49]) + page.tasks) else page
        }
        c.apply(HistoryQuery())
        c.loadMore()
        val s = c.state.value
        assertEquals(s.tasks.size, s.tasks.distinctBy { it.upid }.size)
        assertEquals("offset counts rows handed out, not rows shown", 101L, s.consumed)
    }

    @Test
    fun anOlderQuerysLatePageNeverLandsInANewerOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = controller { f -> if (f.statusfilter == "error") gate.await(); serve(f) }
        val slow = launch { c.apply(HistoryQuery(outcome = HistoryOutcome.FAILED)) }
        runCurrent()
        c.apply(HistoryQuery(outcome = HistoryOutcome.RUNNING))
        gate.complete(Unit)
        slow.join()
        val s = c.state.value
        assertEquals(HistoryOutcome.RUNNING, s.query.outcome)
        assertTrue(s.tasks.all { it.endTime == null })
    }

    @Test
    fun aFailedPageKeepsTheRowsAndRetryContinuesFromTheSamePlace() = runTest {
        var fail = false
        val c = controller { f -> if (fail) throw IOException("connection reset") else serve(f) }
        c.apply(HistoryQuery())
        fail = true
        c.loadMore()
        val failed = c.state.value
        assertEquals(50, failed.tasks.size)
        assertTrue(failed.error != null)
        assertFalse(failed.endReached)
        fail = false
        c.retry()
        assertEquals(100, c.state.value.tasks.size)
        assertEquals(50L, sent.last().start)
    }

    @Test
    fun aCachedPageIsMarkedWithItsAge() = runTest {
        val c = controller { f -> serve(f).copy(cachedAt = 1_799_000_000_000L) }
        c.apply(HistoryQuery())
        assertEquals(1_799_000_000_000L, c.state.value.cachedAt)
    }

    @Test
    fun closingDropsAPageStillOnItsWay() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = controller { f -> gate.await(); serve(f) }
        val job = launch { c.apply(HistoryQuery()) }
        runCurrent()
        c.close()
        gate.complete(Unit)
        job.join()
        assertTrue(c.state.value.tasks.isEmpty())
    }

    private fun counts(f: TaskFilters): com.pdm.app.data.model.TaskStatisticsDto {
        val rows = serve(f.copy(start = 0, limit = Int.MAX_VALUE)).tasks.filter { it.status != null }
        fun c(r: List<RemoteTask>) = com.pdm.app.data.model.TaskCountsDto(ok = r.count { it.status == "OK" }, error = r.count { it.status != "OK" })
        return com.pdm.app.data.model.TaskStatisticsDto(byRemote = rows.groupBy { it.remoteId }.mapValues { c(it.value) }, byType = rows.groupBy { it.workerType }.mapValues { c(it.value) })
    }

    @Test
    fun countsAskForEveryMatchWithTheListsOwnFilters() = runTest {
        val asked = mutableListOf<TaskFilters>()
        val c = TaskHistoryController({ serve(it) }, stats = { f -> asked += f; counts(f) }, nowEpochSeconds = { now }, zone = { zone })
        c.apply(HistoryQuery(type = "vzdump", range = HistoryRange.LastHours(48)))
        val f = asked.single()
        // limit 0: PDM otherwise counts only its first 50 matches.
        assertEquals(0, f.limit)
        assertEquals("vzdump", f.typefilter)
        assertEquals(now - 48 * 3600L, f.since)
        val s = c.state.value
        // The card and the list describe the same tasks.
        assertEquals(s.tasks.count { it.status != null }, s.stats!!.byType.values.sumOf { it.total })
    }

    @Test
    fun failedCountsRetryWithoutReloadingTheList() = runTest {
        var fail = true
        var listReads = 0
        val c = TaskHistoryController({ listReads++; serve(it) }, stats = { f -> if (fail) throw IOException("reset") else counts(f) }, nowEpochSeconds = { now }, zone = { zone })
        c.apply(HistoryQuery())
        assertTrue(c.state.value.statsError != null)
        assertEquals(50, c.state.value.tasks.size)
        fail = false
        c.retry()
        assertNull(c.state.value.statsError)
        assertTrue(c.state.value.stats != null)
        assertEquals("the list was not read again", 1, listReads)
    }

    @Test
    fun countsForAnOlderFilterAreDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = TaskHistoryController(
            { serve(it) },
            stats = { f -> if (f.typefilter == "vzdump") gate.await(); counts(f) },
            nowEpochSeconds = { now }, zone = { zone },
        )
        val slow = launch { c.apply(HistoryQuery(type = "vzdump")) }
        runCurrent()
        c.apply(HistoryQuery(type = "qmstart"))
        gate.complete(Unit)
        slow.join()
        assertEquals(setOf("qmstart"), c.state.value.stats!!.byType.keys)
    }
}
