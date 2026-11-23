package com.pdm.app.ui.home

import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import com.pdm.app.ui.adaptive.topChromePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pdm.app.data.model.DashboardMetrics
import com.pdm.app.data.model.FleetDatastore
import com.pdm.app.data.model.FleetNetwork
import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.FleetStorage
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.GuestAction
import com.pdm.app.data.model.LeaderEntry
import com.pdm.app.data.model.Leaderboards
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.model.RemoteTask
import com.pdm.app.data.model.taskPlateSubtitle
import com.pdm.app.data.model.taskPlateValue
import com.pdm.app.data.model.canManageGuest
import com.pdm.app.data.model.NodeUpdates
import com.pdm.app.data.model.PdmHost
import com.pdm.app.data.model.rollup
import com.pdm.app.data.model.taskRollup
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.PendingPower
import com.pdm.app.ui.RefreshReason
import com.pdm.app.ui.adaptive.DeskCommand
import com.pdm.app.ui.adaptive.LocalUiPosture
import com.pdm.app.ui.adaptive.WindowTier
import com.pdm.app.ui.adaptive.deskShortcuts
import com.pdm.app.ui.adaptive.handOnHover
import com.pdm.app.ui.adaptive.secondaryClick
import com.pdm.app.ui.adaptive.isOperatorTwoPane
import com.pdm.app.ui.components.CollapsibleHeader
import com.pdm.app.ui.components.LeaderPlate
import com.pdm.app.ui.components.ProtocolTag
import com.pdm.app.ui.components.Protocols
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.components.TechConfirmDialog
import com.pdm.app.ui.components.TechSectionLabel
import com.pdm.app.ui.components.TechUsageBar
import com.pdm.app.ui.components.techRailColor
import com.pdm.app.ui.settings.SettingsScreen
import com.pdm.app.ui.util.AllocationScale
import com.pdm.app.ui.util.formatCores
import com.pdm.app.ui.util.formatPercent
import com.pdm.app.ui.util.formatRatio
import com.pdm.app.ui.util.formatUptime
import com.pdm.app.ui.util.fractionOf

/** The filter field's requester, so a desk shortcut can focus it from outside the list. */
internal val LocalFilterFocus = staticCompositionLocalOf<FocusRequester?> { null }

