package com.pdm.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.model.PdmHost
import androidx.compose.material3.OutlinedTextField
import com.pdm.app.data.model.canOpenHostShell
import com.pdm.app.data.model.canRestartHost
import com.pdm.app.data.model.canCheckHostUpdates
import com.pdm.app.data.model.canEditSharedNotes
import com.pdm.app.data.ops.HistoryOutcome
import com.pdm.app.data.model.canReadHostTasks
import com.pdm.app.data.model.canReadHostLogs
import com.pdm.app.data.model.ReadAvailability
import com.pdm.app.data.model.TaskCountsDto
import com.pdm.app.data.model.TaskStatisticsDto
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import androidx.compose.foundation.layout.Arrangement
import com.pdm.app.ui.components.TechSectionLabel
import com.pdm.app.ui.components.TechUsageBar
import com.pdm.app.ui.util.formatBytes
import com.pdm.app.ui.util.formatPercent
import com.pdm.app.ui.util.formatRatio
import com.pdm.app.ui.util.formatUptime
import com.pdm.app.ui.util.fractionOf

/** PDM's licence verdicts, said plainly and without alarm. */
internal fun subscriptionLabel(state: String): String = when (state.lowercase()) {
    "none", "notfound", "" -> "community (none)"
    "invalid" -> "community (none)"
    "active" -> "active"
    else -> state
}

