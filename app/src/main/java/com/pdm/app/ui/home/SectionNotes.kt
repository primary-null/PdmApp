package com.pdm.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pdm.app.data.ops.FleetSection
import com.pdm.app.data.ops.SectionStatus
import com.pdm.app.ui.FleetUiState
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.util.formatTimestamp

/** What a section's status means for the values on screen, in one short line. */
internal fun sectionLine(section: FleetSection, status: SectionStatus): String = when (status.kind) {
    SectionStatus.Kind.LIVE -> section.label
    SectionStatus.Kind.CACHED -> "${section.label}: saved copy" + (status.cachedAt?.let { " from ${formatTimestamp(it / 1000)}" } ?: "")
    SectionStatus.Kind.FORBIDDEN -> "${section.label}: your account can't read this"
    SectionStatus.Kind.UNSUPPORTED -> "${section.label}: this server doesn't offer it"
    SectionStatus.Kind.MISSING -> "${section.label}: not found on the server"
    SectionStatus.Kind.UNREACHABLE -> "${section.label}: no connection, showing what was read before"
    SectionStatus.Kind.FAILED -> "${section.label}: didn't load, showing what was read before"
}

/** Statuses worth calling out in the header: something that should have refreshed didn't. */
private fun SectionStatus.needsAttention(): Boolean =
    kind == SectionStatus.Kind.CACHED || kind == SectionStatus.Kind.UNREACHABLE || kind == SectionStatus.Kind.FAILED

/**
 * The status of [sections] where they are shown (Q4). Nothing when they are current; a muted line
 * for steady states (no permission, not offered); an amber line and Retry when a read failed.
 * Offline, everything is a saved copy and the header already says so.
 */
@Composable
internal fun SectionNote(state: FleetUiState, vm: FleetViewModel, vararg sections: FleetSection) {
    if (state.offline) return
    val shown = sections.mapNotNull { s -> state.sections[s]?.takeIf { !it.live }?.let { s to it } }
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { (section, status) -> SectionRow(section, status, state, vm) }
    }
}

@Composable
private fun SectionRow(section: FleetSection, status: SectionStatus, state: FleetUiState, vm: FleetViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            sectionLine(section, status),
            Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            color = if (status.needsAttention()) TechColors.Amber else TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
        )
        if (section.retryable && status.worthRetrying && !state.readOnly) {
            val busy = section in state.sectionRetrying
            TechActionPlate(label = if (busy) "Reading" else "Retry", onClick = { vm.retrySection(section) }, enabled = !busy)
        }
    }
}

/**
 * Under the header: how many parts of the last refresh didn't come back live, collapsed to one
 * line. Tapping it lists them with their own Retry.
 */
@Composable
internal fun SectionsLine(state: FleetUiState, vm: FleetViewModel) {
    if (state.offline || state.sync.cachedAt != null) return
    val stale = state.sections.filter { it.value.needsAttention() }
    if (stale.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            (if (stale.size == 1) "1 part didn't refresh" else "${stale.size} parts didn't refresh") + if (open) "  ▾" else "  ▸",
            Modifier.clickable { open = !open }.padding(vertical = 4.dp),
            fontFamily = FontFamily.Monospace,
            color = TechColors.Amber,
            style = MaterialTheme.typography.bodySmall,
        )
        if (open) {
            FleetSection.entries.filter { it in stale }.forEach { s -> SectionRow(s, stale.getValue(s), state, vm) }
        }
    }
}
