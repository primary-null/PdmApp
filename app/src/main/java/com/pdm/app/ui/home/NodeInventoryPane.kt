package com.pdm.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.model.NetIfaceDto
import com.pdm.app.data.model.NodeInventory
import com.pdm.app.data.model.NodeStorageDto
import com.pdm.app.data.model.asFlag
import com.pdm.app.data.model.asText
import com.pdm.app.ui.components.Protocol
import com.pdm.app.ui.components.ProtocolFamily
import com.pdm.app.ui.components.ProtocolTag
import com.pdm.app.ui.components.Protocols
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.components.TechSectionLabel
import com.pdm.app.ui.components.TechUsageBar
import com.pdm.app.ui.util.AllocationScale
import com.pdm.app.ui.util.fractionOf

/** The node's own storages and network: what lives on this box, not the fleet-wide rollup. */
internal fun LazyListScope.nodeInventoryItems(nodeId: String, inv: NodeInventory) {
    inv.error?.let { err -> item { MutedLine(err) } }
    val storages = inv.storages
    if (storages != null) {
        // The node's largest pool sets the scale, so a 100 GiB pool's track is visibly shorter than 4 TiB.
        val largest = storages.maxOfOrNull { it.total ?: 0L }
        item { TechSectionLabel("Storage on node", storages.size) }
        items(storages, key = { detailItemKey("nst/$nodeId/${it.storage}") }) { NodeStorageRow(it, largest) }
    }
    if (inv.interfaces != null) {
        item { TechSectionLabel("Network", inv.logical.size) }
        items(inv.logical, key = { detailItemKey("nif/$nodeId/${it.iface}") }) { NetRow(it) }
        val physical = inv.physical
        if (physical.isNotEmpty()) item { PhysicalPorts(physical) }
    }
}

@Composable
private fun NodeStorageRow(s: NodeStorageDto, largest: Long?) {
    val active = s.active.asFlag() != false
    val enabled = s.enabled.asFlag() != false
    val frac = fractionOf(s.used, s.total)
    TechPlate(
        railColor = when {
            !enabled -> TechColors.Mute
            !active -> MaterialTheme.colorScheme.error
            (frac ?: 0f) >= 0.85f -> MaterialTheme.colorScheme.error
            (frac ?: 0f) >= 0.70f -> TechColors.Amber
            else -> TechColors.LinkGreen
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.storage,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            s.type?.let { NetTag(it, TechColors.Mute) }
            if (s.shared.asFlag() == true) {
                Spacer(Modifier.width(6.dp))
                NetTag("shared", TechColors.Amber)
            }
            if (!enabled || !active) {
                Spacer(Modifier.width(6.dp))
                NetTag(if (!enabled) "disabled" else "inactive", MaterialTheme.colorScheme.error)
            }
        }
        s.content?.takeIf { it.isNotBlank() }?.let { MutedLine(it.split(',').sorted().joinToString(" · ")) }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(end = 20.dp)) {
            TechUsageBar(
                "USED",
                frac,
                storageCaption(s.used, s.total),
                span = AllocationScale.storage(s.total, largest),
                capped = largest != null,
            )
        }
    }
}

/**
 * One interface as a port card: name, type tag and link state on top; the address set large
 * with its prefix muted; gateway as "via"; bridge ports, bond members or the VLAN parent as tags.
 * Everything reads left to right, so nothing sits in the plate's cut corner.
 */
