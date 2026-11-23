package com.pdm.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.data.net.DiscoveredHost
import com.pdm.app.data.net.ScanStep
import com.pdm.app.data.net.ScanTone
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.NetworkScanState
import com.pdm.app.ui.components.Protocol
import com.pdm.app.ui.components.ProtocolFamily
import com.pdm.app.ui.components.ProtocolTag
import com.pdm.app.ui.components.Protocols
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Lines the running terminal shows; it keeps this height, so the screen never jumps. */
private const val TAIL_LINES = 8

/** Lines SHOW LOG opens at most; earlier ones are counted. */
private const val FULL_LINES = 40

/** How long DONE stays on screen before the log folds into the summary. */
private const val FOLD_DELAY_MS = 1_500L

private val LINE = 18.dp

/**
 * Network scan under the login form. While it runs, a small terminal shows what the sweep is
 * doing: the subnet, each open port, the TLS answer and the verdict (closed addresses are only
 * counted). When it finishes, DONE holds for a moment and the log folds into one summary plate.
 * SHOW LOG opens it again.
 */
@Composable
internal fun ColumnScope.ScanResults(scan: NetworkScanState, vm: FleetViewModel) {
    val visible = scan.isScanning || scan.isComplete || scan.log.isNotEmpty() ||
        scan.error != null || scan.infoMessage != null
    if (!visible) return

    var showLog by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(scan.isScanning) {
        if (scan.isScanning) {
            showLog = true
        } else {
            delay(FOLD_DELAY_MS)
            showLog = false
        }
    }
    val terminal = scan.isScanning || (showLog && scan.log.isNotEmpty())

    // Scroll the plate into view (the same scroll a focused field gets above the keyboard,
    // and only as far as needed) when a scan starts and when it folds into the summary.
    // Coming back to a finished scan leaves the page where it is.
    val bring = remember { BringIntoViewRequester() }
    var quiet by remember { mutableStateOf(!scan.isScanning) }
    LaunchedEffect(scan.isScanning, terminal) {
        val starting = scan.isScanning
        val folded = !scan.isScanning && !terminal
        if (starting) quiet = false
        if (starting || (folded && !quiet)) {
            withFrameNanos { } // let the plate lay out at its new size first
            bring.bringIntoView()
        }
    }
    val found = scan.discoveredHosts.isNotEmpty() || scan.pdmDetectedHosts.isNotEmpty()
    val rail = when {
        scan.error != null -> MaterialTheme.colorScheme.error
        scan.isScanning -> TechColors.Amber
        found -> TechColors.LinkGreen
        else -> TechColors.Edge
    }

    Spacer(Modifier.height(16.dp))
    TechPlate(Modifier.bringIntoViewRequester(bring), railColor = rail) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    scan.error != null -> "SCAN ERROR"
                    scan.isScanning -> "SCANNING"
                    !scan.isComplete -> "STOPPED"
                    terminal -> "DONE"
                    else -> "NETWORK"
                },
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = when {
                    scan.error != null -> MaterialTheme.colorScheme.error
                    scan.isScanning -> TechColors.Amber
                    found -> TechColors.LinkGreen
                    else -> TechColors.Mute
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                if (scan.isScanning || !scan.isComplete) {
                    "${scan.scannedCount} / ${scan.totalCount}".takeIf { scan.totalCount > 0 }.orEmpty()
                } else {
                    listOfNotNull(
                        scan.networks.joinToString().ifBlank { null },
                        "${scan.totalCount} checked".takeIf { scan.totalCount > 0 },
                        scan.elapsedMs?.let { "${it / 100 / 10.0} s" },
                    ).joinToString(" · ")
                },
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (terminal) {
            if (scan.isScanning) {
                Spacer(Modifier.height(8.dp))
                ScanProgressLine(if (scan.totalCount > 0) scan.scannedCount.toFloat() / scan.totalCount else 0f)
            }
            Spacer(Modifier.height(8.dp))
            ScanTerminal(scan.log, running = scan.isScanning)
        } else {
            Spacer(Modifier.height(6.dp))
            ScanSummary(scan, vm)
        }

        scan.infoMessage?.let { ScanNote(it, TechColors.Mute) }
        scan.error?.let { ScanNote(it, MaterialTheme.colorScheme.error) }

        if (!scan.isScanning) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (scan.totalCount > 0) "${(scan.totalCount - scan.openCount).coerceAtLeast(0)} closed" else "",
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (scan.log.isNotEmpty()) {
                    TechActionPlate(label = if (showLog) "Hide log" else "Show log", onClick = { showLog = !showLog })
                    Spacer(Modifier.width(8.dp))
                }
                TechActionPlate(label = "Clear", onClick = vm::clearScanResults)
            }
        }
    }
}