/** The manager itself, listed with the remotes it manages. */
@Composable
internal fun HostPlate(host: PdmHost, onClick: () -> Unit) {
    val pending = host.updates?.size ?: 0
    val attention = pending > 0 || host.pendingKernel != null
    TechPlate(railColor = if (attention) TechColors.Amber else TechColors.LinkGreen, modifier = Modifier.clickable(onClick = onClick)) {
        Text(host.node, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        MutedLine(
            listOfNotNull(
                "PDM",
                host.version?.display,
                "this manager",
                "$pending updates".takeIf { pending > 0 },
                "restart needed".takeIf { host.pendingKernel != null },
            ).joinToString(" · "),
        )
    }
}

@Composable
internal fun HostUpdatesRow(host: PdmHost, onClick: () -> Unit) {
    val pending = host.updates?.size ?: 0
    TechPlate(railColor = if (pending > 0) TechColors.Amber else TechColors.LinkGreen, modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(host.node, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                MutedLine(listOfNotNull("PDM host", host.version?.display).joinToString(" · "))
                if (host.pendingKernel != null) {
                    Text("restart needed", fontFamily = FontFamily.Monospace, color = TechColors.Amber, style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    host.updates?.size?.toString() ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = if (pending > 0) TechColors.Amber else TechColors.LinkGreen,
                )
                MutedLine(if (pending == 1) "update" else "updates")
            }
        }
    }
}

internal fun LazyListScope.hostItems(state: FleetUiState, vm: FleetViewModel) {
    val host = state.host
    item { DetailHeader(host?.node ?: "PDM host", host?.updates?.size, onBack = vm::popFocus, hero = true) }
    if (host == null) {
        item { EmptyHint("The PDM host did not answer (needs System.Audit).") }
        return
    }
    val st = host.status
    item {
        TechPlate(railColor = TechColors.LinkGreen) {
            Text(host.node, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            SpecLine("Role", "Proxmox Datacenter Manager")
            host.version?.let { SpecLine("Version", it.display) }
            st?.kernel?.release?.let { SpecLine("Kernel", it) }
            st?.cpuinfo?.let { c -> SpecLine("CPU", listOfNotNull(c.cpus?.let { "$it vCPU" }, c.model).joinToString(" · ")) }
            st?.cpu?.let { SpecLine("CPU load", formatPercent(it) + (st.loadavg.takeIf { l -> l.isNotEmpty() }?.let { l -> " · load ${l.joinToString(" ")}" } ?: "")) }
            st?.uptime?.let { SpecLine("Uptime", formatUptime(it)) }
            host.subscription?.status?.let { SpecLine("Subscription", subscriptionLabel(it), valueColor = TechColors.Mute) }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(end = BAR_CORNER_CLEARANCE)) {
                st?.memory?.let { TechUsageBar("MEM", fractionOf(it.used, it.total), formatRatio(it.used, it.total), capped = true) }
                st?.root?.let { TechUsageBar("ROOT", fractionOf(it.used, it.total), formatRatio(it.used, it.total), capped = true) }
                st?.swap?.takeIf { (it.total ?: 0) > 0 }?.let { TechUsageBar("SWAP", fractionOf(it.used, it.total), formatRatio(it.used, it.total), capped = true) }
            }
        }
    }
    item { SectionNote(state, vm, com.pdm.app.data.ops.FleetSection.HOST_STATUS, com.pdm.app.data.ops.FleetSection.HOST_SUBSCRIPTION) }
    host.pendingKernel?.let { kernel ->
        item {
            TechPlate(railColor = TechColors.Amber) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RESTART NEEDED", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TechColors.Amber)
                        MutedLine(
                            "A new kernel ($kernel) is installed. ${host.node} keeps running " +
                                "${com.pdm.app.data.KernelCheck.build(st?.kernel?.release) ?: "the old one"} until it restarts.",
                        )
                    }
                    if (!state.readOnly && state.writable.canRestartHost()) {
                        TechActionPlate(label = "Restart", onClick = vm::requestHostRestart, emphasized = true)
                    }
                }
            }
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.readOnly && state.writable.canCheckHostUpdates()) {
                TechActionPlate(label = "Check now", onClick = vm::requestHostCheckNow)
            }
            if (!state.readOnly && state.writable.canOpenHostShell() && state.shellAllowed) {
                TechActionPlate(label = "Terminal", onClick = vm::requestHostTerminal)
            }
        }
    }
    if (!state.shellAllowed && !state.readOnly && state.writable.canOpenHostShell()) {
        item {
            MutedLine("Terminal requires password authentication (API tokens cannot open interactive terminal sessions).")
        }
    }

    val pkgs = host.updates
    item { TechSectionLabel("Pending on ${host.node}", pkgs?.size) }
    item { SectionNote(state, vm, com.pdm.app.data.ops.FleetSection.HOST_UPDATES) }
    if (!pkgs.isNullOrEmpty() && !state.readOnly && state.writable.canOpenHostShell() && state.shellAllowed) {
        item {
            TechPlate(railColor = TechColors.Amber) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        MutedLine("Opens a terminal to upgrade ${host.node}.")
                    }
                    TechActionPlate(label = "Upgrade", onClick = vm::requestHostUpgrade, emphasized = true)
                }
            }
        }
    }
    when {
        pkgs == null -> item { EmptyHint("Package list did not load.") }
        pkgs.isEmpty() -> item { EmptyHint("Nothing pending.") }
        else -> items(pkgs, key = { detailItemKey("hostpkg/${it.name}") }) { pkg ->
            val open = state.hostUpdatesOpenPackage == pkg.name
            val changelog = state.hostUpdatesChangelog.takeIf { open }
            PackageRow(pkg, open = open, changelog = changelog, onClick = { vm.toggleHostPackage(pkg.name) })
        }
    }

    // Host graph (F2)
    item {
        HostUsagePlate(
            points = state.hostRrdData,
            timeframe = state.hostRrdTimeframe,
            loading = state.hostRrdLoading,
            onTimeframe = vm::setHostRrdTimeframe,
        )
    }
    state.hostRrdError?.let { err ->
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(err, Modifier.weight(1f), fontFamily = FontFamily.Monospace, color = TechColors.Amber, style = MaterialTheme.typography.labelSmall)
                TechActionPlate(label = "Retry", onClick = { vm.loadHostRrd() })
            }
        }
    }

    // Shared PDM notes (F3)
    item { TechSectionLabel("Shared notes") }
    item { SharedNotesPlate(state, vm) }

    // The machine's own tasks (F2)
    if (state.writable.canReadHostTasks()) hostTaskItems(state, vm)

    // The machine's journal (F2)
    if (state.writable.canReadHostLogs()) hostLogItems(state, vm)
}