@Composable
fun HomeScreen(vm: FleetViewModel) {
    val state by vm.fleet.collectAsStateWithLifecycle()
    val twoPane = isOperatorTwoPane(LocalConfiguration.current.screenWidthDp)
    val posture = LocalUiPosture.current
    // The third column is a desk layout: only in a desktop window (DeX), never on the tablet held in the hand.
    val threePane = twoPane && posture.desktopWindow && posture.tier >= WindowTier.THREE_PANE
    val remoteId = state.selectedRemoteId
    val guests = state.guests.filter { guest ->
        (remoteId == null || guest.remoteId == remoteId) && guest.matchesQuery(state.query)
    }
    val nodes = state.nodes.filter { remoteId == null || it.remoteId == remoteId }
    val storages = state.storages.filter { remoteId == null || it.remoteId == remoteId }
    val datastores = state.datastores.filter { remoteId == null || it.remoteId == remoteId }
    val networks = state.networks.filter { remoteId == null || it.remoteId == remoteId }
    val tasks = state.tasks?.filter { remoteId == null || it.remoteId == remoteId }
    var cpuGraphsExpanded by rememberSaveable { mutableStateOf(true) }
    var memoryGraphsExpanded by rememberSaveable { mutableStateOf(true) }
    var nodesExpanded by rememberSaveable { mutableStateOf(false) }
    var storageExpanded by rememberSaveable { mutableStateOf(false) }
    var datastoresExpanded by rememberSaveable { mutableStateOf(false) }
    var sdnExpanded by rememberSaveable { mutableStateOf(false) }
    val compactListState = rememberLazyListState()
    val detailListState = rememberLazyListState()
    val specListState = rememberLazyListState()
    val notableSubCount = state.subscriptions.count { sub ->
        sub.isNotable() && (remoteId == null || sub.remote == remoteId)
    }
    val mapCount = mapPaneItemCount(
        notableSubscriptionCount = notableSubCount,
        hasLeaders = !state.leaders.isEmpty,
        cpuGraphsExpanded = cpuGraphsExpanded,
        memoryGraphsExpanded = memoryGraphsExpanded,
        nodeCount = nodes.size,
        nodesExpanded = nodesExpanded,
        storageCount = storages.size,
        storageExpanded = storageExpanded,
        datastoreCount = datastores.size,
        datastoresExpanded = datastoresExpanded,
        networkCount = networks.size,
        sdnExpanded = sdnExpanded,
        hasHost = state.host != null,
    )
    if (!state.showSettings && state.nav.canPop) {
        BackHandler { vm.popFocus() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.keepFresh() }
    }
    // Settings > Extras can require the screen lock on top of each destructive confirm.
    val lockGate = com.pdm.app.ui.util.rememberScreenLockGate(state.screenLockConfirm, vm::notify)
    state.pendingPower?.let { pending ->
        PowerTechConfirmDialog(
            pending,
            onConfirm = { lockGate("${pending.action.label} ${pending.guest.name}", vm::confirmPower) },
            onDismiss = vm::cancelPower,
        )
    }
    state.pendingSnapshot?.let { pending ->
        TechConfirmDialog(
            title = if (pending.rollback) "ROLL BACK ${pending.guest.name}?" else "DELETE SNAPSHOT ${pending.name}?",
            body = if (pending.rollback) {
                "Returns ${pending.guest.name} to snapshot \"${pending.name}\". Everything written since then " +
                    "is lost. A running guest is stopped for the rollback."
            } else {
                "Removes snapshot \"${pending.name}\" from ${pending.guest.name}. The guest keeps running as it is; " +
                    "you just can no longer return to that point."
            },
            confirmLabel = if (pending.rollback) "Roll back" else "Delete",
            onConfirm = {
                lockGate(if (pending.rollback) "Roll back ${pending.guest.name}" else "Delete snapshot ${pending.name}", vm::confirmSnapshotAction)
            },
            onDismiss = vm::cancelSnapshotAction,
        )
    }
    state.snapshotFormFor?.let { id ->
        state.guests.firstOrNull { it.id == id }?.let { guest ->
            SnapshotFormDialog(
                guest = guest,
                onCreate = { name, note, ram -> vm.createSnapshot(guest, name, note, ram) },
                onDismiss = vm::closeSnapshotForm,
            )
        }
    }
    state.migrateView?.let { view ->
        state.guests.firstOrNull { it.id == view.guestId }?.let { guest ->
            MigrateDialog(guest, view, vm)
        }
    }
    if (state.pendingNotesLeave) {
        TechConfirmDialog(
            title = "DISCARD YOUR NOTES CHANGES?",
            body = "Your changes to the shared notes haven't been saved. Leaving throws them away.",
            confirmLabel = "Discard",
            onConfirm = vm::confirmNotesLeave,
            onDismiss = vm::cancelNotesLeave,
        )
    }
    if (state.pendingHostRestart) {
        val node = state.host?.node ?: "PDM"
        TechConfirmDialog(
            title = "RESTART $node?",
            body = "$node goes offline for a minute or two while it restarts. Your remotes and their guests keep running.\n\n" +
                "You can keep looking around meanwhile; the app reconnects by itself when $node is back.",
            confirmLabel = "Restart",
            onConfirm = { lockGate("Restart $node", vm::confirmHostRestart) },
            onDismiss = vm::cancelHostRestart,
        )
    }
    state.pendingShell?.let { pending ->
        com.pdm.app.ui.shell.UpgradeShellDialog(
            pending = pending,
            onConfirm = vm::confirmShell,
            onForgetSaved = vm::forgetPendingNodePassword,
            onDismiss = vm::cancelShell,
        )
    }
    state.pendingTaskStop?.let { task ->
        TechConfirmDialog(
            title = "STOP ${task.headline}?",
            body = buildString {
                append(task.remoteId)
                if (task.node.isNotBlank()) append(" / ${task.node}")
                append(".\n\nThe worker is aborted where it is. Whatever it was doing is left half done.")
            },
            confirmLabel = "Stop task",
            onConfirm = { lockGate("Stop ${task.headline}", vm::confirmStopTask) },
            onDismiss = vm::cancelStopTask,
        )
    }
    LaunchedEffect(if (threePane) state.nav.listFocus else state.nav.focus, twoPane) {
        if (twoPane) {
            detailListState.scrollToItem(0)
            return@LaunchedEffect
        }
        val target = if (state.nav.focus.isRoot) 0 else mapCount
        compactListState.animateScrollToItem(target.coerceAtLeast(0))
    }
    LaunchedEffect(state.nav.focus, threePane) {
        if (threePane) specListState.scrollToItem(0)
    }
    val rootFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    var filterTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(posture.desktopWindow) {
        if (posture.desktopWindow) runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(filterTick) {
        if (filterTick == 0) return@LaunchedEffect
        if (state.showSettings) vm.closeSettings()
        if (!state.nav.focus.isRoot) vm.openFocus(DashFocus.Guests)
        if (twoPane) detailListState.scrollToItem(0) else compactListState.scrollToItem(mapCount)
        withFrameNanos { }
        runCatching { filterFocus.requestFocus() }
    }
    val onDeskCommand: (DeskCommand) -> Unit = { command ->
        when (command) {
            DeskCommand.REFRESH -> vm.refresh(RefreshReason.PULL)
            DeskCommand.FOCUS_FILTER -> filterTick++
            DeskCommand.BACK -> when {
                state.livePanel -> vm.setLivePanel(false)
                state.showSettings -> vm.closeSettings()
                state.nav.canPop -> vm.popFocus()
                else -> runCatching { rootFocus.requestFocus() }
            }
            DeskCommand.TIMEFRAME_HOUR -> vm.setTimeframe("hour")
            DeskCommand.TIMEFRAME_DAY -> vm.setTimeframe("day")
            DeskCommand.TIMEFRAME_WEEK -> vm.setTimeframe("week")
        }
    }
    val dashContent: LazyListScope.() -> Unit = {
        dashboardItems(
            metrics = state.metrics,
            remotes = state.remotes,
            selectedRemoteId = remoteId,
            onSelectRemote = { id ->
                cpuGraphsExpanded = true
                memoryGraphsExpanded = true
                vm.selectRemote(id)
            },
            leaders = state.leaders,
            timeframe = state.timeframe,
            onTimeframe = vm::setTimeframe,
            subscriptions = state.subscriptions,
            tasks = tasks,
            cpuGraphsExpanded = cpuGraphsExpanded,
            memoryGraphsExpanded = memoryGraphsExpanded,
            onToggleCpuGraphs = { cpuGraphsExpanded = !cpuGraphsExpanded },
            onToggleMemoryGraphs = { memoryGraphsExpanded = !memoryGraphsExpanded },
            onOpenCpuGraphs = {
                cpuGraphsExpanded = true
                vm.openFocus(DashFocus.Cpu)
            },
            onOpenMemoryGraphs = {
                memoryGraphsExpanded = true
                vm.openFocus(DashFocus.Memory)
            },
            onRemotesStat = {
                cpuGraphsExpanded = true
                memoryGraphsExpanded = true
                if (remoteId != null) vm.selectRemote(remoteId) else vm.openFocus(DashFocus.Guests)
            },
            onGuestsStat = { vm.openFocus(DashFocus.Guests) },
            onNodesStat = { vm.openFocus(DashFocus.Nodes) },
            onFaultsStat = { vm.openFocus(DashFocus.Faults) },
            onTasksStat = { vm.openFocus(DashFocus.Tasks) },
            updates = state.updates?.filter { remoteId == null || it.remoteId == remoteId },
            hostPending = state.host?.updates?.size?.takeIf { remoteId == null } ?: 0,
            onUpdatesStat = { vm.openFocus(DashFocus.Updates) },
            onCpuStat = {
                cpuGraphsExpanded = true
                vm.openFocus(DashFocus.Cpu)
            },
            onMemoryStat = {
                memoryGraphsExpanded = true
                vm.openFocus(DashFocus.Memory)
            },
            onStorageStat = { vm.openFocus(DashFocus.Storage) },
            onSdnStat = { vm.openFocus(DashFocus.Sdn) },
            onLeader = vm::openLeader,
            host = state.host.takeIf { remoteId == null },
            onHost = { vm.openFocus(DashFocus.Host) },
            state = state,
            onToggleExpanded = { vm.setDashboardExpanded(!state.dashboardExpanded) },
        )
        inventoryItems(
            nodes = nodes,
            storages = storages,
            datastores = datastores,
            networks = networks,
            nodesExpanded = nodesExpanded,
            storageExpanded = storageExpanded,
            datastoresExpanded = datastoresExpanded,
            sdnExpanded = sdnExpanded,
            onToggleNodes = { nodesExpanded = !nodesExpanded },
            onToggleStorage = { storageExpanded = !storageExpanded },
            onToggleDatastores = { datastoresExpanded = !datastoresExpanded },
            onToggleSdn = { sdnExpanded = !sdnExpanded },
            onOpenNodes = { vm.openFocus(DashFocus.Nodes) },
            onOpenStorage = { vm.openFocus(DashFocus.Storage) },
            onOpenDatastores = { vm.openFocus(DashFocus.Datastores) },
            onOpenSdn = { vm.openFocus(DashFocus.Sdn) },
            onNode = {
                cpuGraphsExpanded = true
                memoryGraphsExpanded = true
                vm.openFocus(DashFocus.NodeDetail(it.id))
            },
            onStorage = { vm.openFocus(DashFocus.StorageDetail(it.id)) },
            onDatastore = { vm.openFocus(DashFocus.DatastoreDetail(it.id)) },
            onNetwork = { vm.openFocus(DashFocus.NetworkDetail(it.id)) },
        )
    }

    // One scrolling column of the detail surface: the single right pane, or the list / spec pair on a desk.
    val detailColumn: @Composable (Modifier, DashFocus?, LazyListState) -> Unit = { modifier, shown, listState ->
        FollowLogScroll(state, shown, listState)
        RefreshReveal(
            refreshing = state.sync.running,
            pullBusy = state.sync.manual,
            look = pullLook(state),
            onRefresh = { vm.refresh(RefreshReason.PULL) },
            modifier = modifier.tastefulMotion(shown, state.motionStyle).fillMaxHeight(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shown == null) {
                    item { EmptyHint("Select a guest, node or task to inspect it here.") }
                } else {
                    detailPane(
                        state = state,
                        guests = guests,
                        nodes = nodes,
                        storages = storages,
                        datastores = datastores,
                        networks = networks,
                        vm = vm,
                        focus = shown,
                    )
                }
            }
        }
    }

    CompositionLocalProvider(LocalFilterFocus provides filterFocus) {
    Box(Modifier.fillMaxSize()) {
    // Only the background reads the activity stream, so a request does not redraw the dashboard.
    AtmosphereBackground(state, vm.activity)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .deskShortcuts(posture.desktopWindow, rootFocus, onDeskCommand)
            .topChromePadding()
            .navigationBarsPadding(),
    ) {
        HomeHeader(state, vm)
        state.hostRestart?.takeIf { !it.settled }?.let { RestartLine(it, onRetry = vm::retryRestartWatch) }
            ?: state.sync.cachedAt?.let { at -> CachedDataLine(at, state) }
            ?: SectionsLine(state, vm)
        if (!state.error.isNullOrBlank()) {
            Text(
                state.error.orEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (twoPane) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(if (threePane) 0.28f else 0.42f)
                        .fillMaxHeight()
                        .padding(end = 12.dp),
                ) {
                    if (state.showSettings) {
                        HomeSettings(state, vm, Modifier.tastefulMotion(state.showSettings, state.motionStyle).weight(1f))
                    } else {
                        RefreshReveal(
                            refreshing = state.sync.running,
                            pullBusy = state.sync.manual,
                            look = pullLook(state),
                            onRefresh = { vm.refresh(RefreshReason.PULL) },
                            modifier = Modifier.weight(1f),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                content = dashContent,
                            )
                        }
                        SettingsFooter(onClick = vm::openSettings)
                    }
                }
                PaneDivider()
                if (threePane) {
                    detailColumn(Modifier.weight(0.34f).padding(horizontal = 12.dp), state.nav.listFocus, detailListState)
                    PaneDivider()
                    detailColumn(
                        Modifier.weight(0.38f).padding(start = 12.dp),
                        state.nav.focus.takeIf { it.isDetail },
                        specListState,
                    )
                } else {
                    detailColumn(Modifier.weight(0.58f).padding(start = 12.dp), state.nav.focus, detailListState)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                if (state.showSettings) {
                    HomeSettings(state, vm, Modifier.weight(1f))
                } else {
                    RefreshReveal(
                        refreshing = state.sync.running,
                        pullBusy = state.sync.manual,
                        look = pullLook(state),
                        onRefresh = { vm.refresh(RefreshReason.PULL) },
                        modifier = Modifier.tastefulMotion(state.nav.focus, state.motionStyle).weight(1f),
                    ) {
                        FollowLogScroll(state, state.nav.focus, compactListState)
                        LazyColumn(
                            state = compactListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            dashContent()
                            detailPane(
                                state = state,
                                guests = guests,
                                nodes = nodes,
                                storages = storages,
                                datastores = datastores,
                                networks = networks,
                                vm = vm,
                            )
                        }
                    }
                    SettingsFooter(onClick = vm::openSettings)
                }
            }
        }
    }
    }
    }
}

