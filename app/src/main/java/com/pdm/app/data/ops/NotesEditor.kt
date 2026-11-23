package com.pdm.app.data.ops

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The server's copy of the shared notes, with the digest that guards writes to it. */
data class NotesCopy(val text: String, val digest: String)

/** Why a notes read or write failed, in terms the person can act on. */
enum class NotesTrouble { FORBIDDEN, UNREACHABLE, OTHER }

/** Someone else saved while this person was editing: their copy, shown before anything is replaced. */
data class NotesConflict(val theirs: NotesCopy?)

data class NotesEditorState(
    /** Null until read live once. */
    val server: NotesCopy? = null,
    /** Non-null while editing. Kept in memory only. */
    val draft: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val conflict: NotesConflict? = null,
    /** The last save's answer never arrived: reload to see whether it landed before saving again. */
    val saveUnconfirmed: Boolean = false,
    val error: String? = null,
    val trouble: NotesTrouble? = null,
    /** Set when a save is confirmed by a fresh read. */
    val savedAt: Long? = null,
) {
    val editing: Boolean get() = draft != null
    val hasUnsavedChanges: Boolean get() = draft != null && draft != server?.text

    /** Saving needs a live copy with its digest, no open conflict and no unconfirmed earlier save. */
    val canSave: Boolean
        get() = editing && server != null && !saving && conflict == null && !saveUnconfirmed && hasUnsavedChanges
}

/** What the server said to a save. */
sealed interface NotesWriteResult {
    data object Saved : NotesWriteResult

    /** The digest no longer matches: the notes changed on the server. */
    data object Conflict : NotesWriteResult

    data class Rejected(val reason: String, val trouble: NotesTrouble) : NotesWriteResult

    /** Sent, but no answer came back: it may or may not have been saved. */
    data object Unknown : NotesWriteResult
}

/**
 * Shared PDM notes (F3): read live, edit a draft, save with the digest of the copy the edit
 * started from. A save is confirmed only by reading the notes back. A conflict keeps the draft
 * and shows the newer server copy; replacing it is the person's explicit choice, never automatic.
 * An unanswered save must be reconciled by a reload before another save is allowed.
 */
class NotesEditor(
    private val read: suspend () -> NotesCopy,
    private val write: suspend (text: String, digest: String) -> NotesWriteResult,
    private val classify: (Exception) -> NotesTrouble,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(NotesEditorState())
    val state: StateFlow<NotesEditorState> = _state.asStateFlow()
    private var generation = 0L

    /** Read the server copy. A draft in progress is kept; an unconfirmed save is reconciled. */
    suspend fun load() {
        val gen = generation
        _state.update { it.copy(loading = true, error = null, trouble = null) }
        val copy = try {
            read()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (gen == generation) _state.update { it.copy(loading = false, error = e.message, trouble = classify(e)) }
            return
        }
        if (gen != generation) return
        _state.update { s ->
            if (s.saveUnconfirmed && s.draft != null) {
                // The lost save landed exactly when the server now holds the draft.
                val landed = copy.text == s.draft
                s.copy(
                    server = copy,
                    draft = if (landed) null else s.draft,
                    saveUnconfirmed = false,
                    loading = false,
                    savedAt = if (landed) wallClock() else s.savedAt,
                    error = if (landed) null else "Your last save didn't reach the server. Save again when ready.",
                )
            } else {
                s.copy(server = copy, loading = false)
            }
        }
    }

    fun startEditing() {
        _state.update { s -> if (s.server == null || s.editing) s else s.copy(draft = s.server.text, savedAt = null) }
    }

    fun edit(text: String) {
        _state.update { s -> if (s.editing) s.copy(draft = text) else s }
    }

    /** Leave editing and throw the draft away. */
    fun discard() {
        _state.update { it.copy(draft = null, conflict = null, saveUnconfirmed = false, error = null) }
    }

    /** Save the draft against the copy it was edited from. Never retried by itself. */
    suspend fun save() {
        val s = _state.value
        if (!s.canSave) return
        val draft = s.draft ?: return
        val base = s.server ?: return
        val gen = generation
        _state.update { it.copy(saving = true, error = null) }
        val result = try {
            write(draft, base.digest)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NotesWriteResult.Rejected(e.message ?: "The notes weren't saved.", classify(e))
        }
        if (gen != generation) return
        when (result) {
            NotesWriteResult.Saved -> {
                // Confirm by reading back; the server returns no new digest of its own.
                val fresh = try {
                    read()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                if (gen != generation) return
                _state.update {
                    if (fresh == null) {
                        it.copy(saving = false, saveUnconfirmed = true, error = "Saved, but the notes couldn't be read back. Reload to check.")
                    } else {
                        it.copy(server = fresh, draft = null, saving = false, savedAt = wallClock())
                    }
                }
            }
            NotesWriteResult.Conflict -> {
                val theirs = try {
                    read()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                if (gen != generation) return
                _state.update { it.copy(saving = false, conflict = NotesConflict(theirs)) }
            }
            is NotesWriteResult.Rejected ->
                _state.update { it.copy(saving = false, error = result.reason, trouble = result.trouble) }
            NotesWriteResult.Unknown ->
                _state.update {
                    it.copy(saving = false, saveUnconfirmed = true, error = "No answer from the server. Reload to see whether it saved.")
                }
        }
    }

    /** After a conflict: take their copy and drop the draft. */
    fun keepTheirs() {
        _state.update { s ->
            val theirs = s.conflict?.theirs
            s.copy(server = theirs ?: s.server, draft = null, conflict = null)
        }
    }

    /**
     * After a conflict, having seen their copy: edit on top of it, so the next save (still the
     * person's own action) replaces exactly the version they looked at.
     */
    fun replaceTheirs() {
        _state.update { s ->
            val theirs = s.conflict?.theirs ?: return@update s
            s.copy(server = theirs, conflict = null)
        }
    }

    /** Another server or account: forget everything and ignore late answers. */
    fun reset() {
        generation++
        _state.value = NotesEditorState()
    }
}
