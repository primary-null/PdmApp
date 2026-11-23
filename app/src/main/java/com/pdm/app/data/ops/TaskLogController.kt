package com.pdm.app.data.ops

import com.pdm.app.data.model.TaskLogLine
import com.pdm.app.data.model.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a task's log and status come from: the host routes or the remote ones (Q2). */
interface TaskLogSource {
    /** Live status (never from the offline cache). */
    suspend fun status(): TaskStatus?

    /** Lines starting near [start] (see [TaskLogController]), at most [limit], plus the log's total. */
    suspend fun page(start: Int, limit: Int): Pair<List<TaskLogLine>, Long?>
}

/** What the log screen shows. [lines] is a window onto the log, sorted by line number. */
data class TaskLogState(
    val lines: List<TaskLogLine> = emptyList(),
    val total: Long? = null,
    val status: TaskStatus? = null,
    /** Keep the window at the end and append new lines as they come. */
    val follow: Boolean = true,
    val loading: Boolean = true,
    val loadingEarlier: Boolean = false,
    val loadingLater: Boolean = false,
    /** The last read that failed; the lines on screen stay. */
    val error: String? = null,
    /** The status read failed: running/finished is unknown, not assumed. */
    val statusUnknown: Boolean = false,
    /** The task ended and its last lines have been read. */
    val complete: Boolean = false,
) {
    val firstN: Long get() = lines.firstOrNull()?.n ?: 0
    val lastN: Long get() = lines.lastOrNull()?.n ?: 0

    /** Lines before the window exist on the server. */
    val hasEarlier: Boolean get() = firstN > 1

    /** Lines after the window exist (or may, when the total is unknown and the task runs). */
    val hasLater: Boolean get() = total?.let { lastN < it } ?: false
    val running: Boolean get() = status?.isRunning == true
}

/**
 * A bounded, followable window onto one task's log (Q2).
 *
 * Line numbers: every server numbers lines from 1 ("n"). PVE's `start` skips that many lines
 * (returns n >= start+1); PDM and PBS return lines with n >= start. To be right for both, a read
 * for lines from N asks for start = N - 1 and drops anything outside the wanted range, keyed by n.
 *
 * Bounds: at most [RETAIN] lines are kept; each request asks for at most [PAGE] (+1) lines; a poll
 * catches up with at most [CATCH_UP_PAGES] requests, and when further behind it jumps to the end.
 * Every read and every change of the window runs under one lock, so polls, page buttons and the
 * final read never race, and a read that finishes after [close] changes nothing.
 */
