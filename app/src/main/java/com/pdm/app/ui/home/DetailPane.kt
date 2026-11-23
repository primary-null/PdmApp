package com.pdm.app.ui.home

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.model.FleetDatastore
import com.pdm.app.data.model.FleetNetwork
import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.FleetStorage
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.GuestAction
import com.pdm.app.data.model.LeaderEntry
import com.pdm.app.data.model.GuestSnapshot
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.data.model.RemoteTask
import com.pdm.app.data.model.canManageGuest
import com.pdm.app.data.model.canManageRemote
import com.pdm.app.data.model.canMigrateGuest
import com.pdm.app.data.model.canStopTask
import com.pdm.app.data.model.OperationReceipt
import com.pdm.app.data.model.OperationSubmissionState
import com.pdm.app.data.model.isValidSnapshotName
import com.pdm.app.data.model.TaskPhase
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.GuestView
import com.pdm.app.ui.TaskView
import com.pdm.app.ui.components.LeaderRankRow
import com.pdm.app.ui.components.ProtocolTag
import com.pdm.app.ui.components.Protocols
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.components.TechSectionLabel
import com.pdm.app.ui.components.TechSwitch
import com.pdm.app.ui.components.techRailColor
import com.pdm.app.ui.util.formatBytes
import com.pdm.app.ui.util.formatCores
import com.pdm.app.ui.util.formatPercent
import com.pdm.app.ui.util.formatTaskAge
import com.pdm.app.ui.util.formatTimestamp
import com.pdm.app.ui.util.formatRatio
import com.pdm.app.ui.util.formatUptime

