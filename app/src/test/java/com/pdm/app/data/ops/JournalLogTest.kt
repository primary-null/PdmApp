package com.pdm.app.data.ops

import com.pdm.app.data.repo.PdmException
import com.pdm.app.data.repo.checkedChangelog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The PDM host's journal (F2), against a fake journal that answers like mini-journalreader. */
class JournalLogTest {
    @Test
    fun decodesTheReaderFramingIntoLinesAndCursors() {
        val page = JournalDecoder.decode(
            """{"data":["s=a;i=1","Oct 24 09:00:01 overseer systemd[1]: Started x.","-- Reboot --","Oct 24 09:01:00 overseer kernel: up","s=a;i=3"],"success":1}""",
        )
        assertEquals(listOf("Oct 24 09:00:01 overseer systemd[1]: Started x.", "-- Reboot --", "Oct 24 09:01:00 overseer kernel: up"), page.lines)
        assertEquals("s=a;i=1", page.firstCursor)
        assertEquals("s=a;i=3", page.lastCursor)
    }

    @Test
    fun anEmptyAnswerIsTheAnchorTwiceAndHasNoLines() {
        val page = JournalDecoder.decode("""{"data":["s=a;i=9","s=a;i=9"],"success":1}""")
        assertTrue(page.lines.isEmpty())
        assertEquals("s=a;i=9", page.lastCursor)
    }

    @Test
    fun theReadersOwnErrorIsShownNotTheFraming() {
        try {
            JournalDecoder.decode("""{"data":[],"error":"Failed to seek to cursor","success":0}""")
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertEquals("Failed to seek to cursor", e.message)
        }
    }

    /** A journal of [size] entries, cursors c0..c(size-1), answering like mini-journalreader. */
    private class Journal(var size: Int) {
        val queries = mutableListOf<JournalQuery>()
        var fail = false
        private fun line(i: Int) = "line $i"
        private fun page(from: Int, toExclusive: Int, anchor: String?): JournalPage {
            if (from >= toExclusive) return JournalPage(emptyList(), anchor, anchor)
            return JournalPage((from until toExclusive).map(::line), "c$from", "c${toExclusive - 1}")
        }
        fun read(q: JournalQuery): JournalPage {
            queries += q
            if (fail) throw IllegalStateException("Logs didn't load: no connection.")
            val n = q.lastEntries ?: Int.MAX_VALUE
            return when {
                q.startCursor != null -> {
                    // Reads on from the cursor, starting with that entry itself.
                    val at = q.startCursor.removePrefix("c").toInt()
                    page(at, size, q.startCursor)
                }
                q.endCursor != null -> {
                    // Reads up to and including the cursor.
                    val at = q.endCursor.removePrefix("c").toInt()
                    page(maxOf(0, at + 1 - n), at + 1, q.endCursor)
                }
                else -> page(maxOf(0, size - n), size, null)
            }
        }
    }

    @Test
    fun opensAtTheNewestEntries() = runTest {
        val j = Journal(1_000)
        val c = JournalLogController(j::read)
        c.open()
        val s = c.state.value
        assertEquals(JournalLogController.PAGE, s.lines.size)
        assertEquals("line 999", s.lines.last())
        assertEquals("line 800", s.lines.first())
        assertTrue(s.hasEarlier)
    }

    @Test
    fun earlierPagesJoinWithoutRepeatingTheBoundaryLine() = runTest {
        val j = Journal(1_000)
        val c = JournalLogController(j::read)
        c.open()
        c.earlier()
        val lines = c.state.value.lines
        assertEquals(lines.distinct(), lines)
        assertEquals("line 601", lines.first())
        assertEquals((601..999).map { "line $it" }, lines)
    }

    @Test
    fun pagingBackReachesTheStartAndSaysSo() = runTest {
        val j = Journal(250)
        val c = JournalLogController(j::read)
        c.open()
        c.earlier()
        c.earlier()
        val s = c.state.value
        assertEquals((0..249).map { "line $it" }, s.lines)
        assertTrue(s.atStart)
        assertFalse(s.hasEarlier)
    }

    @Test
    fun newerAddsOnlyWhatArrivedSince() = runTest {
        val j = Journal(300)
        val c = JournalLogController(j::read)
        c.open()
        j.size = 305
        c.newer()
        val lines = c.state.value.lines
        assertEquals(lines.distinct(), lines)
        assertEquals("line 304", lines.last())
        assertEquals(JournalLogController.PAGE + 5, lines.size)
        // A count and a start point are never sent together.
        assertNull(j.queries.last().lastEntries?.takeIf { j.queries.last().startCursor != null })
    }

    @Test
    fun theWindowStaysBounded() = runTest {
        val j = Journal(10_000)
        val c = JournalLogController(j::read)
        c.open()
        repeat(15) { c.earlier() }
        val s = c.state.value
        assertEquals(JournalLogController.RETAIN, s.lines.size)
        // Newest lines were dropped, so "newer" starts again from the end rather than guessing.
        assertNull(s.lastCursor)
        c.newer()
        assertEquals("line 9999", c.state.value.lines.last())
    }

    @Test
    fun aFailedReadKeepsTheLinesAndRetryRecovers() = runTest {
        val j = Journal(400)
        val c = JournalLogController(j::read)
        c.open()
        j.fail = true
        c.earlier()
        assertEquals(JournalLogController.PAGE, c.state.value.lines.size)
        assertEquals("Logs didn't load: no connection.", c.state.value.error)
        j.fail = false
        c.retry()
        assertNull(c.state.value.error)
        assertEquals("line 399", c.state.value.lines.last())
    }

    @Test
    fun aServiceFilterIsSentWithEveryPage() = runTest {
        val j = Journal(1_000)
        val c = JournalLogController(j::read)
        c.open(JournalFilters(service = " pveproxy "))
        c.earlier()
        c.newer()
        assertTrue(j.queries.all { it.service == "pveproxy" })
    }

    @Test
    fun anAnswerForAClosedScreenIsDropped() = runTest {
        lateinit var c: JournalLogController
        val j = Journal(100)
        c = JournalLogController { q -> c.close(); j.read(q) }
        c.open()
        assertTrue(c.state.value.lines.isEmpty())
    }

    @Test
    fun aptsErrorInsideASuccessfulAnswerIsNotShownAsAChangelog() {
        try {
            checkedChangelog("E: Failed to fetch https://example/changelog 404 Not Found", "proxmox-datacenter-manager")
            fail("expected an error")
        } catch (e: PdmException) {
            assertTrue(e.message!!.contains("has not published it"))
        }
        assertEquals("pkg (1.0) stable; urgency=low", checkedChangelog("pkg (1.0) stable; urgency=low", "pkg"))
    }
}