@Composable
private fun PaneDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(TechColors.Edge),
    )
}

private fun LazyListScope.dashboardItems(
    metrics: DashboardMetrics,
    remotes: List<RemoteNode>,
    selectedRemoteId: String?,
    onSelectRemote: (String?) -> Unit,
    leaders: Leaderboards,
    timeframe: String,
    onTimeframe: (String) -> Unit,
    subscriptions: List<RemoteSubscription>,
    tasks: List<RemoteTask>?,
    cpuGraphsExpanded: Boolean,
    memoryGraphsExpanded: Boolean,
    onToggleCpuGraphs: () -> Unit,
    onToggleMemoryGraphs: () -> Unit,
    onOpenCpuGraphs: () -> Unit,
    onOpenMemoryGraphs: () -> Unit,
    onRemotesStat: () -> Unit,
    onGuestsStat: () -> Unit,
    onNodesStat: () -> Unit,
    onFaultsStat: () -> Unit,
    onTasksStat: () -> Unit,
    updates: List<NodeUpdates>?,
    hostPending: Int,
    onUpdatesStat: () -> Unit,
    onCpuStat: () -> Unit,
    onMemoryStat: () -> Unit,
    onStorageStat: () -> Unit,
    onSdnStat: () -> Unit,
    onLeader: (LeaderEntry) -> Unit,
    host: PdmHost?,
    onHost: () -> Unit,
    state: FleetUiState,
    onToggleExpanded: () -> Unit,
) {
    val expanded = state.dashboardExpanded
    // Same three items either way, so the compact layout's scroll targets stay right.
    item { DashboardHeader(expanded, timeframe, onTimeframe, onToggleExpanded) }
    item {
        if (expanded) {
            ExpandedCounts(
                state = state,
                onRemotes = onRemotesStat,
                onGuests = onGuestsStat,
                onNodes = onNodesStat,
                onFaults = onFaultsStat,
                onTasks = onTasksStat,
                onUpdates = onUpdatesStat,
            )
        } else {
            DashboardStats(
                metrics = metrics,
                tasks = tasks,
                onRemotes = onRemotesStat,
                onGuests = onGuestsStat,
                onNodes = onNodesStat,
                onFaults = onFaultsStat,
                onTasks = onTasksStat,
                updates = updates,
                hostPending = hostPending,
                onUpdates = onUpdatesStat,
            )
        }
    }
    item {
        if (expanded) {
            ExpandedLoad(
                state = state,
                onCpu = onCpuStat,
                onMemory = onMemoryStat,
                onStorage = onStorageStat,
                onSdn = onSdnStat,
                onNetwork = onNodesStat,
            )
        } else {
            LoadPane(
                metrics = metrics,
                onCpu = onCpuStat,
                onMemory = onMemoryStat,
                onStorage = onStorageStat,
                onSdn = onSdnStat,
            )
        }
    }
    if (!leaders.isEmpty) {
        // Expanded, the timeframe lives in the dashboard header; the item stays (empty) to keep counts.
        item { if (!expanded) TimeframeRow(timeframe, onTimeframe) }
        item {
            CollapsibleHeader(
                title = "CPU leaders",
                expanded = cpuGraphsExpanded,
                onToggle = onToggleCpuGraphs,
                onOpen = onOpenCpuGraphs,
            )
        }
        if (cpuGraphsExpanded) {
            item { LeaderPlate("Guests with highest CPU", sparkWindow(leaders.guestCpu, selectedRemoteId), null, onLeader) }
            item { LeaderPlate("Nodes with highest CPU", sparkWindow(leaders.nodeCpu, selectedRemoteId), null, onLeader) }
        }
        item {
            CollapsibleHeader(
                title = "Memory leaders",
                expanded = memoryGraphsExpanded,
                onToggle = onToggleMemoryGraphs,
                onOpen = onOpenMemoryGraphs,
            )
        }
        if (memoryGraphsExpanded) {
            item { LeaderPlate("Nodes with highest memory", sparkWindow(leaders.nodeMemory, selectedRemoteId), null, onLeader) }
        }
    }
    item { TechSectionLabel("Remotes", remotes.size) }
    host?.let { h -> item { HostPlate(h, onClick = onHost) } }
    item { RemoteList(remotes, selectedRemoteId, onSelectRemote) }
    val notableSubs = subscriptions.filter { sub ->
        sub.isNotable() && (selectedRemoteId == null || sub.remote == selectedRemoteId)
    }
    if (notableSubs.isNotEmpty()) {
        item { TechSectionLabel("Subscription", notableSubs.size) }
        item { SubscriptionList(notableSubs) }
    }
}