@Composable
private fun NetRow(n: NetIfaceDto) {
    val active = n.active.asFlag() == true
    TechPlate(railColor = if (active) TechColors.LinkGreen else TechColors.Mute) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.iface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
            n.type?.let { type ->
                if (ifaceTypeLabel(type) == "VLAN") {
                    ProtocolTag(Protocols.vlan(Protocols.vlanId(n.iface, n.vlanId.asText())))
                } else {
                    // Interface kinds stay grey: on this card only protocols carry colour.
                    NetTag(ifaceTypeLabel(type), TechColors.Mute)
                }
            }
            if (n.bridgeVlanAware.asFlag() == true) {
                Spacer(Modifier.width(6.dp))
                ProtocolTag(Protocol("VLAN AWARE", ProtocolFamily.ADDRESS))
            }
            Spacer(Modifier.weight(1f))
            LinkState(active)
        }
        val addresses = listOfNotNull(n.cidr?.let { false to it }, n.cidr6?.let { true to it })
        if (addresses.isNotEmpty() || n.gateway != null) Spacer(Modifier.height(6.dp))
        addresses.forEach { (v6, cidr) -> AddressLine(v6, cidr) }
        n.gateway?.let { gw ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetLabel("VIA")
                Text(gw, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.bodySmall)
            }
        }
        val members = when {
            !n.bridgePorts.isNullOrBlank() -> "PORTS" to splitIfaces(n.bridgePorts)
            !n.slaves.isNullOrBlank() -> "MEMBERS" to splitIfaces(n.slaves)
            !n.vlanRawDevice.isNullOrBlank() -> "ON" to listOf(n.vlanRawDevice)
            else -> null
        }
        members?.let { (label, names) ->
            Spacer(Modifier.height(6.dp))
            TagRows(label, names, TechColors.Mute)
        }
        n.comments?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            MutedLine(it.trim())
        }
    }
}

/** Plain ports without an address: which have a link and which do not, as tags. */
@Composable
private fun PhysicalPorts(physical: List<NetIfaceDto>) {
    val up = physical.filter { it.active.asFlag() == true }.map { it.iface }
    val down = physical.filter { it.active.asFlag() != true }.map { it.iface }
    TechPlate(railColor = TechColors.Edge) {
        Text(
            "PHYSICAL PORTS",
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            letterSpacing = 1.2.sp,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(6.dp))
        if (up.isNotEmpty()) TagRows("LINK", up, TechColors.LinkGreen)
        if (up.isNotEmpty() && down.isNotEmpty()) Spacer(Modifier.height(8.dp))
        if (down.isNotEmpty()) TagRows("NO LINK", down, TechColors.Mute)
    }
}

/** `[IPv4]  192.0.2.31/24`: family as a protocol tag in the label slot, address bright, prefix muted. */
@Composable
private fun AddressLine(v6: Boolean, cidr: String) {
    val address = cidr.substringBefore('/')
    val prefix = cidr.substringAfter('/', "").takeIf { it.isNotEmpty() }
    val style = if (v6) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(72.dp)) { ProtocolTag(Protocols.ip(v6)) }
        Text(
            address,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        prefix?.let {
            Text(
                "/$it",
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = style,
            )
        }
    }
}

/** A label and its tags, four to a line so long port lists wrap instead of running off the plate. */
@Composable
private fun TagRows(label: String, names: List<String>, tint: Color) {
    names.chunked(4).forEachIndexed { index, chunk ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = if (index == 0) 0.dp else 4.dp),
        ) {
            NetLabel(if (index == 0) label else "")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chunk.forEach { NetTag(it, tint) } }
        }
    }
}

@Composable
private fun NetLabel(text: String) {
    Text(
        text,
        modifier = Modifier.width(72.dp),
        fontFamily = FontFamily.Monospace,
        color = TechColors.Mute,
        letterSpacing = 1.2.sp,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Small cut-corner tag in the plate style: interface names, types, flags. */
@Composable
private fun NetTag(text: String, tint: Color) {
    val shape = CutCornerShape(bottomEnd = 6.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, tint.copy(alpha = 0.7f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, fontFamily = FontFamily.Monospace, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun LinkState(active: Boolean) {
    val color = if (active) TechColors.LinkGreen else TechColors.Mute
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            if (active) "UP" else "DOWN",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 1.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

internal fun splitIfaces(raw: String): List<String> = raw.split(' ', ',', '\t').filter { it.isNotBlank() }

internal fun ifaceTypeLabel(type: String): String = when (type) {
    "bridge", "OVSBridge" -> "BRIDGE"
    "bond", "OVSBond" -> "BOND"
    "vlan", "OVSIntPort" -> "VLAN"
    "eth" -> "PORT"
    else -> type.uppercase()
}
