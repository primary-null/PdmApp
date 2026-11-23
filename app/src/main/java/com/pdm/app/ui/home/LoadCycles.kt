package com.pdm.app.ui.home

import com.pdm.app.data.api.ApiActivity
import com.pdm.app.ui.NoteKind
import com.pdm.app.ui.NoteTone
import com.pdm.app.ui.SyncNote

/**
 * One fleet load as the LIVE panel shows it: what started it, how it ended, the requests it made.
 * Back-to-back identical routine loads fold into one row ([repeats], [at] to [until]).
 */
data class LoadCycle(
    val at: Long,
    val until: Long,
    val trigger: String,
    val result: String?,
    val tone: NoteTone,
    val requests: List<ApiActivity>,
    val repeats: Int = 1,
)

/** Requests that belong to a load start shortly before its trigger and end by its result. */
private const val LEAD_MS = 100L
private const val TAIL_MS = 400L

/**
 * Group the journal into loads, newest first, at most [limit] rows. A load is a TRIGGER note and
 * the first RESULT after it; requests are matched by time. Consecutive loads with the same trigger
 * and result fold together, so a quiet half hour is one row, not sixty.
 */
fun loadCycles(notes: List<SyncNote>, requests: List<ApiActivity>, limit: Int = 6): List<LoadCycle> {
    val triggers = notes.withIndex().filter { it.value.kind == NoteKind.TRIGGER }
    val raw = triggers.mapIndexed { n, (index, trigger) ->
        val nextAt = triggers.getOrNull(n + 1)?.value?.at ?: Long.MAX_VALUE
        val result = notes.drop(index + 1).firstOrNull { it.kind == NoteKind.RESULT && it.at < nextAt }
        val end = (result?.at?.plus(TAIL_MS) ?: nextAt).coerceAtMost(nextAt)
        LoadCycle(
            at = trigger.at,
            until = result?.at ?: trigger.at,
            trigger = trigger.text,
            result = result?.text,
            tone = result?.tone ?: NoteTone.NEUTRAL,
            requests = requests.filter { it.startedAt >= trigger.at - LEAD_MS && it.startedAt < end },
        )
    }
    val folded = ArrayList<LoadCycle>()
    for (cycle in raw) {
        val last = folded.lastOrNull()
        if (last != null && last.trigger == cycle.trigger && last.result == cycle.result && last.tone == cycle.tone) {
            // Keep the newest requests to show, the earliest start and the latest end.
            folded[folded.lastIndex] = cycle.copy(at = last.at, repeats = last.repeats + 1)
        } else {
            folded += cycle
        }
    }
    return folded.takeLast(limit).asReversed()
}

/** Requests no shown load accounts for: the detail pane's own reads (guest status, history, logs). */
fun otherRequests(requests: List<ApiActivity>, cycles: List<LoadCycle>): List<ApiActivity> {
    val claimed = cycles.flatMap { it.requests }.map { it.id }.toSet()
    return requests.filter { it.id !in claimed }
}

/** Notable events, newest first, repeated lines folded, at most [limit]. */
fun notableEvents(notes: List<SyncNote>, limit: Int = 4): List<SyncNote> {
    val events = notes.filter { it.kind == NoteKind.EVENT }
    val folded = events.filterIndexed { i, note -> i == 0 || events[i - 1].text != note.text }
    return folded.takeLast(limit).asReversed()
}