class TaskLogController(private val source: TaskLogSource) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(TaskLogState())
    val state: StateFlow<TaskLogState> = _state.asStateFlow()
    private var closed = false

    /** First read: live status and the end of the log (the part that matters most). */
    suspend fun open() = guarded {
        readStatus()
        jumpToEndLocked()
    }

    /**
     * One tick while the screen is open: status, then (when following) new lines. After the task
     * ends, one more read takes the log to its real end. Returns false once there is nothing left
     * to poll for.
     */
    suspend fun poll(): Boolean = guarded {
        val st = _state.value
        if (st.complete) return@guarded false
        readStatus()
        val now = _state.value
        when {
            now.statusUnknown -> Unit // nothing decided from a failed read
            now.running -> if (now.follow) catchUpLocked()
            else -> {
                // Finished: read to the true end once, then stop.
                if (now.follow || now.lines.isEmpty()) catchUpLocked(FINAL_PAGES) else learnTotalLocked()
                if (_state.value.error == null) _state.update { it.copy(complete = true) }
            }
        }
        !_state.value.complete
    } ?: false

    /** Older lines above the window. Turns Follow off: the person is reading back. */
    suspend fun loadEarlier() = guarded {
        val st = _state.value
        if (!st.hasEarlier) return@guarded
        _state.update { it.copy(loadingEarlier = true, follow = false) }
        val from = maxOf(1L, st.firstN - PAGE)
        val wanted = from until st.firstN
        val got = fetch(from, (st.firstN - from).toInt()) { lines -> lines.filter { it.n in wanted } }
        if (got != null) {
            _state.update { s ->
                val merged = mergeSorted(got.first, s.lines)
                // Over the bound: drop from the far end (the newest), never the lines just read.
                s.copy(lines = merged.take(RETAIN), total = got.second ?: s.total)
            }
        }
        _state.update { it.copy(loadingEarlier = false) }
    }

    /** Newer lines below the window. */
    suspend fun loadLater() = guarded {
        _state.update { it.copy(loadingLater = true) }
        appendLocked()
        _state.update { it.copy(loadingLater = false) }
    }

    /** Follow on: jump to the end and keep up. Off: keep the window where it is. */
    suspend fun setFollow(on: Boolean) = guarded {
        _state.update { it.copy(follow = on) }
        if (on) jumpToEndLocked()
    }

    /** Retry after a failed read: re-read status and whatever the window needs. */
    suspend fun retry() = guarded {
        _state.update { it.copy(error = null) }
        readStatus()
        if (_state.value.lines.isEmpty() || _state.value.follow) jumpToEndLocked() else appendLocked()
    }

    /** The screen left this task: late reads change nothing from now on. */
    fun close() {
        closed = true
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = lock.withLock {
        if (closed) null else block()
    }

    private suspend fun readStatus() {
        try {
            val s = source.status()
            if (closed) return
            _state.update { if (s == null) it.copy(statusUnknown = true) else it.copy(status = s, statusUnknown = false) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(statusUnknown = true, error = e.message ?: "The task status didn't load.") }
        }
    }

    /** Replace the window with the last [PAGE] lines (two reads when the total is unknown). */
    private suspend fun jumpToEndLocked() {
        val probe = fetch(1, PAGE) { it } ?: run {
            _state.update { it.copy(loading = false) }
            return
        }
        var (lines, total) = probe
        val known = total
        if (known != null && known > PAGE) {
            val from = known - PAGE + 1
            fetch(from, PAGE) { page -> page.filter { it.n >= from } }?.let { (tail, t2) ->
                lines = tail
                total = t2 ?: known
            }
        }
        _state.update { it.copy(lines = lines.takeLast(RETAIN), total = total ?: it.total, loading = false) }
    }

    /** Follow: read what was added, a few pages at most; much further behind, jump to the end. */
    private suspend fun catchUpLocked(maxPages: Int = CATCH_UP_PAGES) {
        val st = _state.value
        val total = st.total
        if (st.lines.isEmpty() || (total != null && total - st.lastN > CATCH_UP_PAGES * PAGE)) {
            jumpToEndLocked()
            if (_state.value.total != null) return
        }
        // Without a total from the server, read on until a short page shows the end (bounded).
        repeat(maxPages) {
            val before = _state.value.lastN
            val added = appendLocked() ?: return
            if (added < PAGE || _state.value.lastN == before) return
        }
    }

    /** Learn how long the log is now without moving the window (Follow off, task ended). */
    private suspend fun learnTotalLocked() {
        val st = _state.value
        fetch(st.lastN + 1, 1) { lines -> lines.filter { it.n > st.lastN } }?.let { (_, total) ->
            _state.update { it.copy(total = total ?: it.total) }
        }
    }

    /** Read the page after the window, keep it bounded; returns how many new lines arrived. */
    private suspend fun appendLocked(): Int? {
        val st = _state.value
        val got = fetch(st.lastN + 1, PAGE) { lines -> lines.filter { it.n > st.lastN } } ?: return null
        _state.update { s ->
            val merged = mergeSorted(s.lines, got.first)
            s.copy(lines = merged.takeLast(RETAIN), total = got.second ?: s.total)
        }
        return got.first.size
    }

    /** One read for lines from [from] (1-based), tolerant of both start conventions. */
    private suspend fun fetch(
        from: Long,
        count: Int,
        keep: (List<TaskLogLine>) -> List<TaskLogLine>,
    ): Pair<List<TaskLogLine>, Long?>? = try {
        val (lines, total) = source.page(start = (from - 1).coerceAtLeast(0).toInt(), limit = count + 1)
        if (closed) null else {
            _state.update { it.copy(error = null) }
            keep(lines.distinctBy { it.n }.sortedBy { it.n }).take(count) to total
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        _state.update { it.copy(error = e.message ?: "The log didn't load.") }
        null
    }

    companion object {
        const val PAGE = 500
        const val RETAIN = 2_000
        const val CATCH_UP_PAGES = 4

        /** Reading to the end after the task finished, when the server gives no total. */
        const val FINAL_PAGES = 40

        fun mergeSorted(a: List<TaskLogLine>, b: List<TaskLogLine>): List<TaskLogLine> =
            (a + b).distinctBy { it.n }.sortedBy { it.n }
    }
}
