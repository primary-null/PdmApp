package com.pdm.app.ui.home

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.pdm.app.data.api.ApiActivity
import com.pdm.app.data.cache.ResponseCache
import com.pdm.app.data.ops.RestartProgress
import com.pdm.app.ui.Freshness
import com.pdm.app.ui.NoteTone
import com.pdm.app.ui.RefreshPolicy
import com.pdm.app.ui.SyncNote
import com.pdm.app.ui.SyncState
import com.pdm.app.ui.components.ProtocolTag
import com.pdm.app.ui.components.Protocols
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** At most this many loads in the panel; older ones fold away. */
private const val LOAD_ROWS = 6

/** At most this many requests when a load is opened. */
private const val REQUEST_ROWS = 12

private const val OTHERS_KEY = -1L

/** Same wording as the header chip, including a restart that must not read as LIVE. */
internal fun livePanelSyncLabel(
    sync: SyncState,
    now: Long,
    wallNow: Long,
    hostRestart: RestartProgress?,
): SyncLabel = syncLabel(sync, now, wallNow, hostRestart)

/**
 * The header chip opened up (long press). A fixed-size readout, not a log: four sync lines, the
 * last few loads (repeats folded, tap one for its requests), the detail pane's own reads, and the
 * few events worth noticing. Anchored over the chip and starting with its line. Close / outside / Back.
 */
