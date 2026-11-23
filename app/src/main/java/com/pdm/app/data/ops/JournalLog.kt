package com.pdm.app.data.ops

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One answer from PDM's `nodes/{node}/journal` (F2). The route streams mini-journalreader's own
 * output: `{"data":[firstCursor, line, ..., lastCursor],"success":1}`, or on failure
 * `{"data":[...],"error":"...","success":0}`. With no entries the anchor cursor appears twice.
 */
data class JournalPage(val lines: List<String>, val firstCursor: String?, val lastCursor: String?)

object JournalDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    /** Largest answer read; pages are a few hundred lines, so this is far above normal use. */
    const val MAX_BYTES = 2 * 1024 * 1024

    fun decode(text: String): JournalPage {
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalStateException("The journal answer wasn't readable.")
        val success = (root["success"] as? JsonPrimitive)?.intOrNull
        if (success == 0) {
            val err = (root["error"] as? JsonPrimitive)?.contentOrNull?.trim()
            throw IllegalStateException(err?.ifBlank { null } ?: "The journal couldn't be read.")
        }
        val data = (root["data"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (data.size < 2) return JournalPage(emptyList(), data.firstOrNull(), data.lastOrNull())
        val first = data.first()
        val last = data.last()
        // An empty result is just the anchor cursor, twice.
        if (data.size == 2 && first == last) return JournalPage(emptyList(), first, last)
        return JournalPage(data.subList(1, data.size - 1), first, last)
    }
}

/** What to read. Only the parameters that combine are ever sent together. */
data class JournalQuery(
    val lastEntries: Int? = null,
    val startCursor: String? = null,
    val endCursor: String? = null,
    val service: String? = null,
    val unit: String? = null,
)

data class JournalFilters(val service: String = "", val unit: String = "")

data class JournalState(
    val lines: List<String> = emptyList(),
    val firstCursor: String? = null,
    val lastCursor: String? = null,
    val filters: JournalFilters = JournalFilters(),
    val loading: Boolean = false,
    val loadingEarlier: Boolean = false,
    val loadingNewer: Boolean = false,
    /** An earlier read came back empty: this is the start of the journal (for these filters). */
    val atStart: Boolean = false,
    val requested: Boolean = false,
    val error: String? = null,
) {
    val busy: Boolean get() = loading || loadingEarlier || loadingNewer
    val hasEarlier: Boolean get() = !atStart && firstCursor != null && lines.isNotEmpty()
}

private fun String.orNull(): String? = trim().ifBlank { null }

/**
 * The PDM host's journal (F2): read only when asked, a bounded window of [RETAIN] lines, paged
 * by journal cursors ([PAGE] entries a read). Never polled in the background.
 */
class JournalLogController(private val fetch: suspend (JournalQuery) -> JournalPage) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(JournalState())
    val state: StateFlow<JournalState> = _state.asStateFlow()
    private var generation = 0L

    /** The newest [PAGE] entries for [filters]. */
    suspend fun open(filters: JournalFilters = _state.value.filters) = lock.withLock { openLocked(filters) }

    private suspend fun openLocked(filters: JournalFilters) {
        val gen = ++generation
        _state.value = JournalState(filters = filters, loading = true, requested = true)
        read(gen, JournalQuery(lastEntries = PAGE, service = filters.service.orNull(), unit = filters.unit.orNull())) { page, s ->
            s.copy(lines = page.lines.takeLast(RETAIN), firstCursor = page.firstCursor, lastCursor = page.lastCursor, loading = false)
        }
    }

    /** Entries before the window. */
    suspend fun earlier() = lock.withLock {
        val s = _state.value
        val anchor = s.firstCursor ?: return@withLock
        if (s.atStart || s.lines.isEmpty() || s.busy) return@withLock
        val gen = generation
        _state.update { it.copy(loadingEarlier = true) }
        read(gen, JournalQuery(lastEntries = PAGE, endCursor = anchor, service = s.filters.service.orNull(), unit = s.filters.unit.orNull())) { page, st ->
            // A read up to a cursor may include that entry itself: it is already the window's first line.
            val fresh = if (page.lastCursor == anchor) page.lines.dropLast(1) else page.lines
            if (fresh.isEmpty()) {
                st.copy(atStart = true, loadingEarlier = false)
            } else {
                val merged = fresh + st.lines
                st.copy(
                    lines = merged.take(RETAIN),
                    firstCursor = page.firstCursor ?: st.firstCursor,
                    // Newest lines dropped to stay bounded: the end cursor no longer matches the window.
                    lastCursor = if (merged.size > RETAIN) null else st.lastCursor,
                    loadingEarlier = false,
                )
            }
        }
    }

    /** Entries after the window, when asked; there is no background follow. */
    suspend fun newer() = lock.withLock {
        val s = _state.value
        if (s.busy) return@withLock
        // The window lost its end while paging back: start again from the newest entries.
        val anchor = s.lastCursor ?: return@withLock openLocked(s.filters)
        val gen = generation
        _state.update { it.copy(loadingNewer = true) }
        read(gen, JournalQuery(startCursor = anchor, service = s.filters.service.orNull(), unit = s.filters.unit.orNull())) { page, st ->
            // A read from a cursor may start with that entry itself: it is already the window's last line.
            val fresh = if (page.firstCursor == anchor && page.lines.isNotEmpty()) page.lines.drop(1) else page.lines
            val merged = st.lines + fresh
            st.copy(
                lines = merged.takeLast(RETAIN),
                lastCursor = page.lastCursor ?: st.lastCursor,
                firstCursor = if (merged.size > RETAIN) null else st.firstCursor,
                atStart = if (merged.size > RETAIN) false else st.atStart,
                loadingNewer = false,
            )
        }
    }

    /** After an error: repeat the newest read. */
    suspend fun retry() = open(_state.value.filters)

    /** The screen closed: drop answers still on their way. */
    fun close() {
        generation++
    }

    private suspend fun read(gen: Long, query: JournalQuery, apply: (JournalPage, JournalState) -> JournalState) {
        val page = try {
            fetch(query)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (gen == generation) {
                _state.update { it.copy(loading = false, loadingEarlier = false, loadingNewer = false, error = e.message ?: "The journal didn't load.") }
            }
            return
        }
        if (gen != generation) return
        _state.update { apply(page, it).copy(error = null) }
    }

    companion object {
        const val PAGE = 200
        const val RETAIN = 2_000
    }
}