private fun LazyListScope.hostTaskItems(state: FleetUiState, vm: FleetViewModel) {
    val h = state.hostTaskHistory
    item(key = "hosttasks-label") { TechSectionLabel("Tasks on this machine", h.tasks.size.takeIf { h.loaded }) }
    item(key = "hosttasks-filter") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(HistoryOutcome.ALL, HistoryOutcome.FAILED, HistoryOutcome.RUNNING).forEach { o ->
                TechActionPlate(label = o.label, onClick = { vm.setHostTaskOutcome(o) }, emphasized = h.query.outcome == o)
            }
        }
    }
    when {
        !h.loaded && h.loading -> item(key = "hosttasks-loading") { EmptyHint("Loading tasks") }
        !h.loaded && h.error != null -> item(key = "hosttasks-error") { RetryLine(h.error, vm::retryHostTasks) }
        h.loaded && h.tasks.isEmpty() -> item(key = "hosttasks-empty") { EmptyHint("No tasks match.") }
        else -> items(h.tasks, key = { detailItemKey("hosttask/${it.upid}") }) { task ->
            TaskRow(task, onClick = { vm.openTask(task) })
        }
    }
    if (h.loaded && h.tasks.isNotEmpty()) {
        item(key = "hosttasks-footer") {
            when {
                h.error != null -> RetryLine(h.error, vm::retryHostTasks)
                h.loading -> MutedLine("Loading more")
                h.endReached -> Unit
                else -> TechActionPlate(label = "Load more", onClick = vm::loadMoreHostTasks)
            }
        }
    }
}

/** Lines shown per list row, so a long window doesn't become one huge text block. */
private const val HOST_LOG_CHUNK = 50

private fun LazyListScope.hostLogItems(state: FleetUiState, vm: FleetViewModel) {
    val logs = state.hostLogs
    item(key = "hostlogs-label") { TechSectionLabel("Logs", logs.lines.size.takeIf { logs.requested && !logs.loading }) }
    item(key = "hostlogs-controls") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.hostLogsService,
                onValueChange = vm::setHostLogsService,
                modifier = Modifier.weight(1f),
                label = { Text("Service") },
                placeholder = { Text("All services") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            TechActionPlate(
                label = if (logs.loading) "Reading" else if (logs.requested) "Read again" else "Read",
                onClick = vm::readHostLogs,
                enabled = !state.readOnly && !logs.busy,
                emphasized = !logs.requested,
            )
        }
    }
    if (state.readOnly && !logs.requested) {
        item(key = "hostlogs-offline") { MutedLine("Logs aren't kept on this device. They can be read once the server answers.") }
        return
    }
    if (!logs.requested) return
    if (logs.lines.isEmpty()) {
        item(key = "hostlogs-empty") {
            when {
                logs.loading -> EmptyHint("Reading logs")
                logs.error != null -> RetryLine(logs.error, vm::retryHostLogs)
                else -> EmptyHint("No log lines match.")
            }
        }
        return
    }
    item(key = "hostlogs-earlier") {
        when {
            logs.loadingEarlier -> MutedLine("Loading earlier lines")
            logs.hasEarlier -> TechActionPlate(label = "Load earlier", onClick = vm::earlierHostLogs, enabled = !logs.busy)
            logs.atStart -> MutedLine("Start of the log.")
        }
    }
    val chunks = logs.lines.chunked(HOST_LOG_CHUNK)
    items(chunks.size, key = { "hostlogs-chunk-$it" }) { index ->
        TechPlate(railColor = TechColors.Edge) {
            SelectionContainer {
                Text(chunks[index].joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    item(key = "hostlogs-newer") {
        Column {
            logs.error?.let { RetryLine(it, vm::retryHostLogs) }
            when {
                logs.loadingNewer -> MutedLine("Loading newer lines")
                else -> TechActionPlate(label = "Load newer", onClick = vm::newerHostLogs, enabled = !logs.busy)
            }
        }
    }
}

@Composable
private fun RetryLine(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), fontFamily = FontFamily.Monospace, color = TechColors.Amber, style = MaterialTheme.typography.labelSmall)
        TechActionPlate(label = "Retry", onClick = onRetry)
    }
}

