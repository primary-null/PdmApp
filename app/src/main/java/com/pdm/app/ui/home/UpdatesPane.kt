package com.pdm.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.model.AptUpdateDto
import com.pdm.app.data.model.NodeUpdates
import com.pdm.app.data.model.canManageNodeUpdates
import com.pdm.app.data.model.canOpenNodeShell
import com.pdm.app.data.model.rollup
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.NodeUpdatesView
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.util.formatTaskAge

/** Every node across every remote: what is pending and whether its repositories are sane. */
internal fun LazyListScope.updatesItems(state: FleetUiState, vm: FleetViewModel) {
    val remoteId = state.selectedRemoteId
    val nodes = state.updates?.filter { remoteId == null || it.remoteId == remoteId }
    // Same total as the dashboard tile: fleet nodes plus the PDM host when unfiltered.
    val hostPending = state.host?.updates?.size?.takeIf { remoteId == null } ?: 0
    item { DetailHeader("Updates", nodes?.let { it.sumOf { n -> n.pending } + hostPending }, onBack = vm::popFocus, hero = true) }
    item { SectionNote(state, vm, com.pdm.app.data.ops.FleetSection.UPDATES, com.pdm.app.data.ops.FleetSection.HOST_UPDATES) }
    item {
        TechPlate(railColor = TechColors.Edge) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val roll = nodes?.rollup()
                    MutedLine(
                        when {
                            roll == null -> "PDM's update summary did not load."
                            else -> "${roll.pending} on ${roll.nodesWithUpdates} of ${nodes.size} nodes" +
                                (if (hostPending > 0) " + $hostPending on PDM" else "") +
                                (if (roll.repoWarnings > 0) " · ${roll.repoWarnings} repository warnings" else "")
                        },
                    )
                    MutedLine("PDM's cached view. Refresh re-polls every remote; open a node to check it now or upgrade it.")
                }
                if (!state.readOnly) {
                    TechActionPlate(
                        label = if (state.updatesRefreshing) "Refreshing" else "Refresh",
                        onClick = vm::refreshUpdates,
                    )
                }
            }
        }
    }
    // The manager itself is a Debian box too; its pending packages sit above the fleet's.
    state.host?.takeIf { remoteId == null }?.let { host ->
        item { HostUpdatesRow(host, onClick = { vm.openFocus(DashFocus.Host) }) }
    }
    when {
        nodes == null -> item { EmptyHint("No update data yet.") }
        nodes.isEmpty() -> item { EmptyHint("No nodes in this filter.") }
        else -> items(nodes, key = { detailItemKey("upd/${it.id}") }) { n ->
            NodeUpdatesRow(n, onClick = { vm.openFocus(DashFocus.NodeUpdates(n.remoteId, n.node)) })
        }
    }
}

@Composable
internal fun NodeUpdatesRow(n: NodeUpdates, onClick: () -> Unit) {
    val rail = when {
        n.error != null -> MaterialTheme.colorScheme.error
        n.pending > 0 || !n.repoOk -> TechColors.Amber
        else -> TechColors.LinkGreen
    }
    TechPlate(railColor = rail, modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.node, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                MutedLine(
                    listOfNotNull(
                        n.remoteId.takeIf { !it.equals(n.node, ignoreCase = true) },
                        n.version?.let { "pve $it" },
                        n.lastRefresh?.let { "checked ${formatTaskAge(it)}" },
                    ).joinToString(" · "),
                )
                if (!n.repoOk) {
                    Text(
                        repositoryLabel(n.repositoryStatus),
                        fontFamily = FontFamily.Monospace,
                        color = TechColors.Amber,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                n.error?.let {
                    Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    n.pending.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = if (n.pending > 0) TechColors.Amber else TechColors.LinkGreen,
                )
                MutedLine(if (n.pending == 1) "update" else "updates")
            }
        }
    }
}