private fun LazyListScope.inventoryItems(
    nodes: List<FleetNode>,
    storages: List<FleetStorage>,
    datastores: List<FleetDatastore>,
    networks: List<FleetNetwork>,
    nodesExpanded: Boolean,
    storageExpanded: Boolean,
    datastoresExpanded: Boolean,
    sdnExpanded: Boolean,
    onToggleNodes: () -> Unit,
    onToggleStorage: () -> Unit,
    onToggleDatastores: () -> Unit,
    onToggleSdn: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenDatastores: () -> Unit,
    onOpenSdn: () -> Unit,
    onNode: (FleetNode) -> Unit,
    onStorage: (FleetStorage) -> Unit,
    onDatastore: (FleetDatastore) -> Unit,
    onNetwork: (FleetNetwork) -> Unit,
) {
    if (nodes.isNotEmpty()) {
        item {
            CollapsibleHeader(
                title = "Nodes",
                expanded = nodesExpanded,
                onToggle = onToggleNodes,
                count = nodes.size,
                onOpen = onOpenNodes,
            )
        }
        if (nodesExpanded) {
            items(nodes, key = { mapItemKey(it.id) }) {
                NodeRow(
                    it,
                    onClick = { onNode(it) },
                    largestMem = nodes.maxOfOrNull { n -> n.maxmem ?: 0L },
                    largestCores = nodes.maxOfOrNull { n -> n.maxcpu ?: 0.0 },
                )
            }
        }
    }
    if (storages.isNotEmpty()) {
        item {
            CollapsibleHeader(
                title = "Storage",
                expanded = storageExpanded,
                onToggle = onToggleStorage,
                count = storages.size,
                onOpen = onOpenStorage,
            )
        }
        if (storageExpanded) {
            items(storages, key = { mapItemKey(it.id) }) {
                StorageRow(it, onClick = { onStorage(it) }, largestDisk = storages.maxOfOrNull { s -> s.maxdisk ?: 0L })
            }
        }
    }
    if (datastores.isNotEmpty()) {
        item {
            CollapsibleHeader(
                title = "Datastores",
                expanded = datastoresExpanded,
                onToggle = onToggleDatastores,
                count = datastores.size,
                onOpen = onOpenDatastores,
            )
        }
        if (datastoresExpanded) {
            items(datastores, key = { mapItemKey(it.id) }) { DatastoreRow(it, onClick = { onDatastore(it) }) }
        }
    }
    if (networks.isNotEmpty()) {
        item {
            CollapsibleHeader(
                title = "SDN",
                expanded = sdnExpanded,
                onToggle = onToggleSdn,
                count = networks.size,
                onOpen = onOpenSdn,
            )
        }
        if (sdnExpanded) {
            items(networks, key = { mapItemKey(it.id) }) { NetworkRow(it, onClick = { onNetwork(it) }) }
        }
    }
}