/**
 * The notes every PDM user shares (F3). Shown as plain text, exactly as written (Markdown is not
 * rendered, and links are not followed). Editing saves against the copy it started from.
 */
@Composable
private fun SharedNotesPlate(state: FleetUiState, vm: FleetViewModel) {
    val n = state.notes
    val canEdit = !state.readOnly && state.writable.canEditSharedNotes()
    TechPlate(railColor = if (n.conflict != null || n.error != null) TechColors.Amber else TechColors.Edge) {
        when {
            n.server == null && n.loading -> MutedLine("Reading notes")
            n.server == null && n.trouble == com.pdm.app.data.ops.NotesTrouble.FORBIDDEN ->
                MutedLine("You can't read the shared notes.")
            n.server == null && state.readOnly -> MutedLine("Notes aren't kept on this device. They show once the server answers.")
            n.server == null && n.error != null -> MutedLine("Notes didn't load.")
            n.conflict != null -> NotesConflictView(n, vm)
            n.editing -> {
                OutlinedTextField(
                    value = n.draft.orEmpty(),
                    onValueChange = vm::updateNotesDraft,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    label = { Text("Notes (Markdown)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TechActionPlate(
                        label = if (n.saving) "Saving" else "Save",
                        onClick = vm::saveSharedNotes,
                        emphasized = true,
                        enabled = n.canSave && canEdit,
                    )
                    TechActionPlate(label = "Discard changes", onClick = vm::cancelEditingSharedNotes, enabled = !n.saving)
                    if (n.saveUnconfirmed) TechActionPlate(label = "Reload", onClick = vm::loadSharedNotes)
                }
            }
            else -> {
                val text = n.server?.text.orEmpty()
                if (text.isBlank()) MutedLine("No shared notes yet.") else Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canEdit && n.server != null) TechActionPlate(label = "Edit", onClick = vm::startEditingSharedNotes)
                    TechActionPlate(label = if (n.loading) "Reading" else "Reload", onClick = vm::loadSharedNotes, enabled = !n.loading && !state.readOnly)
                }
            }
        }
        n.error?.takeIf { n.server != null || n.trouble != com.pdm.app.data.ops.NotesTrouble.FORBIDDEN }?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text(err, fontFamily = FontFamily.Monospace, color = TechColors.Amber, style = MaterialTheme.typography.labelSmall)
            if (n.server == null) {
                Spacer(Modifier.height(4.dp))
                TechActionPlate(label = "Retry", onClick = vm::loadSharedNotes)
            }
        }
    }
}

@Composable
private fun NotesConflictView(n: com.pdm.app.data.ops.NotesEditorState, vm: FleetViewModel) {
    Text("SOMEONE ELSE SAVED THESE NOTES", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TechColors.Amber)
    MutedLine("Your changes are kept below. Compare, then choose.")
    Spacer(Modifier.height(8.dp))
    Text("THEIR VERSION", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = TechColors.Mute)
    Text(
        n.conflict?.theirs?.text?.ifBlank { "(empty)" } ?: "Their version couldn't be read.",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text("YOUR VERSION", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = TechColors.Mute)
    SelectionContainer {
        Text(n.draft.orEmpty().ifBlank { "(empty)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TechActionPlate(label = "Keep theirs", onClick = vm::notesKeepTheirs)
        if (n.conflict?.theirs != null) {
            TechActionPlate(label = "Keep editing mine", onClick = vm::notesReplaceTheirs, emphasized = true)
        } else {
            TechActionPlate(label = "Reload", onClick = vm::loadSharedNotes)
        }
    }
}
