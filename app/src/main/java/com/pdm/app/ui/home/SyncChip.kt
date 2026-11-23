package com.pdm.app.ui.home

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.ui.Freshness
import com.pdm.app.ui.RefreshPolicy
import com.pdm.app.ui.SyncState
import com.pdm.app.data.ops.RestartProgress
import com.pdm.app.ui.components.TechColors
import kotlinx.coroutines.delay

/** What the header chip says. Kept apart from the composable so the wording is testable. */
internal data class SyncLabel(val text: String, val spoken: String, val freshness: Freshness)

/** [now] is `elapsedRealtime` (sync times); [wallNow] is epoch ms (cache save times). */
internal fun syncLabel(sync: SyncState, now: Long, wallNow: Long, hostRestart: RestartProgress? = null): SyncLabel {
    // While a restart is unsettled the chip never says LIVE, whatever the last load did.
    if (hostRestart != null && !hostRestart.settled) {
        val words = restartWords(hostRestart, ((wallNow - hostRestart.submittedAt) / 1000).coerceAtLeast(0))
        return SyncLabel(words.chip, words.spoken, if (words.problem) Freshness.FAILING else Freshness.LOADING)
    }
    val freshness = RefreshPolicy.freshness(sync, now)
    val age = sync.cachedAt?.let { RefreshPolicy.age(wallNow - it) }
        ?: sync.lastSuccessAt?.let { RefreshPolicy.age(now - it) }
    val shown = when {
        sync.cachedAt != null -> ", showing cached data from $age ago"
        age != null -> ", data is $age old"
        else -> ""
    }
    return when {
        sync.manual -> SyncLabel("SYNCING", "Refreshing the fleet", freshness)
        freshness == Freshness.FAILING && sync.running -> SyncLabel("RECONNECTING", "No connection, trying again$shown", freshness)
        freshness == Freshness.FAILING && sync.paused -> SyncLabel("OFFLINE", "Not connected$shown. Sign in again to go live", freshness)
        freshness == Freshness.FAILING -> {
            val wait = RefreshPolicy.age(RefreshPolicy.dueIn(sync, now).coerceAtLeast(0))
            SyncLabel("OFFLINE · RETRY $wait", "No connection$shown. Next try in $wait", freshness)
        }
        freshness == Freshness.CACHED -> SyncLabel("CACHED $age", "Not live$shown", freshness)
        freshness == Freshness.LOADING -> SyncLabel("LOADING", "Loading the fleet", freshness)
        freshness == Freshness.LIVE -> SyncLabel("LIVE", "Fleet data is live", freshness)
        else -> SyncLabel("$age AGO", "Not live, data is $age old", freshness)
    }
}

/**
 * How current the fleet is, beside Exit: green live, gray not live (cached or old), red when the
 * server cannot be reached. The dot breathes while any load runs, so background refreshes are
 * visible without the label changing every 30 s. Tap refreshes (or reconnects), like a pull.
 */
@Composable
internal fun SyncChip(
    sync: SyncState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    hostRestart: RestartProgress? = null,
    /** Long press opens the chip into the LIVE panel. */
    onLongPress: (() -> Unit)? = null,
) {
    // One clock per chip; only this composable recomposes each second.
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val label = syncLabel(sync, now, System.currentTimeMillis(), hostRestart)
    // Green live, gray not live, red connection problem. Nothing else uses these meanings here.
    val color = when (label.freshness) {
        Freshness.LIVE -> TechColors.LinkGreen
        Freshness.LOADING, Freshness.AGING, Freshness.STALE, Freshness.CACHED -> TechColors.Mute
        Freshness.FAILING -> TechColors.Danger
    }
    val dotAlpha = if (sync.running) {
        rememberInfiniteTransition(label = "syncDot").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "syncDotAlpha",
        ).value
    } else {
        1f
    }
    val shape = CutCornerShape(bottomEnd = 10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(TechColors.Hull)
            .border(1.dp, TechColors.Edge, shape)
            .combinedClickable(onClick = onRefresh, onLongClick = onLongPress)
            .clearAndSetSemantics {
                contentDescription = label.spoken
                role = Role.Button
                onClick(label = "Refresh") {
                    onRefresh()
                    true
                }
                if (onLongPress != null) {
                    onLongClick(label = "Show sync and requests") {
                        onLongPress()
                        true
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label.text,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = if (label.freshness == Freshness.LIVE) MaterialTheme.colorScheme.onSurface else color,
            )
        }
    }
}