/** Dashboard polls while the screen is resumed. PDM itself caches remote reads (`max-age`). */

@Composable
private fun PowerTechConfirmDialog(
    pending: PendingPower,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val guest = pending.guest
    TechConfirmDialog(
        title = "${pending.action.label.uppercase()} ${guest.name}?",
        body = buildString {
            append("${guest.type} ${guest.vmid} on ${guest.remoteId}")
            guest.node?.takeIf { it.isNotBlank() }?.let { append(" / $it") }
            append(".\n\n")
            append(
                if (pending.action == GuestAction.STOP) {
                    "Stop cuts power immediately. Unsaved data in the guest is lost."
                } else {
                    "Shutdown asks the guest OS to power off. It can take a few minutes."
                },
            )
        },
        confirmLabel = pending.action.label,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SettingsFooter(onClick: () -> Unit) {
    TechPlate(
        railColor = TechColors.Edge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            "SETTINGS",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Session, trust, and clean slate",
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HomeHeader(state: FleetUiState, vm: FleetViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "PDM",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!state.demo) {
                    // PDM only speaks HTTPS; the TLS version is what the last live exchange used.
                    val tls by vm.tls.collectAsStateWithLifecycle()
                    ProtocolTag(Protocols.HTTPS)
                    tls?.takeIf { !state.readOnly }?.let {
                        Spacer(Modifier.width(6.dp))
                        ProtocolTag(Protocols.tls(it))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    state.hostLabel + (state.version?.let { " · $it" } ?: ""),
                    fontFamily = FontFamily.Monospace,
                    color = if (state.demo) TechColors.LinkGreen else TechColors.Amber,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        state.selectedRemoteId?.let { remote ->
            // The whole screen is scoped to one remote; say so where it is always visible.
            TechActionPlate(label = "$remote ✕", onClick = { vm.selectRemote(remote) }, emphasized = true)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.secondaryClick(LocalUiPosture.current.externalPointer) { vm.setLivePanel(true) }) {
            val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
            SyncChip(
                state.sync,
                hostRestart = state.hostRestart,
                onRefresh = { vm.refresh(RefreshReason.PULL) },
                onLongPress = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    vm.setLivePanel(true)
                },
            )
            if (state.livePanel) {
                // Collected only while open: requests do not redraw the header otherwise.
                val requests by vm.activity.collectAsStateWithLifecycle()
                val journal by vm.journal.collectAsStateWithLifecycle()
                LivePanel(
                    sync = state.sync,
                    offline = state.offline,
                    cacheStats = state.cacheStats,
                    cacheWriting = vm.cacheWriting(),
                    requests = requests,
                    journal = journal,
                    hostRestart = state.hostRestart,
                    onClose = { vm.setLivePanel(false) },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TechActionPlate(label = "Exit", onClick = vm::exit)
    }
}

/** Settings, wired to the view model. One place, so the two layouts cannot drift apart. */
@Composable
private fun HomeSettings(state: FleetUiState, vm: FleetViewModel, modifier: Modifier) {
    SettingsScreen(
        hostLabel = state.hostLabel,
        version = state.version,
        demo = state.demo,
        secretSaved = state.secretSaved,
        certPins = state.certPins,
        subscriptions = state.subscriptions,
        onBack = vm::closeSettings,
        onSwitchAccount = vm::exit,
        onForgetSecret = vm::forgetSavedSecret,
        onForgetPin = vm::forgetCertPin,
        onCleanSlate = vm::cleanSlate,
        sessionInfo = state.sessionInfo,
        motionStyle = state.motionStyle,
        watermarkStrength = state.watermarkStrength,
        onMotionStyle = vm::setMotionStyle,
        onWatermarkStrength = vm::setWatermarkStrength,
        watermarkMode = state.watermarkMode,
        watermarkTone = state.watermarkTone,
        themeMode = state.themeMode,
        onThemeMode = vm::setThemeMode,
        onWatermarkMode = vm::setWatermarkMode,
        onWatermarkTone = vm::setWatermarkTone,
        onBackgroundEnabled = vm::setBackgroundEnabled,
        onStrengthEnabled = vm::setStrengthEnabled,
        offline = state.offline,
        offlineCache = state.offlineCache,
        cacheStats = state.cacheStats,
        onOfflineCache = vm::setOfflineCache,
        onClearCache = vm::clearOfflineCache,
        screenLockConfirm = state.screenLockConfirm,
        onScreenLockConfirm = vm::setScreenLockConfirm,
        modifier = modifier,
    )
}

/** While the PDM machine restarts: how long it has been, and what the app is waiting for (Q1). */
@Composable
private fun RestartLine(restart: com.pdm.app.data.ops.RestartProgress, onRetry: () -> Unit) {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), restart.submittedAt) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val words = restartWords(restart, ((now - restart.submittedAt) / 1000).coerceAtLeast(0))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            words.banner,
            modifier = Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            fontFamily = FontFamily.Monospace,
            color = if (words.problem) TechColors.Danger else TechColors.Amber,
            style = MaterialTheme.typography.bodySmall,
        )
        if (words.retry) {
            Spacer(Modifier.width(8.dp))
            TechActionPlate(label = "Retry", onClick = onRetry)
        }
    }
}

/**
 * Says plainly that the screen shows saved data, from when, and why. Red when the server cannot be
 * reached, gray when this is just the cached copy shown while going live.
 */
@Composable
private fun CachedDataLine(cachedAt: Long, state: FleetUiState) {
    val problem = state.sync.failures > 0 || state.sync.paused
    val age = com.pdm.app.ui.RefreshPolicy.age(System.currentTimeMillis() - cachedAt)
    val stamp = com.pdm.app.ui.util.formatTimestamp(cachedAt / 1000)
    val text = when {
        state.offline && problem -> "OFFLINE · cached $stamp ($age ago). Read-only until the server answers."
        problem -> "SERVER UNREACHABLE · showing data cached from $stamp ($age ago). Read-only until it answers."
        // Cached view at launch, or the moment between signing in and the first live answer.
        else -> "CACHED · $stamp ($age ago). Going live…"
    }
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp),
        fontFamily = FontFamily.Monospace,
        color = if (problem) TechColors.Danger else TechColors.Mute,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun QueryPlate(query: String, onQuery: (String) -> Unit) {
    TechPlate(railColor = TechColors.Mute) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(TechColors.Amber),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .then(LocalFilterFocus.current?.let { Modifier.focusRequester(it) } ?: Modifier),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "FILTER GUESTS · NODES · STORAGE · REMOTES",
                            fontFamily = FontFamily.Monospace,
                            color = TechColors.Mute,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** `1H  [1 DAY]  1W`: the chosen timeframe spells itself out; the plate grows into the words. */
@Composable
internal fun TimeframeRow(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("hour" to "1h", "day" to "1d", "week" to "1w").forEach { (id, short) ->
            TechActionPlate(
                label = if (selected == id) "1 $id" else short,
                onClick = { onSelect(id) },
                emphasized = selected == id,
            )
        }
    }
}

@Composable
private fun DashboardStats(
    metrics: DashboardMetrics,
    tasks: List<RemoteTask>?,
    onRemotes: () -> Unit,
    onGuests: () -> Unit,
    onNodes: () -> Unit,
    onFaults: () -> Unit,
    onTasks: () -> Unit,
    updates: List<NodeUpdates>?,
    hostPending: Int,
    onUpdates: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DashStat(
                title = "Remotes",
                value = (metrics.pveRemotes + metrics.pbsRemotes).toString(),
                subtitle = "${metrics.pveRemotes} PVE · ${metrics.pbsRemotes} PBS",
                modifier = Modifier.weight(1f),
                rail = TechColors.Amber,
                onClick = onRemotes,
            )
            DashStat(
                title = "Guests",
                value = (metrics.guestsRunning + metrics.guestsStopped).toString(),
                subtitle = "${metrics.guestsRunning} up · ${metrics.guestsStopped} down",
                modifier = Modifier.weight(1f),
                rail = TechColors.LinkGreen,
                onClick = onGuests,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val nodeSubtitle = buildString {
                append("${metrics.nodesOnline} up")
                if (metrics.nodesOffline > 0) append(" · ${metrics.nodesOffline} down")
                if (metrics.qemu + metrics.lxc > 0) append(" · ${metrics.qemu} qemu · ${metrics.lxc} lxc")
            }
            DashStat(
                title = "Nodes",
                value = metrics.nodes.toString(),
                subtitle = nodeSubtitle,
                modifier = Modifier.weight(1f),
                rail = if (metrics.nodesOffline > 0) TechColors.Amber else TechColors.LinkGreen,
                onClick = onNodes,
            )
            DashStat(
                title = "Faults",
                value = metrics.remoteErrors.toString(),
                subtitle = if (metrics.remoteErrors == 0) "remotes clean" else "remote errors",
                modifier = Modifier.weight(1f),
                rail = if (metrics.remoteErrors == 0) TechColors.LinkGreen else MaterialTheme.colorScheme.error,
                onClick = onFaults,
            )
        }
        val roll = tasks?.taskRollup()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        DashStat(
            title = "Tasks",
            value = taskPlateValue(tasks),
            subtitle = taskPlateSubtitle(tasks),
            modifier = Modifier.weight(1f),
            rail = when {
                tasks == null -> TechColors.Mute
                (roll?.failed ?: 0) > 0 -> MaterialTheme.colorScheme.error
                (roll?.running ?: 0) > 0 || (roll?.warnings ?: 0) > 0 -> TechColors.Amber
                else -> TechColors.LinkGreen
            },
            onClick = onTasks,
        )
        val upd = updates?.rollup()
        DashStat(
            title = "Updates",
            value = upd?.let { (it.pending + hostPending).toString() } ?: "—",
            subtitle = when {
                upd == null -> "unavailable"
                upd.pending + hostPending == 0 && upd.repoWarnings == 0 -> "all nodes current"
                else -> listOfNotNull(
                    "${upd.nodesWithUpdates} nodes".takeIf { upd.pending > 0 },
                    "PDM $hostPending".takeIf { hostPending > 0 },
                    "${upd.repoWarnings} repo warn".takeIf { upd.repoWarnings > 0 },
                ).joinToString(" · ")
            },
            modifier = Modifier.weight(1f),
            rail = when {
                upd == null -> TechColors.Mute
                upd.errors > 0 -> MaterialTheme.colorScheme.error
                upd.pending + hostPending > 0 || upd.repoWarnings > 0 -> TechColors.Amber
                else -> TechColors.LinkGreen
            },
            onClick = onUpdates,
        )
        }
    }
}

