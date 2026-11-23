package com.pdm.app.data.ops

import com.pdm.app.data.repo.PdmHttpException
import com.pdm.app.data.repo.isDigestConflict
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real notes editor against a fake server that checks digests like PDM's config/notes (F3). */
class NotesEditorTest {
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private class Server(var text: String) {
        var writes = 0
        var lose = false
        var failRead = false
        fun copy(sha: (String) -> String) = NotesCopy(text, sha(text))
    }

    private fun editor(server: Server): NotesEditor = NotesEditor(
        read = { if (server.failRead) throw java.io.IOException("reset") else server.copy(::sha) },
        write = { text, digest ->
            server.writes++
            when {
                digest != sha(server.text) -> NotesWriteResult.Conflict
                server.lose -> { server.text = text; NotesWriteResult.Unknown }
                else -> { server.text = text; NotesWriteResult.Saved }
            }
        },
        classify = { NotesTrouble.OTHER },
        wallClock = { 42L },
    )

    @Test
    fun aSaveIsConfirmedByReadingItBack() = runTest {
        val server = Server("# Fleet\nbackups at 02:00")
        val e = editor(server)
        e.load()
        e.startEditing()
        e.edit("# Fleet\nbackups at 03:00")
        e.save()
        val s = e.state.value
        assertEquals("# Fleet\nbackups at 03:00", server.text)
        assertEquals(server.text, s.server?.text)
        assertEquals(sha(server.text), s.server?.digest)
        assertFalse(s.editing)
        assertEquals(42L, s.savedAt)
    }

    @Test
    fun aConflictKeepsTheDraftShowsTheirsAndNeverResavesByItself() = runTest {
        val server = Server("v1")
        val e = editor(server)
        e.load(); e.startEditing(); e.edit("mine")
        server.text = "theirs" // someone else saved meanwhile
        e.save()
        val s = e.state.value
        assertEquals("theirs", server.text)
        assertEquals(1, server.writes)
        assertEquals("mine", s.draft)
        assertEquals("theirs", s.conflict?.theirs?.text)
        assertFalse("save is blocked until a choice is made", s.canSave)
        // Choosing to continue over theirs is explicit; the next save is the person's own.
        e.replaceTheirs()
        assertTrue(e.state.value.canSave)
        e.save()
        assertEquals("mine", server.text)
        assertEquals(2, server.writes)
    }

    @Test
    fun keepTheirsDropsTheDraft() = runTest {
        val server = Server("v1")
        val e = editor(server)
        e.load(); e.startEditing(); e.edit("mine")
        server.text = "theirs"
        e.save()
        e.keepTheirs()
        val s = e.state.value
        assertNull(s.draft)
        assertEquals("theirs", s.server?.text)
    }

    @Test
    fun aLostAnswerBlocksSavingUntilAReloadSaysWhatHappened() = runTest {
        val server = Server("v1")
        val e = editor(server)
        e.load(); e.startEditing(); e.edit("v2")
        server.lose = true
        e.save()
        val s = e.state.value
        assertTrue(s.saveUnconfirmed)
        assertFalse(s.canSave)
        // The lost save had landed: the reload confirms it.
        e.load()
        val after = e.state.value
        assertFalse(after.saveUnconfirmed)
        assertNull(after.draft)
        assertEquals("v2", after.server?.text)
        assertEquals(1, server.writes)
    }

    @Test
    fun aLostAnswerThatDidNotLandKeepsTheDraftForASecondTry() = runTest {
        val server = Server("v1")
        val e = NotesEditor(
            read = { server.copy(::sha) },
            write = { _, _ -> server.writes++; NotesWriteResult.Unknown }, // never applied
            classify = { NotesTrouble.OTHER },
        )
        e.load(); e.startEditing(); e.edit("v2")
        e.save()
        e.load()
        val s = e.state.value
        assertEquals("v2", s.draft)
        assertFalse(s.saveUnconfirmed)
        assertTrue(s.canSave)
    }

    @Test
    fun aFailedReadKeepsWhatWasShownAndADraft() = runTest {
        val server = Server("v1")
        val e = editor(server)
        e.load(); e.startEditing(); e.edit("half written")
        server.failRead = true
        e.load()
        val s = e.state.value
        assertEquals("half written", s.draft)
        assertEquals("v1", s.server?.text)
        assertTrue(s.error != null)
    }

    @Test
    fun nothingCanBeSavedWithoutALiveCopyFirst() = runTest {
        val server = Server("v1")
        val e = editor(server)
        e.startEditing() // never loaded: no digest to save against
        e.save()
        assertEquals(0, server.writes)
        assertFalse(e.state.value.editing)
    }

    @Test
    fun anotherServersLateAnswerIsDropped() = runTest {
        val server = Server("old server notes")
        lateinit var e: NotesEditor
        e = NotesEditor(
            read = { e.reset(); server.copy(::sha) }, // the session changed while reading
            write = { _, _ -> NotesWriteResult.Saved },
            classify = { NotesTrouble.OTHER },
        )
        e.load()
        assertNull(e.state.value.server)
    }

    @Test
    fun pdmsDigestRejectionIsRecognisedAsAConflict() {
        val body = """{"errors":{},"message":"detected modified configuration - file changed by other user? Try again.\n"}"""
        assertTrue(isDigestConflict(PdmHttpException(400, body, "Bad Request")))
        assertFalse(isDigestConflict(PdmHttpException(400, """{"message":"parameter verification failed"}""", "Bad Request")))
        assertFalse(isDigestConflict(PdmHttpException(403, body, "Forbidden")))
    }
}
