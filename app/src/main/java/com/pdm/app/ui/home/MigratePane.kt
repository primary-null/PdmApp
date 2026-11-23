package com.pdm.app.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.pdm.app.data.model.Guest
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.MigrateView
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechSwitch

/**
 * In-cluster: pick a node, PVE's preflight runs and its verdict is shown.
 * Cross-remote: pick remote, storage, bridge, VMID. The dialog is the confirmation.
 */
@Composable
internal fun MigrateDialog(guest: Guest, view: MigrateView, vm: FleetViewModel) {
    val req = view.request
    val running = guest.status == "running"
    val fleetState by vm.fleet.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { vm.closeMigrate() },
        containerColor = TechColors.Hull,
        title = { Text("MIGRATE ${guest.name}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MutedLine("${guest.type} ${guest.vmid} · ${guest.remoteId}${guest.node?.let { " / $it" } ?: ""} · ${guest.status}")
                ChipRow {
                    if (view.nodes.isNotEmpty()) {
                        TechActionPlate(
                            label = "Within ${guest.remoteId}",
                            onClick = { vm.updateMigrate { it.copy(crossRemote = false, targetNode = it.targetNode ?: view.nodes.first()) } },
                            emphasized = !req.crossRemote,
                        )
                    }
                    if (view.remotes.isNotEmpty()) {
                        TechActionPlate(
                            label = "To another remote",
                            onClick = { vm.updateMigrate { it.copy(crossRemote = true, targetRemote = it.targetRemote ?: view.remotes.first()) } },
                            emphasized = req.crossRemote,
                        )
                    }
                }
                if (!req.crossRemote) {
                    Label("TARGET NODE")
                    ChipRow {
                        view.nodes.forEach { n ->
                            val nodeObj = fleetState.nodes.firstOrNull { it.remoteId == guest.remoteId && it.name == n }
                            // Known HA states get a tag; a state this app doesn't know is shown as reported.
                            val haBadge = when {
                                nodeObj?.isHaMaintenance == true -> " [MAINT]"
                                nodeObj?.isHaTransitionalOrFault == true -> " [${nodeObj.haState.orEmpty().uppercase()}]"
                                nodeObj?.haState != null && nodeObj.haStateEnum == null -> " [HA ${nodeObj.haState.uppercase()}]"
                                else -> ""
                            }
                            TechActionPlate(
                                label = "$n$haBadge",
                                onClick = { vm.updateMigrate { it.copy(targetNode = n) } },
                                emphasized = req.targetNode == n,
                            )
                        }
                    }
                    if (view.warning != null) {
                        Text(
                            view.warning,
                            fontFamily = FontFamily.Monospace,
                            color = TechColors.Amber,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    view.haConcern?.let { concern ->
                        Text(concern, fontFamily = FontFamily.Monospace, color = TechColors.Amber, style = MaterialTheme.typography.labelSmall)
                        SwitchLine(
                            title = "Use this node anyway",
                            subtitle = "Migrate starts only once this is on.",
                            checked = view.haConfirmed,
                        ) { on -> vm.confirmMigrateHa(on) }
                    }
                    when {
                        guest.type != "qemu" -> MutedLine("Containers have no preflight; PVE checks when the move starts.")
                        view.checking -> MutedLine("PVE preflight…")
                        else -> view.check?.let { check ->
                            if (check.blockers.isEmpty()) {
                                Text("Preflight ok", fontFamily = FontFamily.Monospace, color = TechColors.LinkGreen, style = MaterialTheme.typography.labelSmall)
                            }
                            check.blockers.forEach {
                                Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            }
                            if (check.needsLocalDisks) {
                                Text(
                                    "Local disks move with the VM (slower): ${check.localDisks.joinToString()}",
                                    fontFamily = FontFamily.Monospace,
                                    color = TechColors.Amber,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                } else {
                    view.check?.guestBlockers?.forEach {
                        Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Label("TARGET REMOTE")
                    ChipRow {
                        view.remotes.forEach { r ->
                            TechActionPlate(label = r, onClick = { vm.updateMigrate { it.copy(targetRemote = r) } }, emphasized = req.targetRemote == r)
                        }
                    }
                    val targets = view.targets
                    if (view.targetsLoading) {
                        MutedLine("reading storages and bridges on ${req.targetRemote}…")
                    } else if (targets != null) {
                        Label("TARGET STORAGE")
                        ChipRow {
                            targets.storages.forEach { s ->
                                TechActionPlate(label = s, onClick = { vm.updateMigrate { it.copy(targetStorage = s) } }, emphasized = req.targetStorage == s)
                            }
                        }
                        if (targets.storages.isEmpty()) MutedLine("No storage on the target accepts ${guest.type} disks.")
                        Label("TARGET BRIDGE")
                        ChipRow {
                            targets.bridges.forEach { b ->
                                TechActionPlate(label = b, onClick = { vm.updateMigrate { it.copy(targetBridge = b) } }, emphasized = req.targetBridge == b)
                            }
                        }
                        OutlinedTextField(
                            value = req.targetVmid?.toString().orEmpty(),
                            onValueChange = { v -> vm.updateMigrate { it.copy(targetVmid = v.filter(Char::isDigit).take(9).toIntOrNull()) } },
                            label = { Text("TARGET VMID") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SwitchLine(
                            title = "Delete source after success",
                            subtitle = "Off keeps a locked copy on ${guest.remoteId}.",
                            checked = req.deleteSource,
                        ) { on -> vm.updateMigrate { it.copy(deleteSource = on) } }
                    }
                }
                if (running) {
                    SwitchLine(
                        title = if (guest.type == "qemu") "Live migration" else "Restart migration",
                        subtitle = if (guest.type == "qemu") "Keeps running while it moves." else "Stops, moves, starts again: brief downtime.",
                        checked = req.online,
                    ) { on -> vm.updateMigrate { it.copy(online = on) } }
                    if (!req.online) MutedLine("Off: the guest must be stopped before PVE will move it.")
                } else {
                    MutedLine("Guest is stopped: offline move.")
                }
                view.error?.let {
                    Text(it, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TechActionPlate(
                label = if (view.starting) "Starting" else "Migrate",
                onClick = { if (view.canStart) vm.startMigrate() },
                emphasized = view.canStart,
            )
        },
        dismissButton = { TechActionPlate(label = "Cancel", onClick = { vm.closeMigrate() }) },
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            MutedLine(subtitle)
        }
        TechSwitch(checked = checked, onCheckedChange = onChange)
    }
}