/** Live package list for one node. Tap a package for its changelog. */
internal fun LazyListScope.nodeUpdatesItems(state: FleetUiState, focus: DashFocus.NodeUpdates, vm: FleetViewModel) {
    val view: NodeUpdatesView? = state.nodeUpdatesView?.takeIf { it.remoteId == focus.remoteId && it.node == focus.node }
    val summary = state.updates?.firstOrNull { it.remoteId == focus.remoteId && it.node == focus.node }
    item { DetailHeader("${focus.node} updates", view?.packages?.size, onBack = vm::popFocus, hero = true) }
    summary?.let { item { NodeUpdatesRow(it, onClick = {}) } }
    val webUi = state.remotes.firstOrNull { it.id == focus.remoteId }?.webUiUrl(focus.node)
    val canCheck = view != null && state.writable.canManageNodeUpdates(focus.remoteId, focus.node)
    val canShell = !state.readOnly && state.writable.canOpenNodeShell(focus.remoteId, focus.node)
    if (canCheck || canShell || webUi != null) {
        item {
            NodeUpdateActions(
                view = view,
                canCheck = canCheck,
                // Token sessions see why the button is missing instead of a button that fails.
                shell = if (canShell) state.shellAllowed else null,
                webUi = webUi,
                onCheck = vm::checkNodeUpdates,
                onUpgrade = { vm.requestNodeUpgrade(focus.remoteId, focus.node) },
            )
        }
    }
    val packages = view?.packages
    when {
        view == null || (view.loading && packages == null) -> item { EmptyHint("Reading the node's package list…") }
        packages == null -> item {
            EmptyHint("Package list did not load: ${view.error.orEmpty()}")
            TechActionPlate(label = "Retry", onClick = vm::retryNodeUpdates)
        }
        packages.isEmpty() -> item { EmptyHint("Nothing pending on ${focus.node}.") }
        else -> items(packages, key = { detailItemKey("pkg/${focus.remoteId}/${focus.node}/${it.name}") }) { pkg ->
            PackageRow(pkg, open = view.openPackage == pkg.name, changelog = view.changelog.takeIf { view.openPackage == pkg.name }) {
                vm.togglePackage(pkg.name)
            }
        }
    }
}

/**
 * Check now runs `apt update` through PDM. Upgrade opens a root shell relayed by PDM with
 * `apt full-upgrade` typed; [shell] is null without Sys.Console, false for token sessions.
 */
@Composable
private fun NodeUpdateActions(
    view: NodeUpdatesView?,
    canCheck: Boolean,
    shell: Boolean?,
    webUi: String?,
    onCheck: () -> Unit,
    onUpgrade: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val checking = view?.checking == true
    TechPlate(railColor = view?.checkError?.let { MaterialTheme.colorScheme.error } ?: TechColors.Edge) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Wraps instead of clipping: a narrow pane puts the last button on a second line.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canCheck) {
                    TechActionPlate(
                        label = if (checking) "Checking" else "Check now",
                        onClick = onCheck,
                        enabled = !checking,
                    )
                }
                if (shell == true) {
                    TechActionPlate(label = "Upgrade", onClick = onUpgrade, emphasized = true)
                }
                if (webUi != null) {
                    TechActionPlate(label = "Open in PVE", onClick = { runCatching { uriHandler.openUri(webUi) } }, emphasized = shell != true)
                }
            }
            MutedLine(
                when {
                    checking -> "Checking for updates…"
                    shell == true -> "Upgrade signs you in to the node and gets the upgrade ready to run."
                    shell == false -> "To upgrade from here, sign in to PDM with a password instead of an API token. Or use Open in PVE."
                    webUi != null -> "Open in PVE opens the node's own page, where you can upgrade it."
                    else -> "Upgrade from the node's own web page."
                },
            )
            view?.checkError?.let {
                Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun PackageRow(pkg: AptUpdateDto, open: Boolean, changelog: String?, onClick: () -> Unit) {
    val origin = pkg.origin?.takeIf { it.isNotBlank() }
    TechPlate(
        railColor = if (origin?.equals("Proxmox", ignoreCase = true) == true) TechColors.Amber else TechColors.Edge,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end = if (origin == null) 0.dp else 108.dp)) {
                Text(pkg.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                MutedLine("${pkg.oldVersion ?: "—"} → ${pkg.version ?: "—"}")
                pkg.title?.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
                if (open) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        changelog?.lineSequence()?.take(40)?.joinToString("\n") ?: "loading changelog…",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (origin != null) {
                OriginStamp(origin, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/** Cut-corner origin mark. Proxmox is amber, Debian is green, anything else stays mute. */
@Composable
private fun OriginStamp(origin: String, modifier: Modifier = Modifier) {
    val accent = when {
        origin.equals("Proxmox", ignoreCase = true) -> TechColors.Amber
        origin.equals("Debian", ignoreCase = true) -> TechColors.LinkGreen
        else -> TechColors.Mute
    }
    val shape = CutCornerShape(bottomStart = 10.dp)
    Text(
        origin.uppercase(),
        modifier = modifier
            .clip(shape)
            .background(TechColors.Scrim.copy(alpha = 0.45f))
            .border(1.dp, accent, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
        color = accent,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** PDM's repository verdicts, in operator words. Unknown values pass through. */
internal fun repositoryLabel(status: String?): String = when (status) {
    "non-production-ready" -> "no-subscription / test repos (not for production)"
    "no-products" -> "no Proxmox repository configured"
    "missing-subscription" -> "enterprise repo without a subscription"
    null, "", "ok" -> "repositories ok"
    else -> "repositories: $status"
}