internal fun LazyListScope.detailPane(
    state: FleetUiState,
    guests: List<Guest>,
    nodes: List<FleetNode>,
    storages: List<FleetStorage>,
    datastores: List<FleetDatastore>,
    networks: List<FleetNetwork>,
    vm: FleetViewModel,
    focus: DashFocus = state.nav.focus,
) {
    val remoteId = state.selectedRemoteId
    when (focus) {
        DashFocus.Guests -> {
            val hits = if (state.query.isNotBlank()) {
                fleetSearch(state.query, state.remotes, state.nodes, state.storages, state.guests, remoteId)
            } else {
                null
            }
            item { TechSectionLabel(if (hits == null) "Guests" else "Results", hits?.total ?: guests.size) }
            val unreachable = state.remotes.filter { remote ->
                (remoteId == null || remote.id == remoteId) &&
                    (!remote.error.isNullOrBlank() || remote.health.equals("Error", true))
            }
            if (unreachable.isNotEmpty()) {
                // A failing remote contributes no guests; say so instead of silently showing fewer.
                item { UnreachableNotice(unreachable) }
            }
            item { QueryPlate(state.query, vm::setQuery) }
            if (hits == null) {
                items(guests, key = { detailItemKey(it.id) }) { guest ->
                    GuestRow(guest, state, vm, onOpen = { vm.openFocus(DashFocus.GuestDetail(guest.id)) })
                }
            } else {
                searchResultItems(hits, state, vm)
            }
        }
        DashFocus.Nodes -> {
            item { DetailHeader(focus.title, nodes.size, onBack = vm::popFocus) }
            if (nodes.isEmpty()) {
                item { EmptyHint("No nodes in this filter.") }
            } else {
                items(nodes, key = { detailItemKey(it.id) }) { node ->
                    NodeRow(
                        node,
                        onClick = { vm.openFocus(DashFocus.NodeDetail(node.id)) },
                        largestMem = state.nodes.maxOfOrNull { it.maxmem ?: 0L },
                        largestCores = state.nodes.maxOfOrNull { it.maxcpu ?: 0.0 },
                    )
                }
            }
        }
        DashFocus.Storage -> {
            item { DetailHeader(focus.title, storages.size, onBack = vm::popFocus) }
            if (storages.isEmpty()) {
                item { EmptyHint("No storage pools in this filter.") }
            } else {
                items(storages, key = { detailItemKey(it.id) }) { storage ->
                    StorageRow(
                        storage,
                        onClick = { vm.openFocus(DashFocus.StorageDetail(storage.id)) },
                        largestDisk = state.storages.maxOfOrNull { it.maxdisk ?: 0L },
                    )
                }
            }
        }
        DashFocus.Datastores -> {
            item { DetailHeader(focus.title, datastores.size, onBack = vm::popFocus) }
            if (datastores.isEmpty()) {
                item { EmptyHint("No PBS datastores.") }
            } else {
                items(datastores, key = { detailItemKey(it.id) }) { ds ->
                    DatastoreRow(ds, onClick = { vm.openFocus(DashFocus.DatastoreDetail(ds.id)) })
                }
            }
        }
        DashFocus.Sdn -> {
            item { DetailHeader(focus.title, networks.size, onBack = vm::popFocus) }
            if (networks.isEmpty()) {
                item { EmptyHint("No SDN zones in this filter.") }
            } else {
                items(networks, key = { detailItemKey(it.id) }) { net ->
                    NetworkRow(net, onClick = { vm.openFocus(DashFocus.NetworkDetail(net.id)) })
                }
            }
        }
        DashFocus.Cpu -> {
            item { DetailHeader(focus.title, onBack = vm::popFocus) }
            val guestRanks = rankLeaders(state.leaders.guestCpu, state.guests, state.nodes, remoteId, LeaderMetric.GuestCpu)
            val nodeRanks = rankLeaders(state.leaders.nodeCpu, state.guests, state.nodes, remoteId, LeaderMetric.NodeCpu)
            if (guestRanks.isEmpty() && nodeRanks.isEmpty()) {
                item { EmptyHint("No CPU leaders for this timeframe.") }
            } else {
                rankSection("Guests by CPU", "cpu-g", guestRanks, guests, nodes, vm::openLeader)
                rankSection("Nodes by CPU", "cpu-n", nodeRanks, guests, nodes, vm::openLeader)
            }
        }
        DashFocus.Memory -> {
            item { DetailHeader(focus.title, onBack = vm::popFocus) }
            val memRanks = rankLeaders(state.leaders.nodeMemory, state.guests, state.nodes, remoteId, LeaderMetric.NodeMemory)
            if (memRanks.isEmpty()) {
                item { EmptyHint("No memory leaders for this timeframe.") }
            } else {
                rankSection("Nodes by memory", "mem-n", memRanks, guests, nodes, vm::openLeader)
            }
        }
        DashFocus.Faults -> {
            val faults = state.remotes.filter { remote ->
                !remote.error.isNullOrBlank() || remote.health.equals("Error", true)
            }
            item { DetailHeader(focus.title, faults.size, onBack = vm::popFocus) }
            if (faults.isEmpty()) {
                item { EmptyHint("No remote errors.") }
            } else {
                item {
                    RemoteList(
                        remotes = faults,
                        selectedRemoteId = remoteId,
                        onSelectRemote = vm::selectRemote,
                    )
                }
            }
        }
        DashFocus.Operations -> {
            val receipts = state.activeReceipts.sortedWith(compareBy<OperationReceipt> { it.isTerminal }.thenByDescending { it.submittedAt })
            item { DetailHeader("Operations", receipts.count { !it.isTerminal }, onBack = vm::popFocus, hero = true) }
            item { MutedLine("What this app sent to the server, and what became of it. Kept on this device until Clean Slate.") }
            if (receipts.isEmpty()) {
                item { EmptyHint("Nothing sent from this app yet.") }
            } else {
                items(receipts, key = { detailItemKey("receipt-${it.id}") }) { receipt ->
                    ReceiptRow(
                        receipt,
                        onOpen = { vm.openOperation(receipt.id) }.takeIf { receipt.upid != null },
                        onLookAgain = { vm.watchOperation(receipt.id) }.takeIf {
                            receipt.state == OperationSubmissionState.OBSERVATION_PAUSED ||
                                receipt.state == OperationSubmissionState.UNKNOWN
                        },
                    )
                }
            }
        }
        DashFocus.Tasks -> {
            if (state.activeReceipts.isNotEmpty()) {
                item { OperationsEntry(state.activeReceipts, onClick = { vm.openFocus(DashFocus.Operations) }) }
            }
            item { DetailHeader(focus.title, state.taskHistory.tasks.size.takeIf { state.taskHistory.loaded }, onBack = vm::popFocus, hero = true) }
            taskHistoryItems(state, vm)
        }
        is DashFocus.GuestDetail -> {
            val guest = state.guests.find { it.id == focus.id }
            item { DetailHeader(guest?.name ?: "Guest", onBack = vm::popFocus) }
            if (guest == null) {
                item { EmptyHint("Guest is gone from the last refresh.") }
            } else {
                item { GuestDetailCard(guest, state, vm) }
                state.guestView?.takeIf { it.guestId == guest.id }?.hardware?.takeIf { it.isNotEmpty() }?.let { lines ->
                    item {
                        TechPlate(railColor = TechColors.Edge) {
                            Text(
                                "HARDWARE",
                                fontFamily = FontFamily.Monospace,
                                color = TechColors.Mute,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.2.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            lines.forEach { SpecLine(it.label, it.value) }
                        }
                    }
                }
                item {
                    UsagePlate(
                        view = state.graphView?.takeIf { it.key == "guest:${guest.id}" },
                        node = false,
                        onTimeframe = vm::setGraphTimeframe,
                    )
                }
                item {
                    SnapshotPlate(
                        guest = guest,
                        view = state.guestView?.takeIf { it.guestId == guest.id },
                        canManage = state.writable.canManageGuest(guest.remoteId, guest.vmid),
                        vm = vm,
                    )
                }
            }
        }
        is DashFocus.NodeDetail -> {
            val node = state.nodes.find { it.id == focus.id }
            val onNode = state.guests.filter { guest ->
                guest.remoteId == node?.remoteId && guest.node == node.name
            }
            item { DetailHeader(node?.name ?: "Node", onBack = vm::popFocus) }
            if (node == null) {
                item { EmptyHint("Node is gone from the last refresh.") }
            } else {
                item { NodeSpecCard(node) }
                // Actionable first (pending updates), then history, then what lives on the box.
                state.updates?.firstOrNull { it.remoteId == node.remoteId && it.node == node.name }?.let { upd ->
                    item(key = detailItemKey("updates-${upd.id}")) { NodeUpdatesRow(upd, onClick = { vm.openFocus(DashFocus.NodeUpdates(upd.remoteId, upd.node)) }) }
                }
                item {
                    UsagePlate(
                        view = state.graphView?.takeIf { it.key == "node:${node.id}" },
                        node = true,
                        onTimeframe = vm::setGraphTimeframe,
                    )
                }
                val nodeRead = state.nodeNotes["${node.remoteId}/${node.name}"]
                nodeRead?.error?.let { err -> item { MutedLine(err) } }
                val nodeNotes = nodeRead?.text
                if (!nodeNotes.isNullOrBlank()) {
                    item {
                        TechPlate(railColor = TechColors.Edge) {
                            Text(
                                "NODE NOTES",
                                fontFamily = FontFamily.Monospace,
                                color = TechColors.Mute,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.2.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                nodeNotes,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                state.nodeInventory?.takeIf { it.first == node.id }?.second?.let { inv ->
                    nodeInventoryItems(node.id, inv)
                }
                item { TechSectionLabel("Guests on this node", onNode.size) }
                items(onNode, key = { detailItemKey(it.id) }) { guest ->
                    GuestRow(guest, state, vm, onOpen = { vm.openFocus(DashFocus.GuestDetail(guest.id)) })
                }
            }
        }
        is DashFocus.StorageDetail -> {
            val storage = state.storages.find { it.id == focus.id }
            item { DetailHeader(storage?.name ?: "Storage", onBack = vm::popFocus) }
            if (storage == null) {
                item { EmptyHint("Storage is gone from the last refresh.") }
            } else {
                item { StorageSpecCard(storage) }
            }
        }
        is DashFocus.DatastoreDetail -> {
            val ds = state.datastores.find { it.id == focus.id }
            item { DetailHeader(ds?.name ?: "Datastore", onBack = vm::popFocus) }
            if (ds == null) {
                item { EmptyHint("Datastore is gone from the last refresh.") }
            } else {
                item { DatastoreSpecCard(ds) }
            }
        }
        DashFocus.Updates -> updatesItems(state, vm)
        DashFocus.Host -> hostItems(state, vm)
        is DashFocus.RemoteDetail -> remoteDetailItems(state, focus, vm)
        is DashFocus.NodeUpdates -> nodeUpdatesItems(state, focus, vm)
        is DashFocus.TaskDetail -> {
            val view = state.taskView?.takeIf { it.task.upid == focus.upid }
            val task = view?.task ?: state.tasks?.firstOrNull { it.upid == focus.upid }
            item { DetailHeader(task?.headline ?: "Task", onBack = vm::popFocus) }
            if (task == null) {
                item { EmptyHint("Task is gone from the last refresh.") }
            } else {
                item { TaskRow(task) }
                item {
                    TaskControls(
                        view,
                        onStop = { vm.requestStopTask(task) }.takeIf { state.writable.canStopTask(task) },
                    )
                }
                taskLogItems(view, vm)
            }
        }
        is DashFocus.NetworkDetail -> {
            val net = state.networks.find { it.id == focus.id }
            item { DetailHeader(net?.name ?: "SDN", onBack = vm::popFocus) }
            if (net == null) {
                item { EmptyHint("Zone is gone from the last refresh.") }
            } else {
                item { NetworkSpecCard(net) }
            }
        }
    }
    item { Spacer(Modifier.height(16.dp)) }
}

private fun LazyListScope.rankSection(
    title: String,
    keyPrefix: String,
    rows: List<RankedLeader>,
    guests: List<Guest>,
    nodes: List<FleetNode>,
    onOpen: (LeaderEntry) -> Unit,
) {
    if (rows.isEmpty()) return
    item { TechSectionLabel(title, rows.size) }
    items(
        rows,
        key = { detailItemKey("$keyPrefix-${it.rank}-${it.entry.entityId ?: it.entry.name}") },
    ) { row ->
        LeaderRankRow(
            rank = row.rank,
            name = row.entry.name,
            subtitle = "${row.entry.remoteId} · ${row.entry.kind}",
            fraction = row.fraction,
            caption = row.caption,
            live = row.live,
            onClick = if (row.entry.toFocus(guests, nodes) != null) {
                { onOpen(row.entry) }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun DetailHeader(title: String, count: Int? = null, onBack: () -> Unit, hero: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TechActionPlate(label = "Back", onClick = onBack)
        if (hero) {
            Box(Modifier.weight(1f)) { TechSectionLabel(title) }
            count?.let { HeroCount(it) }
        } else {
            TechSectionLabel(title, count)
        }
    }
}

@Composable
private fun TaskControls(view: TaskView?, onStop: (() -> Unit)?) {
    val status = view?.status
    TechPlate(railColor = if (view?.running == true) TechColors.Amber else TechColors.Edge) {
        SpecLine(
            "Status",
            when {
                view == null || (view.loading && status == null) -> "loading…"
                view.stopping -> "stopping…"
                status == null -> "unknown"
                status.isRunning -> "running"
                else -> status.exitstatus ?: status.status ?: "stopped"
            },
        )
        view?.task?.let { task ->
            if (task.startTime > 0) SpecLine("Started", formatTimestamp(task.startTime))
            val end = task.endTime?.takeIf { it > 0 && view.running.not() }
            val seconds = (end ?: (System.currentTimeMillis() / 1000)) - task.startTime
            if (task.startTime > 0) SpecLine(if (end != null) "Took" else "Running for", formatUptime(seconds))
        }
        status?.user?.takeIf { it.isNotBlank() }?.let { SpecLine("User", it) }
        status?.node?.takeIf { it.isNotBlank() }?.let { SpecLine("Node", it) }
        view?.error?.let {
            Text(
                it,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (onStop != null && view?.running == true && !view.stopping) {
            Spacer(Modifier.height(8.dp))
            TechActionPlate(label = "Stop task", onClick = onStop)
        }
    }
}

/**
 * The task log (Q2): a header plate with the window and its controls, then the lines in chunks
 * keyed by line number, outside any plate, so reading back keeps its place when older lines are
 * added above. Load earlier / Load later read more; Follow keeps to the end.
 */
private fun LazyListScope.taskLogItems(view: TaskView?, vm: FleetViewModel) {
    val log = view?.log
    item(key = "tasklog-head") {
        TechPlate(railColor = TechColors.Edge) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LOG", fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp)
                Spacer(Modifier.weight(1f))
                if (log != null && log.lines.isNotEmpty()) {
                    Text(
                        "lines ${log.firstN}–${log.lastN}" + (log.total?.let { " of $it" } ?: ""),
                        fontFamily = FontFamily.Monospace,
                        color = TechColors.Mute,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            log?.error?.let { err ->
                Spacer(Modifier.height(6.dp))
                Text(err, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                TechActionPlate(label = "Retry", onClick = vm::retryTaskLog)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (log?.hasEarlier == true) {
                    TechActionPlate(label = if (log.loadingEarlier) "Loading earlier" else "Load earlier", onClick = vm::loadEarlierTaskLog, enabled = !log.loadingEarlier)
                }
                if (view?.running == true || log?.hasLater == true) {
                    TechActionPlate(
                        label = if (log?.follow == true) "Following" else "Follow",
                        onClick = vm::toggleFollowTaskLog,
                        emphasized = log?.follow == true,
                    )
                }
            }
            if (log == null || log.lines.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (log == null || log.loading) "Reading the log" else "No log output.",
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    val lines = log?.lines.orEmpty()
    if (lines.isEmpty()) return
    // Chunks by line number: stable keys across windows, so prepending keeps the scroll position.
    val chunks = lines.groupBy { (it.n - 1) / LOG_CHUNK }
    chunks.forEach { (chunk, rows) ->
        item(key = "tasklog-$chunk") {
            Text(
                rows.joinToString("\n") { it.t },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    if (log?.hasLater == true) {
        item(key = "tasklog-later") {
            TechActionPlate(label = if (log.loadingLater) "Loading later" else "Load later", onClick = vm::loadMoreTaskLog, enabled = !log.loadingLater)
        }
    }
    item(key = "tasklog-end") { Spacer(Modifier.height(1.dp)) }
}

/** Log lines per lazy item: small enough to scroll smoothly, big enough to keep items few. */
private const val LOG_CHUNK = 50L

@Composable
internal fun TaskRow(task: RemoteTask, onClick: (() -> Unit)? = null) {
    val rail = when (task.phase) {
        TaskPhase.Running -> TechColors.Amber
        TaskPhase.Ok -> TechColors.LinkGreen
        TaskPhase.Warning -> TechColors.Amber
        TaskPhase.Error -> MaterialTheme.colorScheme.error
        TaskPhase.Unknown -> TechColors.Mute
    }
    val label = when (task.phase) {
        TaskPhase.Running -> "RUNNING"
        TaskPhase.Ok -> "OK"
        TaskPhase.Warning -> "WARN"
        TaskPhase.Error -> task.status?.take(48) ?: "ERROR"
        TaskPhase.Unknown -> "UNKNOWN"
    }
    TechPlate(
        railColor = rail,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(task.headline, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(
            buildString {
                if (task.remoteId.isNotBlank()) append(task.remoteId).append(" · ")
                if (task.node.isNotBlank() && !task.node.equals(task.remoteId, ignoreCase = true)) {
                    append(task.node).append(" · ")
                }
                if (task.user.isNotBlank()) append(task.user).append(" · ")
                append(label)
                append(" · ")
                append(formatTaskAge(task.startTime))
            },
            fontFamily = FontFamily.Monospace,
            color = if (task.phase == TaskPhase.Error) MaterialTheme.colorScheme.error else TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun LazyListScope.searchResultItems(
    hits: FleetSearchHits,
    state: FleetUiState,
    vm: FleetViewModel,
) {
    if (hits.isEmpty) {
        item { EmptyHint("Nothing matches.") }
        return
    }
    if (hits.remotes.isNotEmpty()) {
        item { TechSectionLabel("Remotes", hits.remotes.size) }
        items(hits.remotes, key = { detailItemKey("search-remote/${it.id}") }) { remote ->
            RemoteList(
                remotes = listOf(remote),
                selectedRemoteId = state.selectedRemoteId,
                onSelectRemote = { vm.openFocus(DashFocus.RemoteDetail(remote.id)) },
            )
        }
    }
    if (hits.nodes.isNotEmpty()) {
        item { TechSectionLabel("Nodes", hits.nodes.size) }
        items(hits.nodes, key = { detailItemKey("search-node/${it.id}") }) { node ->
            NodeRow(
                node,
                onClick = { vm.openFocus(DashFocus.NodeDetail(node.id)) },
                largestMem = state.nodes.maxOfOrNull { it.maxmem ?: 0L },
                largestCores = state.nodes.maxOfOrNull { it.maxcpu ?: 0.0 },
            )
        }
    }
    if (hits.storages.isNotEmpty()) {
        item { TechSectionLabel("Storage", hits.storages.size) }
        items(hits.storages, key = { detailItemKey("search-storage/${it.id}") }) { storage ->
            StorageRow(
                storage,
                onClick = { vm.openFocus(DashFocus.StorageDetail(storage.id)) },
                largestDisk = state.storages.maxOfOrNull { it.maxdisk ?: 0L },
            )
        }
    }
    if (hits.guests.isNotEmpty()) {
        item { TechSectionLabel("Guests", hits.guests.size) }
        items(hits.guests, key = { detailItemKey(it.id) }) { guest ->
            GuestRow(guest, state, vm, onOpen = { vm.openFocus(DashFocus.GuestDetail(guest.id)) })
        }
    }
}

@Composable
private fun UnreachableNotice(remotes: List<RemoteNode>) {
    TechPlate(railColor = MaterialTheme.colorScheme.error) {
        Text(
            "NOT REACHABLE · guests missing from this list",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
        )
        remotes.forEach { remote ->
            MutedLine("${remote.name}: ${remote.error?.take(160) ?: remote.health.orEmpty()}")
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    TechPlate(railColor = TechColors.Mute) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun GuestDetailCard(guest: Guest, state: FleetUiState, vm: FleetViewModel) {
    val remote = state.remotes.firstOrNull { it.id == guest.remoteId }
    val node = state.nodes.find { it.remoteId == guest.remoteId && it.name == guest.node }
    val busy = guest.id in state.busyIds
    val live = state.guestView?.takeIf { it.guestId == guest.id }?.live
    val paused = live?.isPaused == true
    TechPlate(railColor = if (paused) TechColors.Amber else techRailColor(guest.status)) {
        Text(guest.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SpecLine("Status", if (paused) "paused (${live?.qmpstatus})" else guest.status)
        live?.lock?.takeIf { it.isNotBlank() }?.let { SpecLine("Lock", it, valueColor = TechColors.Amber) }
        SpecLine("Type", "${guest.type} ${guest.vmid}")
        if (guest.tags.isNotEmpty()) SpecLine("Tags", guest.tags.joinToString(" ") { "#$it" }, valueColor = TechColors.Amber)
        SpecLine(
            "Remote",
            remote?.name ?: guest.remoteId,
            valueColor = TechColors.Amber,
            onClick = { vm.filterRemote(guest.remoteId) },
        )
        if (!guest.node.isNullOrBlank()) {
            val nodeId = node?.id
            SpecLine(
                "Node",
                guest.node.orEmpty(),
                valueColor = if (nodeId != null) TechColors.Amber else TechColors.Mute,
                onClick = nodeId?.let { id -> { vm.openFocus(DashFocus.NodeDetail(id)) } },
            )
        }
        val usedCores = if (guest.cpu != null && guest.maxcpu != null) guest.cpu * guest.maxcpu else null
        SpecLine(
            "CPU",
            listOfNotNull(formatPercent(guest.cpu), guest.maxcpu?.let { formatCores(usedCores, it) })
                .joinToString(" · ")
                .ifBlank { "—" },
        )
        SpecLine("Memory", formatRatio(guest.mem, guest.maxmem))
        SpecLine(
            "Disk",
            if (guest.type == "qemu" && (guest.disk ?: 0L) == 0L) {
                if ((guest.maxdisk ?: 0L) > 0L) "${formatBytes(guest.maxdisk)} allocated" else "no disk"
            } else {
                formatRatio(guest.disk, guest.maxdisk)
            },
        )
        val canMigrate = state.writable.canMigrateGuest(guest.remoteId, guest.vmid) &&
            (
                state.nodes.any { it.remoteId == guest.remoteId && it.name != guest.node && it.status == "online" } ||
                    state.remotes.any { it.kind == "pve" && it.id != guest.remoteId }
                )
        if (state.writable.canManageGuest(guest.remoteId, guest.vmid) || canMigrate) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.writable.canManageGuest(guest.remoteId, guest.vmid)) {
                    GuestPowerButtons(guest, paused = paused, busy = busy, vm = vm)
                }
                if (canMigrate && !busy && live?.lock.isNullOrBlank()) {
                    TechActionPlate(label = "Migrate", onClick = { vm.openMigrate(guest) })
                }
            }
        }
    }
}

@Composable
private fun SnapshotPlate(guest: Guest, view: GuestView?, canManage: Boolean, vm: FleetViewModel) {
    val snapshots = view?.snapshots
    TechPlate(railColor = TechColors.Edge) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SNAPSHOTS" + (snapshots?.let { " ${it.size}" } ?: ""),
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
            )
            if (canManage && view?.busy == null && snapshots != null) {
                TechActionPlate(label = "New snapshot", onClick = { vm.openSnapshotForm(guest) })
            }
        }
        view?.busy?.let {
            Spacer(Modifier.height(6.dp))
            Text("$it…", fontFamily = FontFamily.Monospace, color = TechColors.Amber, style = MaterialTheme.typography.labelSmall)
        }
        view?.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(6.dp))
        when {
            snapshots == null && view?.loading != false -> MutedLine("loading…")
            snapshots == null -> MutedLine("snapshots did not load")
            snapshots.isEmpty() -> MutedLine("no snapshots")
            else -> snapshots.forEach { snap ->
                SnapshotRow(
                    snap,
                    canManage = canManage && view?.busy == null,
                    onRollback = { vm.requestSnapshotAction(guest, snap.name, rollback = true) },
                    onDelete = { vm.requestSnapshotAction(guest, snap.name, rollback = false) },
                )
            }
        }
    }
}

@Composable
internal fun MutedLine(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun SnapshotRow(snap: GuestSnapshot, canManage: Boolean, onRollback: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                snap.name + if (snap.isCurrentBase) "  ◂ now" else "",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (snap.isCurrentBase) TechColors.Amber else MaterialTheme.colorScheme.onSurface,
            )
            MutedLine(
                listOfNotNull(
                    snap.time?.let { formatTimestamp(it) },
                    "with RAM".takeIf { snap.withRam },
                    snap.description.takeIf { it.isNotBlank() }?.lineSequence()?.first(),
                ).joinToString(" · "),
            )
        }
        if (canManage) {
            TechActionPlate(label = "Rollback", onClick = onRollback)
            Spacer(Modifier.width(8.dp))
            TechActionPlate(label = "Delete", onClick = onDelete)
        }
    }
}

/** Name + note, plus RAM for running VMs. Name follows PVE rules so the server never rejects it. */
@Composable
internal fun SnapshotFormDialog(guest: Guest, onCreate: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var withRam by remember { mutableStateOf(false) }
    val valid = isValidSnapshotName(name)
    val ramPossible = guest.type == "qemu" && guest.status == "running"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TechColors.Hull,
        title = { Text("SNAPSHOT ${guest.name}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("NAME") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = name.isNotEmpty() && !valid,
                    supportingText = { Text("letter first, then letters, digits, - or _", fontFamily = FontFamily.Monospace) },
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("NOTE (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ramPossible) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Include RAM", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            MutedLine("Slower and larger; a rollback comes back running.")
                        }
                        TechSwitch(checked = withRam, onCheckedChange = { withRam = it })
                    }
                }
            }
        },
        confirmButton = {
            TechActionPlate(
                label = "Create",
                onClick = { if (valid) onCreate(name, note, withRam && ramPossible) },
                emphasized = valid,
            )
        },
        dismissButton = { TechActionPlate(label = "Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun NodeSpecCard(node: FleetNode) {
    val usedCores = if (node.cpu != null && node.maxcpu != null) node.cpu * node.maxcpu else null
    TechPlate(railColor = techRailColor(node.status)) {
        Text(node.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SpecLine("Status", node.status)
        SpecLine("Remote", node.remoteId)
        SpecLine("Uptime", formatUptime(node.uptime))
        SpecLine(
            "CPU",
            listOfNotNull(formatPercent(node.cpu), node.maxcpu?.let { formatCores(usedCores, it) })
                .joinToString(" · ")
                .ifBlank { "—" },
        )
        SpecLine("Memory", formatRatio(node.mem, node.maxmem))
        if (node.haState != null) {
            val haColor = when {
                node.isHaMaintenance -> TechColors.Amber
                node.isHaTransitionalOrFault -> MaterialTheme.colorScheme.error
                else -> TechColors.Mute
            }
            SpecLine("HA State", node.haState.uppercase(), valueColor = haColor)
        }
    }
}

@Composable
private fun StorageSpecCard(storage: FleetStorage) {
    TechPlate(railColor = techRailColor(storage.status)) {
        Text(storage.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SpecLine("Remote", storage.remoteId)
        SpecLine("Node", storage.node?.takeIf { it.isNotBlank() } ?: "—")
        SpecLine("Shared", if (storage.shared) "yes" else "no")
        SpecLine("Status", storage.status)
        SpecLine("Used", formatRatio(storage.disk, storage.maxdisk))
    }
}

@Composable
private fun DatastoreSpecCard(ds: FleetDatastore) {
    TechPlate(railColor = if ((ds.usage ?: 0.0) >= 0.80) MaterialTheme.colorScheme.error else TechColors.Amber) {
        Text(ds.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SpecLine("Remote", ds.remoteId)
        SpecLine("Maintenance", ds.maintenance?.takeIf { it.isNotBlank() } ?: "none")
        SpecLine("Usage", formatPercent(ds.usage))
        SpecLine("Used", formatRatio(ds.disk, ds.maxdisk))
    }
}

@Composable
private fun NetworkSpecCard(net: FleetNetwork) {
    TechPlate(railColor = techRailColor(net.status)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(net.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Protocols.zone(net.zoneType)?.let {
                Spacer(Modifier.width(10.dp))
                ProtocolTag(it)
            }
        }
        Spacer(Modifier.height(10.dp))
        SpecLine("Remote", net.remoteId)
        SpecLine("Node", net.node?.takeIf { it.isNotBlank() } ?: "—")
        SpecLine("Type", net.zoneType?.takeIf { it.isNotBlank() } ?: "—")
        SpecLine("Status", net.status)
    }
}

@Composable
internal fun SpecLine(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
) {
    val rowMod = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowMod,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.2.sp,
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Operations sent from this app: how many may still be running, and how many need a look. */
@Composable
private fun OperationsEntry(receipts: List<OperationReceipt>, onClick: () -> Unit) {
    val running = receipts.count { it.mayBeActive && it.state != OperationSubmissionState.UNKNOWN }
    val attention = receipts.count {
        it.state == OperationSubmissionState.UNKNOWN || it.state == OperationSubmissionState.OBSERVATION_PAUSED
    }
    TechPlate(railColor = if (attention > 0) TechColors.Amber else TechColors.Edge, modifier = Modifier.clickable(onClick = onClick)) {
        Text("OPERATIONS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        MutedLine(
            listOfNotNull(
                "$running in progress".takeIf { running > 0 },
                "$attention to check".takeIf { attention > 0 },
                "${receipts.size} sent from this app".takeIf { running == 0 && attention == 0 },
            ).joinToString(" · "),
        )
    }
}

/** One operation, in words: what it was, where it stands, when it was sent. */
internal fun receiptStateLabel(r: OperationReceipt): String = when (r.state) {
    OperationSubmissionState.SUBMITTING -> "Sending"
    OperationSubmissionState.ACCEPTED -> "Accepted"
    OperationSubmissionState.RUNNING -> "Running"
    OperationSubmissionState.UNKNOWN -> "Outcome unknown"
    OperationSubmissionState.OBSERVATION_PAUSED -> "Still running? Not watched"
    OperationSubmissionState.SUCCEEDED -> "Done"
    OperationSubmissionState.FAILED -> "Failed"
    OperationSubmissionState.REJECTED -> "Refused"
}

@Composable
private fun ReceiptRow(receipt: OperationReceipt, onOpen: (() -> Unit)?, onLookAgain: (() -> Unit)?) {
    val tone = when (receipt.state) {
        OperationSubmissionState.SUCCEEDED -> TechColors.LinkGreen
        OperationSubmissionState.FAILED, OperationSubmissionState.REJECTED -> MaterialTheme.colorScheme.error
        OperationSubmissionState.UNKNOWN, OperationSubmissionState.OBSERVATION_PAUSED -> TechColors.Amber
        else -> TechColors.Cyan
    }
    TechPlate(railColor = tone, modifier = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                receipt.description.ifBlank { receipt.kind },
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                receiptStateLabel(receipt).uppercase(),
                fontFamily = FontFamily.Monospace,
                color = tone,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        MutedLine("sent ${formatTaskAge(receipt.submittedAt / 1000)}")
        receipt.error?.let { err ->
            Text(err, fontFamily = FontFamily.Monospace, color = tone, style = MaterialTheme.typography.labelSmall)
        }
        if (onLookAgain != null) {
            Spacer(Modifier.height(6.dp))
            TechActionPlate(label = "Look again", onClick = onLookAgain)
        }
    }
}