@Composable
private fun ScanProgressLine(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(2.dp).background(TechColors.Deck)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(TechColors.Amber))
    }
}

/**
 * The log, oldest at the top. Running, it is a fixed window of the last [TAIL_LINES] lines that
 * fills from the bottom; opened afterwards, it shows the whole run (up to [FULL_LINES]).
 */
@Composable
private fun ScanTerminal(log: List<ScanStep>, running: Boolean) {
    val lines = if (running) log.takeLast(TAIL_LINES) else log.takeLast(FULL_LINES)
    // Phones drop the clock so the verdict keeps its room. The window decides, not the plate:
    // TechPlate measures its content's intrinsic height, which BoxWithConstraints cannot answer.
    val window = LocalWindowInfo.current.containerSize
    val showTime = with(LocalDensity.current) { window.width.toDp() } >= 600.dp
    Column(
        if (running) Modifier.fillMaxWidth().height(LINE * TAIL_LINES) else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (!running && log.size > FULL_LINES) {
            ScanNote("+${log.size - FULL_LINES} earlier lines", TechColors.Mute)
        }
        if (lines.isEmpty()) {
            LogText("knocking on port 8443…", TechColors.Mute)
        }
        lines.forEach { LogLine(it, showTime) }
    }
}

@Composable
private fun LogLine(step: ScanStep, showTime: Boolean) {
    Row(Modifier.height(LINE), verticalAlignment = Alignment.CenterVertically) {
        if (showTime) LogText(clock(step.at), TechColors.Mute.copy(alpha = 0.7f), Modifier.width(66.dp))
        LogText(step.kind, TechColors.Amber.copy(alpha = 0.7f), Modifier.width(42.dp))
        LogText(step.target, MaterialTheme.colorScheme.onSurface, Modifier.width(118.dp))
        LogText(
            step.text,
            when (step.tone) {
                ScanTone.GOOD -> TechColors.LinkGreen
                ScanTone.WARN -> TechColors.Amber
                ScanTone.QUIET -> TechColors.Mute
                ScanTone.PLAIN -> if (step.kind == "done") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            },
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun LogText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Every host that answered, one row each: verified, then sign-in needed, then unconfirmed. */
@Composable
private fun ScanSummary(scan: NetworkScanState, vm: FleetViewModel) {
    val none = scan.discoveredHosts.isEmpty() && scan.pdmDetectedHosts.isEmpty() && scan.unverifiedHosts.isEmpty()
    if (none) {
        if (scan.isComplete && scan.error == null) ScanNote("No Datacenter Manager on 8443.", TechColors.Mute)
        return
    }
    scan.discoveredHosts.forEach { host ->
        HostRow(host.ip, host.port, hostDetail(host, "PDM"), TechColors.LinkGreen, hostTags(host)) { vm.onCandidateSelected(host) }
    }
    scan.pdmDetectedHosts.forEach { host ->
        HostRow(host.ip, host.port, hostDetail(host, "PDM · sign-in needed"), TechColors.Amber, hostTags(host)) {
            vm.onCandidateSelected(host)
        }
    }
    scan.unverifiedHosts.forEach { ip ->
        HostRow(ip, 8443, "port open · not confirmed", TechColors.Mute, listOf(Protocol("TCP", ProtocolFamily.QUIET))) {
            vm.onUnverifiedSelected(ip)
        }
    }
}

@Composable
private fun HostRow(
    ip: String,
    port: Int,
    detail: String,
    dot: Color,
    tags: List<Protocol>,
    onUse: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onUse).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ip, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(":$port", fontFamily = FontFamily.Monospace, color = TechColors.Mute)
            }
            Text(detail, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
        }
        tags.forEach {
            Spacer(Modifier.width(6.dp))
            ProtocolTag(it)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "USE",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = TechColors.Amber,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ScanNote(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp),
        fontFamily = FontFamily.Monospace,
        color = color,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun hostDetail(host: DiscoveredHost, what: String): String =
    if (host.latencyMs > 0) "$what · ${host.latencyMs} ms" else what

private fun hostTags(host: DiscoveredHost): List<Protocol> = listOfNotNull(Protocols.HTTPS, host.tls?.let { Protocols.tls(it) })

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun clock(epochMs: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
