package com.pdm.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.ops.HistoryOutcome
import com.pdm.app.data.ops.HistoryQuery
import com.pdm.app.data.ops.HistoryRange
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.util.formatTimestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The Tasks screen (F1): filters the server applies, pages from the server, and where they stand. */
internal fun LazyListScope.taskHistoryItems(state: FleetUiState, vm: FleetViewModel) {
    val h = state.taskHistory
    val search = state.historySearch.trim()
    val shown = if (search.isEmpty()) h.tasks else h.tasks.filter {
        it.headline.contains(search, ignoreCase = true) || it.user.contains(search, ignoreCase = true) ||
            it.remoteId.contains(search, ignoreCase = true) || it.node.contains(search, ignoreCase = true)
    }
    item(key = "history-results") { ResultsPlate(state, vm) }
    item(key = "history-filters") { HistoryFilters(state, vm) }
    item(key = "history-status") {
        val applied = summary(h.query)
        MutedLine(
            buildString {
                append(if (applied.isEmpty()) "All tasks" else applied)
                if (h.loaded) append(" · ${h.tasks.size} loaded" + if (h.endReached) "" else ", more on the server")
                h.cachedAt?.let { append(" · saved data from ${formatTimestamp(it / 1000)}") }
            },
        )
    }
    if (h.tasks.isNotEmpty()) {
        item(key = "history-search") {
            OutlinedTextField(
                value = state.historySearch,
                onValueChange = vm::setHistorySearch,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search loaded tasks") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
        }
    }
    when {
        !h.loaded && h.loading -> item(key = "history-loading") { EmptyHint("Loading tasks") }
        !h.loaded && h.error != null -> item(key = "history-error") { HistoryError(h.error, vm) }
        h.loaded && h.tasks.isEmpty() -> item(key = "history-empty") { EmptyHint("No tasks match these filters.") }
        else -> {
            items(shown, key = { detailItemKey("history/${it.upid}") }) { task ->
                TaskRow(task, onClick = { vm.openTask(task) })
            }
            if (search.isNotEmpty() && shown.isEmpty()) item(key = "history-nohit") { EmptyHint("No loaded task matches \"$search\".") }
        }
    }
    if (h.loaded) {
        item(key = "history-footer") {
            when {
                h.error != null -> HistoryError(h.error, vm)
                h.loading -> MutedLine("Loading more")
                h.endReached -> MutedLine("End of history for these filters.")
                else -> TechActionPlate(label = "Load more", onClick = vm::loadMoreHistory)
            }
        }
    }
}

@Composable
private fun HistoryError(message: String, vm: FleetViewModel) {
    TechPlate(railColor = MaterialTheme.colorScheme.error) {
        Text(message, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(6.dp))
        TechActionPlate(label = "Retry", onClick = vm::retryHistory)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilters(state: FleetUiState, vm: FleetViewModel) {
    val draft = state.historyDraft
    var picking by remember { mutableStateOf(false) }
    TechPlate(railColor = TechColors.Edge) {
        Label("STATUS")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryOutcome.entries.forEach { o ->
                TechActionPlate(label = o.label, onClick = { vm.pickHistory { it.copy(outcome = o) } }, emphasized = draft.outcome == o)
            }
        }
        if (state.remotes.size > 1) {
            Spacer(Modifier.height(10.dp))
            Label("REMOTE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TechActionPlate(label = "All", onClick = { vm.pickHistory { it.copy(remote = null) } }, emphasized = draft.remote == null)
                state.remotes.forEach { r ->
                    TechActionPlate(label = r.name, onClick = { vm.pickHistory { it.copy(remote = r.id) } }, emphasized = draft.remote == r.id)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Label("WHEN")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                HistoryRange.AnyTime to "Any time",
                HistoryRange.LastHours(24) to "Last 24 h",
                HistoryRange.LastHours(24 * 7) to "Last 7 days",
                HistoryRange.LastHours(24 * 30) to "Last 30 days",
            ).forEach { (range, label) ->
                TechActionPlate(label = label, onClick = { vm.pickHistory { it.copy(range = range) } }, emphasized = draft.range == range)
            }
            val days = draft.range as? HistoryRange.Days
            TechActionPlate(label = days?.let { dayLabel(it) } ?: "Dates", onClick = { picking = true }, emphasized = days != null)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.type,
                onValueChange = { v -> vm.editHistory { it.copy(type = v.take(40)) } },
                label = { Text("Type contains") },
                placeholder = { Text("vzdump", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
            OutlinedTextField(
                value = draft.user,
                onValueChange = { v -> vm.editHistory { it.copy(user = v.take(64)) } },
                label = { Text("User contains") },
                placeholder = { Text("root@pam", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
        }
        val invalid = draft.validate()
        invalid?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val typed = draft.type.trim() != state.taskHistory.query.type.trim() || draft.user.trim() != state.taskHistory.query.user.trim()
            if (typed) TechActionPlate(label = "Apply", onClick = vm::applyHistory, emphasized = true, enabled = invalid == null)
            if (!state.taskHistory.query.isDefault || typed) TechActionPlate(label = "Reset", onClick = vm::resetHistory)
        }
    }
    if (picking) {
        val initial = draft.range as? HistoryRange.Days
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = initial?.from?.toUtcMillis(),
            initialSelectedEndDateMillis = initial?.to?.toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TechActionPlate(
                    label = "Use dates",
                    emphasized = true,
                    enabled = picker.selectedStartDateMillis != null,
                    onClick = {
                        val from = picker.selectedStartDateMillis?.toLocalDate()
                        val to = picker.selectedEndDateMillis?.toLocalDate() ?: from
                        if (from != null && to != null) vm.pickHistory { it.copy(range = HistoryRange.Days(from, to)) }
                        picking = false
                    },
                )
            },
            dismissButton = { TechActionPlate(label = "Cancel", onClick = { picking = false }) },
        ) {
            DateRangePicker(state = picker, modifier = Modifier.height(480.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = TechColors.Edge,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary,
)

private val DAY = DateTimeFormatter.ofPattern("MMM d")

private fun dayLabel(d: HistoryRange.Days): String =
    if (d.from == d.to) DAY.format(d.from) else "${DAY.format(d.from)} – ${DAY.format(d.to)}"

/** The date picker works in UTC midnights; the filter works in local days. */
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** The applied filters in a few words. */
internal fun summary(q: HistoryQuery): String = listOfNotNull(
    q.outcome.label.takeIf { q.outcome != HistoryOutcome.ALL },
    q.remote,
    when (val r = q.range) {
        HistoryRange.AnyTime -> null
        is HistoryRange.LastHours -> if (r.hours % 24 == 0) "last ${r.hours / 24} days".replace("last 1 days", "last 24 h") else "last ${r.hours} h"
        is HistoryRange.Days -> dayLabel(r)
    },
    q.type.trim().takeIf { it.isNotEmpty() }?.let { "type $it" },
    q.user.trim().takeIf { it.isNotEmpty() }?.let { "user $it" },
).joinToString(" · ")

/**
 * Task results for the filters below (finished tasks only; PDM doesn't count running ones), by
 * remote and by task type. A row narrows the list to that remote or type.
 */
@Composable
private fun ResultsPlate(state: FleetUiState, vm: FleetViewModel) {
    val h = state.taskHistory
    val stats = h.stats
    TechPlate(railColor = TechColors.Edge) {
        Text("TASK RESULTS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
        when {
            h.query.outcome == HistoryOutcome.RUNNING -> MutedLine("Counted once they finish.")
            stats == null && h.statsLoading -> MutedLine("Counting")
            stats == null && h.statsError != null -> {
                MutedLine("Counts didn't load.")
                Spacer(Modifier.height(4.dp))
                TechActionPlate(label = "Retry", onClick = vm::retryHistory)
            }
            stats == null -> Unit
            stats.byRemote.isEmpty() && stats.byType.isEmpty() -> MutedLine("No finished tasks match.")
            else -> {
                val remotes = stats.byRemote.toList().sortedByDescending { it.second.total }
                val types = stats.byType.toList()
                    .sortedWith(compareByDescending<Pair<String, com.pdm.app.data.model.TaskCountsDto>> { it.second.error }.thenByDescending { it.second.total })
                if (remotes.size > 1 || h.query.remote == null) {
                    Spacer(Modifier.height(6.dp))
                    Text("BY REMOTE", fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
                    remotes.forEach { (name, c) -> ResultRow(name, c) { vm.pickHistory { it.copy(remote = name) } } }
                }
                Spacer(Modifier.height(6.dp))
                Text("BY TYPE", fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
                types.take(8).forEach { (name, c) -> ResultRow(name, c) { vm.pickHistory { it.copy(type = name) } } }
                if (types.size > 8) MutedLine("${types.size - 8} more types")
            }
        }
    }
}

/** One remote or type: the same color for every row; amber only where something failed. */
@Composable
private fun ResultRow(name: String, c: com.pdm.app.data.model.TaskCountsDto, onClick: () -> Unit) {
    SpecLine(
        name,
        resultLine(c),
        valueColor = if (c.error > 0) TechColors.Amber else MaterialTheme.colorScheme.onSurface,
        onClick = onClick,
    )
}

/** "40 OK · 2 warnings · 1 failed · 1 unknown", in the words the status chips use. */
internal fun resultLine(c: com.pdm.app.data.model.TaskCountsDto): String = listOfNotNull(
    "${c.ok} OK",
    "${c.warning} ${if (c.warning == 1) "warning" else "warnings"}".takeIf { c.warning > 0 },
    "${c.error} failed".takeIf { c.error > 0 },
    "${c.unknown} unknown".takeIf { c.unknown > 0 },
).joinToString(" · ")