@Composable
internal fun RemoteList(
    remotes: List<RemoteNode>,
    selectedRemoteId: String?,
    onSelectRemote: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        remotes.forEach { remote ->
            val selected = remote.id == selectedRemoteId
            val rail = when {
                !remote.error.isNullOrBlank() || remote.health.equals("Error", true) ->
                    MaterialTheme.colorScheme.error
                remote.health.equals("Warning", true) -> TechColors.Amber
                selected -> TechColors.Amber
                remote.kind == "pbs" -> TechColors.Amber
                else -> TechColors.LinkGreen
            }
            TechPlate(
                railColor = rail,
                modifier = Modifier.clickable { onSelectRemote(remote.id) },
            ) {
                Text(remote.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(remote.kind.uppercase())
                        append(" · ")
                        append(remote.id)
                        if (selected) append(" · FILTER")
                        remote.health?.takeIf { it.isNotBlank() && !it.equals("Good", true) }?.let {
                            append(" · $it")
                        }
                        remote.endpoints.firstOrNull()?.let { append(" · ${it.display}") }
                        if (remote.endpoints.size > 1) append(" +${remote.endpoints.size - 1}")
                        remote.error?.let { append(" · $it") }
                    },
                    fontFamily = FontFamily.Monospace,
                    color = if (remote.error == null) TechColors.Mute else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun SubscriptionList(subscriptions: List<RemoteSubscription>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        subscriptions.forEach { sub ->
            val state = sub.state.orEmpty()
            TechPlate(railColor = subscriptionRail(state)) {
                Text(sub.remote, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    state,
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun subscriptionRail(state: String) = when (state.lowercase()) {
    "active" -> TechColors.LinkGreen
    "expired", "suspended", "invalid", "notfound" -> TechColors.Amber
    else -> TechColors.Mute
}

@Composable
private fun LoadPane(
    metrics: DashboardMetrics,
    onCpu: () -> Unit,
    onMemory: () -> Unit,
    onStorage: () -> Unit,
    onSdn: () -> Unit,
) {
    val cpuFrac = fractionOf(metrics.pveCpuUsed, metrics.pveCpuMax)
    val memFrac = fractionOf(metrics.pveMemUsed, metrics.pveMemTotal)
    val diskFrac = fractionOf(metrics.pveStorageUsed, metrics.pveStorageTotal)
    if (cpuFrac == null && memFrac == null && diskFrac == null && metrics.sdnAvailable == 0 && metrics.storages == 0) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DashStat(
                title = "CPU",
                value = formatCores(metrics.pveCpuUsed, metrics.pveCpuMax),
                subtitle = metrics.pveCpuAllocated?.let { "alloc ${it.toInt()}" } ?: "cluster cores",
                modifier = Modifier.weight(1f),
                rail = usageRail(cpuFrac),
                onClick = onCpu,
            )
            DashStat(
                title = "Memory",
                value = formatRatio(metrics.pveMemUsed, metrics.pveMemTotal),
                subtitle = formatPercent(memFrac?.toDouble()),
                modifier = Modifier.weight(1f),
                rail = usageRail(memFrac),
                onClick = onMemory,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DashStat(
                title = "Storage",
                value = formatRatio(metrics.pveStorageUsed, metrics.pveStorageTotal),
                subtitle = "${metrics.storages} pools",
                modifier = Modifier.weight(1f),
                rail = usageRail(diskFrac),
                onClick = onStorage,
            )
            DashStat(
                title = "SDN",
                value = metrics.sdnAvailable.toString(),
                subtitle = if (metrics.sdnError == 0) "zones ok" else "${metrics.sdnError} error",
                modifier = Modifier.weight(1f),
                rail = if (metrics.sdnError == 0) TechColors.LinkGreen else MaterialTheme.colorScheme.error,
                onClick = onSdn,
            )
        }
    }
}

@Composable
private fun usageRail(fraction: Float?) = when {
    fraction == null -> TechColors.Mute
    fraction >= 0.85f -> MaterialTheme.colorScheme.error
    fraction >= 0.70f -> TechColors.Amber
    else -> TechColors.LinkGreen
}

@Composable
private fun DashStat(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    rail: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null,
) {
    TechPlate(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        railColor = rail,
    ) {
        Text(
            title.uppercase(),
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.2.sp,
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
        Text(
            subtitle,
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
internal fun GuestRow(
    guest: Guest,
    state: FleetUiState,
    vm: FleetViewModel,
    onOpen: (() -> Unit)? = null,
) {
    val remoteName = state.remotes.firstOrNull { it.id == guest.remoteId }?.name ?: guest.remoteId
    val busy = guest.id in state.busyIds
    val running = guest.status == "running"
    val canManage = state.writable.canManageGuest(guest.remoteId, guest.vmid)
    val pointer = LocalUiPosture.current.externalPointer
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    TechPlate(
        modifier = Modifier.secondaryClick(pointer) { menuOpen = true },
        railColor = techRailColor(guest.status),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (onOpen != null) Modifier.clickable(onClick = onOpen).handOnHover(pointer) else Modifier),
            ) {
                Text(guest.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append("${guest.type} ${guest.vmid} · $remoteName")
                        // Single-node remotes are usually named after their node; don't print it twice.
                        guest.node?.takeIf { it.isNotBlank() && !it.equals(remoteName, ignoreCase = true) }
                            ?.let { append(" · $it") }
                        append(" · ${guest.status}")
                    },
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (guest.tags.isNotEmpty()) TagLine(guest.tags)
            }
            if (canManage) {
                GuestPowerButtons(guest, paused = guest.status == "paused", busy = busy, vm = vm)
            }
        }
        // A stopped guest has no live usage; its bars would only read 0.
        if (!running) return@TechPlate
        Spacer(Modifier.height(8.dp))
        // The fleet's largest allocation sets the scale, so track lengths compare across guests.
        val largestMem = remember(state.guests) { state.guests.maxOfOrNull { it.maxmem ?: 0L } }
        val largestCores = remember(state.guests) { state.guests.maxOfOrNull { it.maxcpu ?: 0.0 } }
        // End short of the plate's cut corner; the bottom bar used to run into the diagonal.
        Column(Modifier.padding(end = BAR_CORNER_CLEARANCE)) {
            TechUsageBar(
                "CPU",
                guest.cpu?.toFloat()?.coerceIn(0f, 1f),
                cpuCaption(guest.cpu, guest.maxcpu),
                span = AllocationScale.cores(guest.maxcpu, largestCores),
                capped = true,
            )
            TechUsageBar(
                "MEM",
                fractionOf(guest.mem, guest.maxmem),
                formatRatio(guest.mem, guest.maxmem),
                span = AllocationScale.span(guest.maxmem, largestMem),
                capped = true,
            )
            // PVE reports 0 disk use for VMs (it cannot see inside the image); only containers have a real value.
            if (guest.type == "lxc" || (guest.disk ?: 0L) > 0L) {
                TechUsageBar("DISK", fractionOf(guest.disk, guest.maxdisk), formatRatio(guest.disk, guest.maxdisk))
            }
        }
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (onOpen != null) {
            DropdownMenuItem(text = { MenuLabel("Open") }, onClick = { menuOpen = false; onOpen() })
        }
        if (canManage && !busy) {
            // The same confirm dialogs as the buttons on the row.
            guestPowerActions(guest, paused = guest.status == "paused").forEach { action ->
                DropdownMenuItem(
                    text = { MenuLabel(action.label) },
                    onClick = { menuOpen = false; vm.requestPower(guest, action) },
                )
            }
        }
    }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text.uppercase(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
}

/** Tags as `#name`, amber like the rail accents; searchable from the filter field. */
@Composable
internal fun TagLine(tags: List<String>) {
    Text(
        tags.joinToString("  ") { "#$it" },
        fontFamily = FontFamily.Monospace,
        color = TechColors.Amber,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Shutdown / Stop for running guests, Resume for paused VMs, Start otherwise. */
internal fun guestPowerActions(guest: Guest, paused: Boolean): List<GuestAction> = when {
    paused && guest.type == "qemu" -> listOf(GuestAction.RESUME, GuestAction.STOP)
    guest.status == "running" || paused -> listOf(GuestAction.SHUTDOWN, GuestAction.STOP)
    else -> listOf(GuestAction.START)
}

@Composable
internal fun GuestPowerButtons(guest: Guest, paused: Boolean, busy: Boolean, vm: FleetViewModel) {
    val actions = guestPowerActions(guest, paused)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            TechActionPlate(
                label = if (busy) "…" else action.label,
                onClick = { if (!busy) vm.requestPower(guest, action) },
                emphasized = action == GuestAction.START || action == GuestAction.RESUME,
            )
        }
    }
}

@Composable
internal fun NodeRow(
    node: FleetNode,
    onClick: (() -> Unit)? = null,
    /** Largest node memory in the list shown; sets the scale of the MEM track. */
    largestMem: Long? = null,
    /** Largest node core count in the list shown; sets the scale of the CPU track. */
    largestCores: Double? = null,
) {
    TechPlate(
        railColor = techRailColor(node.status),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(node.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        val haColor = when {
            node.isHaMaintenance -> TechColors.Amber
            node.isHaTransitionalOrFault -> MaterialTheme.colorScheme.error
            else -> TechColors.Mute
        }
        Text(
            buildString {
                if (!node.remoteId.equals(node.name, ignoreCase = true)) append("${node.remoteId} · ")
                append(node.status)
                if (node.haState != null) append(" · HA ${node.haState.uppercase()}")
                node.uptime?.let { append(" · up ${formatUptime(it)}") }
            },
            fontFamily = FontFamily.Monospace,
            color = if (node.haState != null) haColor else TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(end = BAR_CORNER_CLEARANCE)) {
            TechUsageBar(
                "CPU",
                node.cpu?.toFloat()?.coerceIn(0f, 1f),
                cpuCaption(node.cpu, node.maxcpu),
                span = AllocationScale.cores(node.maxcpu, largestCores),
                capped = largestCores != null,
            )
            TechUsageBar(
                "MEM",
                fractionOf(node.mem, node.maxmem),
                formatRatio(node.mem, node.maxmem),
                span = AllocationScale.span(node.maxmem, largestMem),
                capped = largestMem != null,
            )
        }
    }
}

/** Plate padding is 12 dp and its bottom-right cut is 28 dp: bars stop this far in to clear it. */
internal val BAR_CORNER_CLEARANCE = 20.dp

/** `12% · 8 cores`: the core count explains the length of the CPU track. */
internal fun cpuCaption(cpu: Double?, maxcpu: Double?): String {
    val cores = maxcpu?.takeIf { it > 0.0 } ?: return formatPercent(cpu)
    val count = if (cores % 1.0 == 0.0) cores.toInt().toString() else "%.1f".format(java.util.Locale.US, cores)
    return "${formatPercent(cpu)} · $count ${if (cores == 1.0) "core" else "cores"}"
}

@Composable
internal fun StorageRow(
    storage: FleetStorage,
    onClick: (() -> Unit)? = null,
    /** Largest pool in the list shown; sets the scale of the DISK track. */
    largestDisk: Long? = null,
) {
    TechPlate(
        railColor = techRailColor(storage.status),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(storage.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(
            buildString {
                append(storage.remoteId)
                storage.node?.let { append(" · $it") }
                if (storage.shared) append(" · shared")
                append(" · ${storage.status}")
            },
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(end = BAR_CORNER_CLEARANCE)) {
            TechUsageBar(
                "DISK",
                fractionOf(storage.disk, storage.maxdisk),
                storageCaption(storage.disk, storage.maxdisk),
                span = AllocationScale.storage(storage.maxdisk, largestDisk),
                capped = largestDisk != null,
            )
        }
    }
}

/** `61% · 120GiB / 196GiB`: the share first, then the sizes that set the track length. */
internal fun storageCaption(used: Long?, total: Long?): String {
    val frac = if (used != null && total != null && total > 0) used.toDouble() / total else null
    return listOfNotNull(frac?.let(::formatPercent), formatRatio(used, total)).joinToString(" · ")
}

@Composable
internal fun DatastoreRow(ds: FleetDatastore, onClick: (() -> Unit)? = null) {
    val frac = ds.usage?.toFloat()?.coerceIn(0f, 1f) ?: fractionOf(ds.disk, ds.maxdisk)
    TechPlate(
        railColor = if ((ds.usage ?: 0.0) >= 0.80) MaterialTheme.colorScheme.error else TechColors.Amber,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(ds.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(
            buildString {
                append(ds.remoteId)
                ds.maintenance?.let { append(" · $it") }
            },
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(end = BAR_CORNER_CLEARANCE)) {
            TechUsageBar("USAGE", frac, ds.usage?.let { formatPercent(it) } ?: formatRatio(ds.disk, ds.maxdisk), capped = true)
        }
    }
}

@Composable
internal fun NetworkRow(net: FleetNetwork, onClick: (() -> Unit)? = null) {
    TechPlate(
        railColor = techRailColor(net.status),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(net.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Protocols.zone(net.zoneType)?.let {
                Spacer(Modifier.width(10.dp))
                ProtocolTag(it)
            }
        }
        Text(
            buildString {
                append(net.remoteId)
                if (Protocols.zone(net.zoneType) == null) net.zoneType?.let { append(" · $it") }
                append(" · ${net.status}")
            },
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Following a task log: keep the newest line in view as lines arrive (Q2). Off, it never moves. */
@Composable
private fun FollowLogScroll(state: FleetUiState, shown: DashFocus?, listState: LazyListState) {
    val upid = (shown as? DashFocus.TaskDetail)?.upid ?: return
    val log = state.taskView?.takeIf { it.task.upid == upid }?.log ?: return
    LaunchedEffect(upid, log.lastN, log.follow) {
        if (!log.follow || log.lastN <= 0) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }
}
