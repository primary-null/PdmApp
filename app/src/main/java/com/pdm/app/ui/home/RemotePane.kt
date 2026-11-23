package com.pdm.app.ui.home

import androidx.compose.ui.unit.sp

import androidx.compose.foundation.clickable
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
import com.pdm.app.data.model.ClusterNodeInfo
import com.pdm.app.data.model.FirewallSummary
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.data.model.summary
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.components.TechSectionLabel
import com.pdm.app.ui.util.formatTaskAge

/** PDM collects remote metrics on a schedule; older than this means the dashboard is lagging. */
private const val METRICS_STALE_SECONDS = 15 * 60L

/** One remote at a glance: cluster health, nodes, firewall, updates, guests. */
internal fun LazyListScope.remoteDetailItems(state: FleetUiState, focus: DashFocus.RemoteDetail, vm: FleetViewModel) {
    val remote = state.remotes.firstOrNull { it.id == focus.id }
    item { DetailHeader(remote?.name ?: focus.id, onBack = vm::popFocus) }
    if (remote == null) {
        item { EmptyHint("Remote is gone from the last refresh.") }
        return
    }
    val view = state.remoteView?.takeIf { it.remoteId == focus.id }
    val detail = view?.detail
    item { RemoteHealthCard(remote, state, view?.loading == true && detail == null, detail) }
    view?.error?.let { err -> item { MutedLine(err) } }

    val clusterRead = state.remoteNotes[remote.id]
    clusterRead?.error?.let { err -> item { MutedLine(err) } }
    val clusterNotes = clusterRead?.text
    if (!clusterNotes.isNullOrBlank()) {
        item {
            TechPlate(railColor = TechColors.Edge) {
                Text(
                    "CLUSTER NOTES",
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    clusterNotes,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    val clusterNodes = detail?.nodes
    if (!clusterNodes.isNullOrEmpty()) {
        item { TechSectionLabel("Nodes", clusterNodes.size) }
        items(clusterNodes, key = { detailItemKey("rnode/${focus.id}/${it.name}") }) { n ->
            val fleetNode = state.nodes.firstOrNull { it.remoteId == focus.id && it.name == n.name }
            ClusterNodeRow(n, haState = fleetNode?.haState, onClick = fleetNode?.let { node -> { vm.openFocus(DashFocus.NodeDetail(node.id)) } })
        }
    }

    detail?.firewall?.let { fw ->
        item { TechSectionLabel("Firewall") }
        item { FirewallCard(fw.summary()) }
    }

    val updates = state.updates?.filter { it.remoteId == focus.id }
    if (!updates.isNullOrEmpty()) {
        item { TechSectionLabel("Updates", updates.sumOf { it.pending }) }
        items(updates, key = { detailItemKey("rupd/${it.id}") }) { u ->
            NodeUpdatesRow(u, onClick = { vm.openFocus(DashFocus.NodeUpdates(u.remoteId, u.node)) })
        }
    }

    val guests = state.guests.filter { it.remoteId == focus.id }
    item { TechSectionLabel("Guests", guests.size) }
    item {
        TechPlate(railColor = TechColors.Edge, modifier = Modifier.clickable { vm.openFocus(DashFocus.Guests) }) {
            val running = guests.count { it.status == "running" }
            MutedLine("$running running · ${guests.size - running} stopped · ${guests.count { it.type == "qemu" }} qemu · ${guests.count { it.type == "lxc" }} lxc")
        }
    }
}

@Composable
private fun RemoteHealthCard(remote: RemoteNode, state: FleetUiState, loading: Boolean, detail: com.pdm.app.data.model.RemoteDetail?) {
    val quorumLost = detail?.quorate == false
    val offline = detail?.nodes?.count { !it.online } ?: 0
    val rail = when {
        !remote.error.isNullOrBlank() || quorumLost -> MaterialTheme.colorScheme.error
        offline > 0 -> TechColors.Amber
        else -> TechColors.LinkGreen
    }
    TechPlate(railColor = rail) {
        Text(remote.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SpecLine("Type", remote.kind.uppercase())
        detail?.clusterName?.let { SpecLine("Cluster", it) }
        when {
            detail?.quorate == true -> SpecLine("Quorum", "quorate", valueColor = TechColors.LinkGreen)
            quorumLost -> SpecLine("Quorum", "LOST", valueColor = MaterialTheme.colorScheme.error)
            detail?.nodes != null -> SpecLine("Quorum", "single node")
        }
        detail?.nodes?.let { nodes ->
            SpecLine(
                "Nodes online",
                "${nodes.count { it.online }} / ${nodes.size}",
                valueColor = if (offline > 0) TechColors.Amber else MaterialTheme.colorScheme.onSurface,
            )
        }
        detail?.version?.version?.let { SpecLine("Version", it) }
        // Licence state is a quiet fact, never an alarm: most homelabs run without one.
        state.subscriptions.firstOrNull { it.remote == remote.id }?.state?.let { sub ->
            SpecLine("Subscription", subscriptionLabel(sub), valueColor = TechColors.Mute)
        }
        detail?.lastMetricCollection?.let { at ->
            val stale = System.currentTimeMillis() / 1000 - at > METRICS_STALE_SECONDS
            SpecLine(
                "Metrics",
                "collected ${formatTaskAge(at)}" + if (stale) " (stale)" else "",
                valueColor = if (stale) TechColors.Amber else MaterialTheme.colorScheme.onSurface,
            )
        }
        remote.endpoints.forEach { SpecLine("Endpoint", it.display) }
        remote.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        if (loading) MutedLine("reading cluster status…")
    }
}

@Composable
private fun ClusterNodeRow(n: ClusterNodeInfo, haState: String? = null, onClick: (() -> Unit)? = null) {
    TechPlate(
        railColor = if (n.online) TechColors.LinkGreen else MaterialTheme.colorScheme.error,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                MutedLine(listOfNotNull(n.ip, haState?.let { "HA ${it.uppercase()}" }, "PDM talks to this node".takeIf { n.local }).joinToString(" · "))
            }
            Text(
                if (n.online) "online" else "OFFLINE",
                fontFamily = FontFamily.Monospace,
                color = if (n.online) TechColors.LinkGreen else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun FirewallCard(fw: FirewallSummary) {
    TechPlate(railColor = if (fw.clusterEnabled) TechColors.LinkGreen else TechColors.Amber) {
        SpecLine(
            "Cluster firewall",
            if (fw.clusterEnabled) "on" else "off",
            valueColor = if (fw.clusterEnabled) TechColors.LinkGreen else TechColors.Amber,
        )
        SpecLine("Nodes with firewall on", "${fw.nodesEnabled} / ${fw.nodes}")
        SpecLine("Guests with firewall on", "${fw.guestsEnabled} / ${fw.guests}")
        SpecLine("Active rules", fw.activeRules.toString())
        if (!fw.clusterEnabled) {
            // PVE only enforces node and guest rules when the datacenter-level switch is on.
            MutedLine("Off at cluster level: node and guest settings are not enforced.")
        }
    }
}