@Composable
internal fun LivePanel(
    sync: SyncState,
    offline: Boolean,
    cacheStats: ResponseCache.Stats?,
    cacheWriting: Boolean,
    requests: List<ApiActivity>,
    journal: List<SyncNote>,
    hostRestart: RestartProgress? = null,
    onClose: () -> Unit,
) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val wall = System.currentTimeMillis()
    val label = livePanelSyncLabel(sync, now, wall, hostRestart)
    val color = when (label.freshness) {
        Freshness.LIVE -> TechColors.LinkGreen
        Freshness.FAILING -> TechColors.Danger
        else -> TechColors.Mute
    }
    val cycles = remember(journal, requests) { loadCycles(journal, requests, LOAD_ROWS) }
    val others = remember(requests, cycles) { otherRequests(requests, cycles) }
    val events = remember(journal) { notableEvents(journal) }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }

    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val maxWidth = with(density) { (window.width.toDp() - 24.dp).coerceAtMost(560.dp) }
    val maxHeight = with(density) { window.height.toDp() * 0.8f }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Popup(alignment = Alignment.TopEnd, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(shown, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn()) {
            val shape = CutCornerShape(bottomEnd = 28.dp)
            Column(
                Modifier
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .background(TechColors.Hull)
                    .border(1.dp, TechColors.Edge, shape)
                    .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label.text,
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = if (label.freshness == Freshness.LIVE) MaterialTheme.colorScheme.onSurface else color,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TechActionPlate(label = "Close", onClick = onClose)
                }
                // Scrolls only when an opened load runs past the bottom; closed, it fits.
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
                    PanelHeading("Sync")
                    PanelLine("Now", if (offline) "offline view · saved data, changes off" else label.spoken)
                    PanelLine("Next", nextText(sync, now))
                    PanelLine(
                        "Loads",
                        listOfNotNull(
                            sync.lastSuccessAt?.let { "live ${RefreshPolicy.age(now - it)} ago" },
                            sync.lastFullAt?.let { "full ${RefreshPolicy.age(now - it)} ago" },
                            "${sync.failures} failed in a row".takeIf { sync.failures > 0 },
                            "retries paused".takeIf { sync.paused },
                        ).joinToString(" · ").ifBlank { "none yet" },
                    )
                    PanelLine(
                        "Cache",
                        when {
                            cacheStats == null || cacheStats.entries == 0 -> "nothing saved for this login"
                            else -> "${cacheStats.entries} entries · ${cacheStats.bytes / 1024} KB · " +
                                if (cacheWriting) "saving" else "not saving"
                        },
                    )

                    Spacer(Modifier.height(10.dp))
                    PanelHeading("Loads")
                    if (cycles.isEmpty()) {
                        PanelNote(if (offline) "Nothing loaded yet: the offline view reads the cache." else "Waiting for the next load…")
                    }
                    cycles.forEach { cycle ->
                        val expanded = open == cycle.at
                        CycleRow(cycle, expanded) { open = if (expanded) null else cycle.at }
                        if (expanded) RequestList(cycle.requests)
                    }
                    if (others.isNotEmpty()) {
                        val expanded = open == OTHERS_KEY
                        OthersRow(others, expanded) { open = if (expanded) null else OTHERS_KEY }
                        if (expanded) RequestList(others)
                    }

                    Spacer(Modifier.height(10.dp))
                    PanelHeading("Events")
                    if (events.isEmpty()) PanelNote("Nothing unusual.")
                    events.forEach { note ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            TimeCell(note.at)
                            Text(
                                note.text,
                                fontFamily = FontFamily.Monospace,
                                color = toneColor(note.tone, MaterialTheme.colorScheme.onSurface),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `17:07:05  TIMER · QUICK   live   7 req · 54 ms   ×5`: one line; tap for the requests. */
@Composable
private fun CycleRow(cycle: LoadCycle, expanded: Boolean, onToggle: () -> Unit) {
    val slowest = cycle.requests.mapNotNull { it.elapsedMs }.maxOrNull()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeCell(cycle.until)
        Text(
            cycle.trigger.uppercase(),
            modifier = Modifier.width(150.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            cycle.result ?: "running…",
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            color = toneColor(cycle.tone, TechColors.Mute),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(
                "${cycle.requests.size} req".takeIf { cycle.requests.isNotEmpty() },
                slowest?.let { "$it ms" },
            ).joinToString(" · "),
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
        )
        if (cycle.repeats > 1) {
            Spacer(Modifier.width(8.dp))
            RepeatTag(cycle.repeats)
        }
        Spacer(Modifier.width(6.dp))
        Text(if (expanded) "▾" else "▸", color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun OthersRow(others: List<ApiActivity>, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeCell(others.last().startedAt)
        Text(
            "SCREEN READS",
            modifier = Modifier.width(150.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "detail pane, graphs, logs",
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text("${others.size} req", fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(6.dp))
        Text(if (expanded) "▾" else "▸", color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
    }
}

/** Every request line has this height, so the four columns stay in step. */
private val REQUEST_LINE = 22.dp

/**
 * A load's requests, newest first, indented under its row: `[GET] /resources/list  [200]  46 ms`.
 * Laid out as columns, so the method and outcome columns are as wide as the widest tag actually
 * shown: no gap when every method is GET, endpoints still aligned when a DELETE appears.
 */
@Composable
private fun RequestList(requests: List<ApiActivity>) {
    val shown = requests.asReversed().take(REQUEST_ROWS)
    Column(Modifier.padding(start = 70.dp, bottom = 6.dp)) {
        if (requests.isEmpty()) PanelNote("Its requests have aged out of the panel.")
        if (shown.isNotEmpty()) {
            Row {
                RequestColumn(shown, Modifier.width(IntrinsicSize.Max)) { ProtocolTag(Protocols.method(it.route.method)) }
                Spacer(Modifier.width(10.dp))
                RequestColumn(shown, Modifier.weight(1f)) {
                    Text(
                        it.route.endpoint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                RequestColumn(shown, Modifier.width(IntrinsicSize.Max)) { ProtocolTag(Protocols.outcome(it)) }
                RequestColumn(shown, Modifier.width(60.dp), Alignment.CenterEnd) {
                    Text(
                        it.elapsedMs?.let { ms -> "$ms ms" } ?: "",
                        fontFamily = FontFamily.Monospace,
                        color = TechColors.Mute,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (requests.size > REQUEST_ROWS) PanelNote("+${requests.size - REQUEST_ROWS} more")
    }
}

@Composable
private fun RequestColumn(
    shown: List<ApiActivity>,
    modifier: Modifier = Modifier,
    align: Alignment = Alignment.CenterStart,
    cell: @Composable (ApiActivity) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { Box(Modifier.height(REQUEST_LINE).fillMaxWidth(), contentAlignment = align) { cell(it) } }
    }
}

@Composable
private fun RepeatTag(count: Int) {
    val shape = CutCornerShape(bottomEnd = 5.dp)
    Box(Modifier.clip(shape).border(1.dp, TechColors.Edge, shape).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text("×$count", fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TimeCell(at: Long) {
    Text(
        clock(at),
        modifier = Modifier.width(70.dp),
        fontFamily = FontFamily.Monospace,
        color = TechColors.Mute,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun PanelHeading(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(bottom = 4.dp),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        color = TechColors.Amber,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun PanelLine(name: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            name.uppercase(),
            modifier = Modifier.width(70.dp),
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            letterSpacing = 1.sp,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PanelNote(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun toneColor(tone: NoteTone, neutral: Color): Color = when (tone) {
    NoteTone.GOOD -> TechColors.LinkGreen
    NoteTone.BAD -> TechColors.Danger
    NoteTone.MUTED -> TechColors.Mute
    NoteTone.NEUTRAL -> neutral
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun clock(epochMs: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

internal fun nextText(sync: SyncState, now: Long): String = when {
    sync.paused -> "paused until you pull or tap"
    sync.running -> "loading now"
    else -> {
        val due = RefreshPolicy.dueIn(sync, now)
        val every = "every ${RefreshPolicy.age(RefreshPolicy.interval(sync.failures))}"
        if (due <= 0) "due now · $every" else "in ${RefreshPolicy.age(due)} · $every"
    }
}
