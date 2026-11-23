package com.pdm.app.ui.home

import com.pdm.app.data.api.ActivityRoute
import com.pdm.app.data.api.ApiActivity
import com.pdm.app.ui.NoteKind
import com.pdm.app.ui.NoteTone
import com.pdm.app.ui.SyncNote
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadCyclesTest {
    private fun trigger(at: Long, text: String = "Timer · quick") = SyncNote(at, text, NoteKind.TRIGGER)
    private fun result(at: Long, text: String = "live", tone: NoteTone = NoteTone.GOOD) = SyncNote(at, text, NoteKind.RESULT, tone)
    private fun event(at: Long, text: String) = SyncNote(at, text)
    private fun request(id: Long, at: Long) =
        ApiActivity(id, ActivityRoute("GET", "/resources/list", "x"), httpCode = 200, elapsedMs = 30, startedAt = at)

    @Test
    fun identicalRoutineLoadsFoldIntoOneRow() {
        val notes = (0 until 20).flatMap { listOf(trigger(it * 30_000L), result(it * 30_000L + 200)) }
        val cycles = loadCycles(notes, emptyList())
        assertEquals(1, cycles.size)
        assertEquals(20, cycles[0].repeats)
        assertEquals(0L, cycles[0].at)
        assertEquals(19 * 30_000L + 200, cycles[0].until)
    }

    @Test
    fun differentLoadsStaySeparateNewestFirstAndBounded() {
        val notes = listOf(
            trigger(0, "Sign-in · full"), result(100),
            trigger(30_000), result(30_100),
            trigger(60_000, "Pull · full"), result(60_100),
            trigger(90_000), result(90_100, "unreachable · cache filled in", NoteTone.BAD),
        )
        val cycles = loadCycles(notes, emptyList(), limit = 3)
        assertEquals(listOf("Timer · quick", "Pull · full", "Timer · quick"), cycles.map { it.trigger })
        assertEquals(NoteTone.BAD, cycles[0].tone)
    }

    @Test
    fun requestsBelongToTheLoadThatMadeThemAndTheRestAreScreenReads() {
        val notes = listOf(trigger(1_000), result(1_200), trigger(31_000, "Pull · full"), result(31_300))
        val requests = listOf(request(1, 1_010), request(2, 1_050), request(3, 10_000), request(4, 31_020))
        val cycles = loadCycles(notes, requests)
        assertEquals(listOf(4L), cycles[0].requests.map { it.id })
        assertEquals(listOf(1L, 2L), cycles[1].requests.map { it.id })
        assertEquals(listOf(3L), otherRequests(requests, cycles).map { it.id })
    }

    @Test
    fun eventsAreTheNotableLinesOnlyFoldedAndBounded() {
        val notes = listOf(
            event(1, "Showing saved data while going live"),
            trigger(2), result(3),
            event(4, "Signed in with the saved credential"),
            event(5, "Signed in with the saved credential"),
            event(6, "Retries paused: TOTP"),
        )
        val events = notableEvents(notes, limit = 2)
        assertEquals(listOf("Retries paused: TOTP", "Signed in with the saved credential"), events.map { it.text })
    }
}
