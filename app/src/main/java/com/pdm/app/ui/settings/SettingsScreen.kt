package com.pdm.app.ui.settings

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.BuildConfig
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.session.CertPin
import com.pdm.app.data.settings.WatermarkMode
import com.pdm.app.data.settings.ThemeMode
import com.pdm.app.data.settings.WatermarkTone
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechConfirmDialog
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.components.TechSwitch
import com.pdm.app.ui.components.TechPlateShape
import com.pdm.app.ui.components.TechSectionLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    sessionInfo: String,
    motionStyle: com.pdm.app.data.settings.MotionStyle,
    watermarkStrength: com.pdm.app.data.settings.WatermarkStrength,
    onMotionStyle: (com.pdm.app.data.settings.MotionStyle) -> Unit,
    onWatermarkStrength: (com.pdm.app.data.settings.WatermarkStrength) -> Unit,
    hostLabel: String,
    version: String?,
    demo: Boolean,
    secretSaved: Boolean,
    certPins: List<CertPin>,
    subscriptions: List<RemoteSubscription>,
    onBack: () -> Unit,
    onSwitchAccount: () -> Unit,
    onForgetSecret: () -> Unit,
    onForgetPin: (String) -> Unit,
    onCleanSlate: () -> Unit,
    watermarkMode: WatermarkMode,
    watermarkTone: WatermarkTone,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onWatermarkMode: (WatermarkMode) -> Unit,
    onWatermarkTone: (WatermarkTone) -> Unit,
    onBackgroundEnabled: (Boolean) -> Unit,
    onStrengthEnabled: (Boolean) -> Unit,
    offline: Boolean,
    offlineCache: Boolean,
    cacheStats: com.pdm.app.data.cache.ResponseCache.Stats?,
    onOfflineCache: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    screenLockConfirm: Boolean,
    onScreenLockConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var showSession by remember { mutableStateOf(false) }
    if (showSession) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSession = false },
            containerColor = TechColors.Hull,
            title = { Text("SESSION DETAILS", fontFamily = FontFamily.Monospace) },
            text = { Text(sessionInfo, fontFamily = FontFamily.Monospace) },
            confirmButton = { TechActionPlate(label = "Close", onClick = { showSession = false }) },
        )
    }
    var pinToForget by remember { mutableStateOf<CertPin?>(null) }
    pinToForget?.let { pin ->
        TechConfirmDialog(
            title = "FORGET PIN FOR ${pin.host}?",
            body = "The next connection trusts whatever certificate the host presents and pins it again. " +
                "Only do this after you changed the certificate on purpose.",
            confirmLabel = "Forget pin",
            onConfirm = {
                pinToForget = null
                onForgetPin(pin.host)
            },
            onDismiss = { pinToForget = null },
        )
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "SETTINGS",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = TechColors.Amber,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "SESSION · TRUST · APP",
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TechActionPlate(label = "Back", onClick = onBack)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { TechSectionLabel("Session") }
            item {
                TechPlate(railColor = TechColors.LinkGreen, modifier = Modifier.clickable { showSession = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                when {
                                    demo -> "DEMO \u00b7 DETAILS"
                                    offline -> "OFFLINE \u00b7 DETAILS"
                                    else -> "CONNECTED \u00b7 DETAILS"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                hostLabel,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "PDM ${version ?: "unknown"} · app ${BuildConfig.VERSION_NAME}",
                                fontFamily = FontFamily.Monospace,
                                color = TechColors.Mute,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            item {
                SettingsRow(
                    title = "Switch account",
                    subtitle = "End this session and return to login",
                    onClick = onSwitchAccount,
                )
            }
            item {
                SettingsRow(
                    title = "Forget saved secret",
                    subtitle = if (secretSaved) {
                        "Removes this login's stored credential. Keeps the session, connection, pins and Extras."
                    } else {
                        "No saved secret on this device"
                    },
                    onClick = onForgetSecret,
                    enabled = secretSaved,
                )
            }

            item { TechSectionLabel("Offline cache") }
            item {
                OfflineCachePlate(
                    enabled = offlineCache,
                    demo = demo,
                    stats = cacheStats,
                    secretSaved = secretSaved,
                    onEnabled = onOfflineCache,
                    onClear = onClearCache,
                )
            }

            item { TechSectionLabel("Trust", certPins.size) }
            if (certPins.isEmpty()) {
                item {
                    TechPlate(railColor = TechColors.Mute) {
                        Text(
                            "No pinned fingerprints",
                            fontFamily = FontFamily.Monospace,
                            color = TechColors.Mute,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                items(certPins, key = { it.host }) { pin ->
                    SettingsRow(
                        title = pin.host,
                        subtitle = if (com.pdm.app.data.api.isCaPin(pin.fingerprint)) {
                            "CA key ${shortFingerprint(com.pdm.app.data.api.pinDisplayFingerprint(pin.fingerprint))}"
                        } else {
                            "Certificate ${shortFingerprint(pin.fingerprint)}"
                        },
                        onClick = { pinToForget = pin },
                        uppercaseTitle = false,
                    )
                }
            }

            item { TechSectionLabel("Subscriptions", subscriptions.size) }
            if (subscriptions.isEmpty()) {
                item {
                    TechPlate(railColor = TechColors.Mute) {
                        Text(
                            "No subscription data",
                            fontFamily = FontFamily.Monospace,
                            color = TechColors.Mute,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                items(subscriptions, key = { it.remote }) { sub ->
                    TechPlate(
                        railColor = when (sub.state?.lowercase()) {
                            "active" -> TechColors.LinkGreen
                            "none", null, "" -> TechColors.Mute
                            else -> TechColors.Amber
                        },
                    ) {
                        Text(sub.remote, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(
                            sub.state?.let { com.pdm.app.ui.home.subscriptionLabel(it) } ?: "unknown",
                            fontFamily = FontFamily.Monospace,
                            color = TechColors.Mute,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            item { TechSectionLabel("Extras") }
            item {
                TechPlate(railColor = TechColors.Edge) {
                    Text("THEME", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        "Dark is the night console on OLED black. Light is a daylight datasheet for bright rooms. System follows the device.",
                        fontFamily = FontFamily.Monospace,
                        color = TechColors.Mute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsChoices(
                        listOf(ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light", ThemeMode.SYSTEM to "System"),
                        themeMode,
                        onThemeMode,
                    )
                }
            }
            item {
                TechPlate(railColor = TechColors.Edge) {
                    Text("BACKGROUND", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        "Server name or live app activity behind the screens. Verbose pairs actions with API calls. Paths use placeholders. No headers, query values or response contents are displayed.",
                        fontFamily = FontFamily.Monospace,
                        color = TechColors.Mute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    ExtrasToggle(
                        title = "Background",
                        subtitle = "Off leaves the screens plain.",
                        checked = watermarkMode != WatermarkMode.OFF,
                        onCheckedChange = onBackgroundEnabled,
                    )
                    if (watermarkMode != WatermarkMode.OFF) {
                        Spacer(Modifier.height(8.dp))
                        SettingsChoices(
                            options = listOf(
                                WatermarkMode.FOLLOW to "Follow view",
                                WatermarkMode.PDM to "PDM server",
                                WatermarkMode.ACTIVITY to "Activity",
                                WatermarkMode.API to "API",
                                WatermarkMode.VERBOSE to "Verbose",
                            ),
                            selected = watermarkMode,
                            onSelect = onWatermarkMode,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("TONE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    SettingsChoices(listOf(WatermarkTone.AMBER to "Amber", WatermarkTone.NEUTRAL to "Neutral"),
                        watermarkTone, onWatermarkTone)
                }
            }

            item {
                TechPlate(railColor = TechColors.Edge) {
                    Text("BACKGROUND STRENGTH", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    ExtrasToggle(
                        title = "Strength",
                        subtitle = "Off hides the background without forgetting the level.",
                        checked = watermarkStrength != com.pdm.app.data.settings.WatermarkStrength.OFF,
                        onCheckedChange = onStrengthEnabled,
                    )
                    if (watermarkStrength != com.pdm.app.data.settings.WatermarkStrength.OFF) {
                        Spacer(Modifier.height(8.dp))
                        SettingsChoices(
                            listOf(
                                com.pdm.app.data.settings.WatermarkStrength.SUBTLE to "Subtle",
                                com.pdm.app.data.settings.WatermarkStrength.STANDARD to "Standard",
                                com.pdm.app.data.settings.WatermarkStrength.BOLD to "Bold",
                            ),
                            watermarkStrength,
                            onWatermarkStrength,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("MOTION", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Off keeps basic transitions. Glide lifts the next view; Focus gently settles it into place.",
                        color = TechColors.Mute, style = MaterialTheme.typography.bodySmall)
                    SettingsChoices(com.pdm.app.data.settings.MotionStyle.entries.map { it to it.name.lowercase() },
                        motionStyle, onMotionStyle)
                }
            }

            item { AndroidPlate(screenLockConfirm, onScreenLockConfirm) }

            item { TechSectionLabel("App") }
            item { PermissionsPlate() }
            item { StoragePlate() }

            item {
                Spacer(Modifier.height(8.dp))
                CleanSlatePlate(onPurgeAndExit = onCleanSlate)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * A settings card that is one summary line until tapped. The details stay a tap away instead of
 * filling the screen.
 */
@Composable
private fun ExpandablePlate(title: String, summary: String, details: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    TechPlate(railColor = TechColors.Edge, modifier = Modifier.clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(summary, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (open) "HIDE" else "DETAILS",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (open) details()
    }
}

/** Permissions Android grants on install without asking, versus the one asked when it is first needed. */
private val ASKED_ON_USE = setOf("android.permission.POST_NOTIFICATIONS")

/**
 * What Android actually granted this install, read from the package (so it can never drift from
 * the manifest), each with the reason from [com.pdm.app.data.AppPermissions].
 */
@Composable
private fun PermissionsPlate() {
    val context = LocalContext.current
    val granted = remember(context) { grantedPermissions(context) }
    val atInstall = granted.count { it.first !in ASKED_ON_USE }
    val asked = granted.filter { it.first in ASKED_ON_USE }
    val summary = buildString {
        append("$atInstall granted by Android at install")
        asked.forEach { (name, ok) ->
            append(" · ${com.pdm.app.data.AppPermissions.reasons[name]?.label ?: name.substringAfterLast('.')} ")
            append(if (ok) "allowed" else "not allowed")
        }
    }
    ExpandablePlate("PERMISSIONS", summary) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Android grants ordinary permissions when the app is installed, without a prompt; that is how the platform works. " +
                "Only sensitive ones are asked, when first needed: here just Notifications, when a node shell opens. " +
                "No storage, location, camera or contacts access.",
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.bodySmall,
        )
        granted.forEach { (name, isGranted) ->
            val reason = com.pdm.app.data.AppPermissions.reasons[name]
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (reason?.label ?: name.substringAfterLast('.')).uppercase(),
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    when {
                        name in ASKED_ON_USE && isGranted -> "ALLOWED"
                        name in ASKED_ON_USE -> "NOT ALLOWED"
                        isGranted -> "AT INSTALL"
                        else -> "NOT GRANTED"
                    },
                    fontFamily = FontFamily.Monospace,
                    color = if (isGranted) TechColors.LinkGreen else TechColors.Amber,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(name, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
            Text(
                reason?.why ?: "Declared by a library; no reason recorded.",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun grantedPermissions(context: Context): List<Pair<String, Boolean>> = runCatching {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_PERMISSIONS.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
    }
    val names = info.requestedPermissions.orEmpty()
    val flags = info.requestedPermissionsFlags ?: IntArray(names.size)
    names.mapIndexed { i, name ->
        name to (flags.getOrElse(i) { 0 } and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
    }
}.getOrDefault(emptyList())

/** Where the app keeps things. Everything is app-private; nothing needs a storage permission. */
@Composable
private fun StoragePlate() {
    val context = LocalContext.current
    // Measured, not estimated: everything under the app's private data folder.
    val bytes by produceState<Long?>(null, context) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrNull()
        }
    }
    val summary = "App-private · " + (bytes?.let { com.pdm.app.ui.util.formatBytes(it) + " on this device" } ?: "measuring…") +
        " · never backed up"
    ExpandablePlate("STORAGE", summary) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Every Android app gets a private folder that only it can read; using it needs no permission and Android has no prompt for it. " +
                "The app never touches shared storage (Downloads, photos, SD card), which is what a storage permission is for. " +
                "Data from your PDM is only written for a login you chose to save, with Offline cache on.",
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.bodySmall,
        )
        listOf(
            "SAVED LOGINS, NODE PASSWORDS & PINS" to
                "Encrypted preferences, AES-256 with a key held in the Android Keystore. A PDM password or API token, or a node's root password for Upgrade, only when you ticked save.",
            "OFFLINE CACHE & HISTORY" to
                "Sealed with a Keystore key in no-backup storage. 7 days, 16 MB per login; secret-looking fields are stripped first.",
            "LOOK & FEEL" to
                "Plain preferences: theme, background, motion. Nothing secret.",
            "TERMINAL" to
                "The shell page is bundled with the app and loads nothing from the network. Its output lives in memory and is gone when the shell closes.",
            "BACKUP" to
                "None of it goes to cloud backup or device-to-device transfer. Clean Slate below erases all of it.",
        ).forEach { (title, body) ->
            Spacer(Modifier.height(10.dp))
            Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** What the cache keeps, for how long, when it goes, and what is in it for this login now. */
@Composable
private fun OfflineCachePlate(
    enabled: Boolean,
    demo: Boolean,
    stats: com.pdm.app.data.cache.ResponseCache.Stats?,
    secretSaved: Boolean,
    onEnabled: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    TechPlate(railColor = if (enabled) TechColors.LinkGreen else TechColors.Mute) {
        Text("OFFLINE CACHE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            "An encrypted copy of what this app reads, for logins with a saved credential. It opens the fleet " +
                "instantly at launch and keeps every screen readable when the server cannot be reached. " +
                "Every live read refreshes it. Entries expire after 7 days (16 MB per login, oldest first). " +
                "Forgetting the saved secret, removing the saved connection, switching this off or Clean Slate " +
                "deletes it. Passwords, tokens and SSH keys are never stored.",
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        ExtrasToggle(
            title = "Offline cache",
            subtitle = "Off deletes every login's cached data now.",
            checked = enabled,
            onCheckedChange = onEnabled,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                demo -> "Demo data is never cached."
                !enabled -> "Nothing is cached."
                !secretSaved -> "This login has no saved credential, so nothing is cached for it."
                stats == null || stats.entries == 0 -> "This login: nothing cached yet."
                else -> buildString {
                    append("This login: ${stats.entries} entries · ${formatBytes(stats.bytes)}")
                    stats.newestAt?.let { append(" · newest ${com.pdm.app.ui.util.formatTimestamp(it / 1000)}") }
                    stats.oldestAt?.let { append(" · oldest ${com.pdm.app.ui.util.formatTimestamp(it / 1000)}") }
                }
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        if (enabled && (stats?.entries ?: 0) > 0) {
            Spacer(Modifier.height(8.dp))
            TechActionPlate(label = "Clear cached data", onClick = onClear)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * PDM outside the app: tile, widget, device controls, shortcuts, and the optional screen lock check.
 * Turning the check off asks for the screen lock too, or anyone holding the unlocked tablet could.
 */
@Composable
private fun AndroidPlate(screenLockConfirm: Boolean, onScreenLockConfirm: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lockAvailable = remember { com.pdm.app.ui.util.ScreenLock.available(context) }
    var tileNote by remember { mutableStateOf<String?>(null) }
    TechPlate(railColor = TechColors.Edge) {
        Text("ANDROID", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            "A Quick Settings tile, a home-screen widget, device controls for guests (Android 11+) and long-press " +
                "shortcuts (Refresh, Tasks, Updates, Faults) that Routines can open. They show counts only, and act " +
                "only while PDM is open and live.",
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        ExtrasToggle(
            title = "Screen lock check",
            subtitle = if (lockAvailable || screenLockConfirm) {
                "Fingerprint, face or PIN before Stop, Shutdown, rollback, task stop and PDM restart."
            } else {
                "Needs a screen lock on this device (Android 10+)."
            },
            checked = screenLockConfirm,
            enabled = lockAvailable || screenLockConfirm,
            onCheckedChange = { on ->
                if (on) {
                    onScreenLockConfirm(true)
                } else {
                    com.pdm.app.ui.util.ScreenLock.confirm(
                        context,
                        "Turn off screen lock check",
                        onPass = { onScreenLockConfirm(false) },
                        onRefused = { tileNote = it },
                    )
                }
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(8.dp))
            TechActionPlate("Add Quick Settings tile", onClick = {
                requestTile(context) { tileNote = it }
            })
        }
        tileNote?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Android 13+: the system's own "add this tile?" sheet. */
private fun requestTile(context: Context, onResult: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
    manager.requestAddTileService(
        android.content.ComponentName(context, com.pdm.app.system.FleetTileService::class.java),
        context.getString(com.pdm.app.R.string.tile_label),
        android.graphics.drawable.Icon.createWithResource(context, com.pdm.app.R.drawable.ic_tile_fleet),
        context.mainExecutor,
    ) { result ->
        onResult(
            when (result) {
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added."
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings."
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Not added."
                else -> "Edit Quick Settings to add the PDM fleet tile."
            },
        )
    }
}

@Composable
private fun ExtrasToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TechSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Explicit rows also measure correctly inside TechPlate's intrinsic-height rail layout. */
@Composable
private fun <T> SettingsChoices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    TechActionPlate(label, onClick = { onSelect(value) }, emphasized = selected == value,
                        modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    uppercaseTitle: Boolean = true,
) {
    TechPlate(
        railColor = if (enabled) TechColors.Edge else TechColors.Mute,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            if (uppercaseTitle) title.uppercase() else title,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CleanSlatePlate(onPurgeAndExit: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var isHolding by remember { mutableStateOf(false) }
    val dangerColor = TechColors.Danger

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TechPlateShape)
            .border(1.dp, dangerColor, TechPlateShape)
            .background(TechColors.Hull, TechPlateShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                                .defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        }
                        val animJob = scope.launch {
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 10_000, easing = LinearEasing),
                            )
                            onPurgeAndExit()
                        }
                        // Continuous ten-second amplitude ramp with no silent segments. Without amplitude
                        // control, use pulses that lengthen second by second instead of a flat buzz.
                        runCatching {
                            vibrator.vibrate(
                                if (vibrator.hasAmplitudeControl()) {
                                    VibrationEffect.createWaveform(
                                        LongArray(10) { 1000L },
                                        IntArray(10) { index -> 60 + (195 * index) / 9 },
                                        -1,
                                    )
                                } else {
                                    VibrationEffect.createWaveform(com.pdm.app.ui.util.Haptics.holdRampTimings(), -1)
                                },
                            )
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            isHolding = false
                            animJob.cancel()
                            vibrator.cancel()
                            scope.launch { progress.snapTo(0f) }
                        }
                    },
                )
            }
            .drawWithContent {
                if (progress.value > 0f) {
                    val fillWidth = size.width * progress.value
                    drawRect(
                        color = dangerColor.copy(alpha = 0.22f),
                        size = size.copy(width = fillWidth),
                    )
                    drawLine(
                        color = dangerColor,
                        start = Offset(fillWidth, 0f),
                        end = Offset(fillWidth, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                drawContent()
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(dangerColor),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "CLEAN SLATE",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = dangerColor,
                    )
                    if (isHolding && progress.value > 0f) {
                        val secondsLeft = ((1f - progress.value) * 10).toInt() + 1
                        Text(
                            "HOLD ${secondsLeft}S",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = dangerColor,
                        )
                    } else {
                        Text(
                            "HOLD 10S",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = dangerColor.copy(alpha = 0.7f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Erases all local app data: saved connections, credentials, pins, Extras, files and caches. The app closes and returns to a first-run state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun shortFingerprint(fp: String): String {
    val trimmed = fp.trim()
    if (trimmed.length <= 17) return trimmed
    return trimmed.take(8) + "…" + trimmed.takeLast(8)
}
