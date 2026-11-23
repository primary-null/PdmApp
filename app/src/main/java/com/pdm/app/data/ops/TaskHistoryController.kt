package com.pdm.app.data.ops

import com.pdm.app.data.model.RemoteTask
import com.pdm.app.data.model.TaskFilters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which tasks by status (F1). Each maps onto one of PDM's own filters, the same ones its web
 * interface sends; in particular Failed is `statusfilter=error`, so tasks that only warned are not
 * counted as failed.
 */
enum class HistoryOutcome(val label: String) {
    ALL("All"),

    /** PDM's `running`: only tasks that have not ended. */
    RUNNING("Running"),

    OK("OK"),
    WARNING("Warnings"),
    FAILED("Failed"),
}

/** A time window. Days are local calendar days; the server takes UNIX seconds. */
sealed interface HistoryRange {
    data object AnyTime : HistoryRange
    data class LastHours(val hours: Int) : HistoryRange

    /** Inclusive local days [from]..[to]. */
    data class Days(val from: LocalDate, val to: LocalDate) : HistoryRange
}

/** What the person asked for. [validate] says why it can't be sent, if it can't. */
data class HistoryQuery(
    val remote: String? = null,
    val outcome: HistoryOutcome = HistoryOutcome.ALL,
    val type: String = "",
    val user: String = "",
    val range: HistoryRange = HistoryRange.AnyTime,
) {
    fun validate(): String? = when (val r = range) {
        is HistoryRange.Days -> if (r.from.isAfter(r.to)) "The start date is after the end date." else null
        is HistoryRange.LastHours -> if (r.hours <= 0) "Pick a positive time window." else null
        HistoryRange.AnyTime -> null
    }

    /** The server's filter for one page: [start] is how many filtered rows to skip. */
    fun toFilters(start: Long, limit: Int, nowEpochSeconds: Long, zone: ZoneId): TaskFilters {
        val (since, until) = when (val r = range) {
            HistoryRange.AnyTime -> null to null
            is HistoryRange.LastHours -> (nowEpochSeconds - r.hours * 3600L) to null
            is HistoryRange.Days ->
                r.from.atStartOfDay(zone).toEpochSecond() to (r.to.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1)
        }
        return TaskFilters(
            start = start,
            limit = limit,
            running = true.takeIf { outcome == HistoryOutcome.RUNNING },
            statusfilter = when (outcome) {
                HistoryOutcome.OK -> "ok"
                HistoryOutcome.WARNING -> "warning"
                HistoryOutcome.FAILED -> "error"
                else -> null
            },
            userfilter = user.trim().ifEmpty { null },
            typefilter = type.trim().ifEmpty { null },
            since = since,
            until = until,
            remote = remote?.ifBlank { null },
        )
    }

    val isDefault: Boolean get() = this == HistoryQuery(remote = remote)
}

/** One page from the server, and when it was saved if it came from the offline cache. */
data class HistoryPage(val tasks: List<RemoteTask>, val cachedAt: Long? = null)

data class HistoryState(
    val query: HistoryQuery = HistoryQuery(),
    val tasks: List<RemoteTask> = emptyList(),
    /** Filtered rows the server has handed out so far; the next page skips this many. */
    val consumed: Long = 0,
    val endReached: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    /** Oldest saved answer among the pages shown; null when every page came live. */
    val cachedAt: Long? = null,
    /** Result counts for the same filters (finished tasks only; PDM doesn't count running ones). */
    val stats: com.pdm.app.data.model.TaskStatisticsDto? = null,
    val statsLoading: Boolean = false,
    val statsError: String? = null,
)

/**
 * Full task history with server-side filters and paging (F1), for remote or host tasks. Every
 * request carries a generation; an answer for an older query or a closed screen is dropped, so a
 * slow page can never land in a newer filter's list. A failed page keeps what was already there.
 */
class TaskHistoryController(
    private val fetch: suspend (TaskFilters) -> HistoryPage,
    /** Result counts for a filter; null where the screen shows none (the PDM machine's tasks). */
    private val stats: (suspend (TaskFilters) -> com.pdm.app.data.model.TaskStatisticsDto)? = null,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    val pageSize: Int = PAGE,
) {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()
    private var generation = 0L

    /** Run [query] from its first page. Invalid queries are not sent. */
    suspend fun apply(query: HistoryQuery) {
        query.validate()?.let { reason ->
            _state.update { it.copy(error = reason) }
            return
        }
        val gen = ++generation
        _state.value = HistoryState(query = query, loading = true, statsLoading = stats != null)
        kotlinx.coroutines.coroutineScope {
            launch { load(gen, query, start = 0) }
            launch { loadStats(gen, query) }
        }
    }

    /** The next page of the current query. */
    suspend fun loadMore() {
        val st = _state.value
        if (st.loading || st.endReached || !st.loaded) return
        val gen = generation
        _state.update { it.copy(loading = true, error = null) }
        load(gen, st.query, start = st.consumed)
    }

    /** Retry after a failure: the page that failed, or the first one. */
    suspend fun retry() {
        val st = _state.value
        when {
            !st.loaded -> apply(st.query)
            st.error != null -> loadMore()
            st.statsError != null -> {
                _state.update { it.copy(statsLoading = true, statsError = null) }
                loadStats(generation, st.query)
            }
        }
    }

    /** The screen closed: drop any answer still on its way. */
    fun close() {
        generation++
    }

    private suspend fun load(gen: Long, query: HistoryQuery, start: Long) {
        val page = try {
            fetch(query.toFilters(start, pageSize, nowEpochSeconds(), zone()))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (gen == generation) _state.update { it.copy(loading = false, error = e.message ?: "Tasks didn't load.") }
            return
        }
        if (gen != generation) return
        _state.update { s ->
            val merged = (s.tasks + page.tasks).distinctBy { it.upid }
            s.copy(
                tasks = merged,
                consumed = start + page.tasks.size,
                // PDM sends no total for this list: a short page is the end.
                endReached = page.tasks.size < pageSize,
                loading = false,
                loaded = true,
                error = null,
                cachedAt = listOfNotNull(s.cachedAt, page.cachedAt).minOrNull(),
            )
        }
    }

    /** Counts for [query]: `limit=0` so PDM counts every match, not just its default first 50. */
    private suspend fun loadStats(gen: Long, query: HistoryQuery) {
        val read = stats ?: return
        val counts = try {
            read(query.toFilters(0, 0, nowEpochSeconds(), zone()).copy(start = null))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (gen == generation) _state.update { it.copy(statsLoading = false, statsError = e.message ?: "Counts didn't load.") }
            return
        }
        if (gen == generation) _state.update { it.copy(stats = counts, statsLoading = false, statsError = null) }
    }

    companion object {
        const val PAGE = 50
    }
}
