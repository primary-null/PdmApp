package com.pdm.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import com.pdm.app.ui.adaptive.topChromePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdm.app.data.model.AuthMode
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.LoginFormState
import com.pdm.app.ui.NetworkScanState
import com.pdm.app.ui.adaptive.isOperatorTwoPane
import com.pdm.app.ui.components.Protocol
import com.pdm.app.ui.components.ProtocolFamily
import com.pdm.app.ui.components.ProtocolTag
import com.pdm.app.ui.components.Protocols
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechPlate
import com.pdm.app.ui.components.TechSwitch

@Composable
fun LoginScreen(vm: FleetViewModel) {
    val form by vm.login.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    form.pendingCert?.let { question ->
        CertificateDialog(question, onAccept = vm::acceptCertificate, onReject = vm::rejectCertificate)
    }
    val twoPane = isOperatorTwoPane(LocalConfiguration.current.screenWidthDp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = TechColors.Edge,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        cursorColor = MaterialTheme.colorScheme.primary,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .topChromePadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 8.dp),
        ) {
            Column(
                modifier = Modifier.clickable { vm.tapDemoUnlock() },
            ) {
                Text(
                    "PDM",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    letterSpacing = 4.sp,
                )
                Text(
                    "DATACENTER MANAGER",
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Amber,
                    letterSpacing = 2.sp,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (!twoPane) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            ) {
                ConnectionPane(form, scan, vm, fieldColors)
                AuthPane(form, vm, fieldColors)
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 20.dp),
                ) {
                    ConnectionPane(form, scan, vm, fieldColors)
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(TechColors.Edge),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp),
                ) {
                    AuthPane(form, vm, fieldColors)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ConnectionPane(
    form: LoginFormState,
    scan: NetworkScanState,
    vm: FleetViewModel,
    fieldColors: androidx.compose.material3.TextFieldColors,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        "CONNECTION",
        fontFamily = FontFamily.Monospace,
        color = TechColors.Amber,
        letterSpacing = 1.4.sp,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(12.dp))
    if (form.connections.isNotEmpty()) {
        Text("SAVED CONNECTIONS", fontFamily = FontFamily.Monospace, color = TechColors.Mute)
        TechActionPlate(label = "New connection", onClick = vm::newConnection, enabled = !form.loading)
        Spacer(Modifier.height(8.dp))
        form.connections.forEach { connection ->
            TechPlate(railColor = TechColors.Edge) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolTag(Protocols.HTTPS)
                    Spacer(Modifier.width(8.dp))
                    Text("${connection.host}:${connection.port}", fontFamily = FontFamily.Monospace)
                }
                Text("${connection.username} \u00b7 ${connection.authMode.name.lowercase().replace('_', ' ')} \u00b7 ${if (connection.secretSaved) "secret saved" else "no saved secret"}",
                    color = TechColors.Mute, style = MaterialTheme.typography.bodySmall)
                connection.cachedAt?.let { at ->
                    Text(
                        "offline data from ${com.pdm.app.ui.util.formatTimestamp(at / 1000)}",
                        color = TechColors.Mute,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TechActionPlate(label = "Use", onClick = { vm.selectConnection(connection.id) }, enabled = !form.loading)
                    if (connection.cachedAt != null) {
                        // Read the cached fleet now; the app goes live by itself once the server answers.
                        TechActionPlate(label = "Offline", onClick = { vm.openOffline(connection.id) }, enabled = !form.loading)
                    }
                    TechActionPlate(label = "Remove", onClick = { vm.removeConnection(connection.id) }, enabled = !form.loading)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    OutlinedTextField(
        form.host,
        { vm.updateLogin { s -> s.copy(host = it) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("HOST / IP") },
        singleLine = true,
        enabled = !form.loading,
        colors = fieldColors,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        form.port,
        { vm.updateLogin { s -> s.copy(port = it.filter { ch -> ch.isDigit() }.take(5)) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("PORT") },
        singleLine = true,
        enabled = !form.loading,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = fieldColors,
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (form.demoUnlocked) {
            TechActionPlate(
                label = "Demo",
                onClick = { if (!form.loading) vm.demo() },
            )
        }
        TechActionPlate(
            label = if (scan.isScanning) "Stop scan" else "Scan network",
            onClick = {
                if (form.loading) return@TechActionPlate
                if (scan.isScanning) vm.stopNetworkScan() else vm.startNetworkScan()
            },
        )
    }
    ScanResults(scan, vm)
}

@Composable
private fun ColumnScope.AuthPane(
    form: LoginFormState,
    vm: FleetViewModel,
    fieldColors: androidx.compose.material3.TextFieldColors,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        "AUTHENTICATION",
        fontFamily = FontFamily.Monospace,
        color = TechColors.Amber,
        letterSpacing = 1.4.sp,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TechActionPlate(
            label = "Password",
            emphasized = form.authMode == AuthMode.PASSWORD,
            enabled = !form.loading,
            onClick = { vm.updateLogin { s -> s.copy(authMode = AuthMode.PASSWORD) } },
            modifier = Modifier.weight(1f),
        )
        TechActionPlate(
            label = "API token",
            emphasized = form.authMode == AuthMode.API_TOKEN,
            enabled = !form.loading,
            onClick = { vm.updateLogin { s -> s.copy(authMode = AuthMode.API_TOKEN) } },
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(12.dp))
    if (form.tfaRequired) {
        SecondFactorStep(form, vm, fieldColors)
        Spacer(Modifier.height(12.dp))
    } else {
        when (form.authMode) {
            AuthMode.PASSWORD -> {
                OutlinedTextField(
                    form.username,
                    { vm.updateLogin { s -> s.copy(username = it) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("USERNAME") },
                    singleLine = true,
                    enabled = !form.loading,
                    colors = fieldColors,
                )
                Spacer(Modifier.height(12.dp))
                RealmField(form, vm, fieldColors)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    form.password,
                    { vm.updateLogin { s -> s.copy(password = it, secretPrefillMasked = false) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (form.secretPrefillMasked) "PASSWORD (SAVED — LEAVE BLANK TO REUSE)"
                            else "PASSWORD",
                        )
                    },
                    singleLine = true,
                    enabled = !form.loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!form.loading) vm.connect() }),
                    colors = fieldColors,
                )
            }
            AuthMode.API_TOKEN -> {
                OutlinedTextField(
                    form.apiToken,
                    { vm.updateLogin { s -> s.copy(apiToken = it, secretPrefillMasked = false) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (form.secretPrefillMasked) "API TOKEN (SAVED — LEAVE BLANK TO REUSE)"
                            else "API TOKEN",
                        )
                    },
                    placeholder = { Text("user@pdm!tokenid:uuid") },
                    singleLine = true,
                    enabled = !form.loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!form.loading) vm.connect() }),
                    colors = fieldColors,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingRow(
        title = "Save credentials",
        subtitle = "Encrypt password/token on this device for this host and user only.",
        checked = form.remember,
        enabled = !form.loading,
        onCheckedChange = { checked -> vm.setRemember(checked) },
    )
    SettingRow(
        title = "Auto-connect on launch",
        subtitle = "Skip login when a saved secret exists for this host and user.",
        checked = form.autoConnect,
        enabled = !form.loading && form.remember,
        onCheckedChange = { checked -> vm.setAutoConnect(checked) },
    )
    Spacer(Modifier.height(12.dp))
    form.jumpBackLabel?.let { label ->
        TechActionPlate(
            label = "Jump back",
            onClick = { if (!form.loading) vm.jumpBack() },
            emphasized = true,
            enabled = !form.loading,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            color = TechColors.Amber,
            style = MaterialTheme.typography.labelSmall,
        )
        form.jumpBackNote?.let { note ->
            Text(
                note,
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TechActionPlate(
            label = if (form.loading) "Connecting" else if (form.tfaRequired) "Verify" else "Connect",
            onClick = { if (!form.loading) vm.connect() },
            emphasized = true,
            icon = Icons.AutoMirrored.Filled.Login,
        )
        if (form.tfaRequired && !form.loading) {
            TechActionPlate(label = "Cancel", onClick = vm::cancelTfa)
        }
    }
    if (!form.error.isNullOrBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            form.error.orEmpty(),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Free-text realm with a picker filled from the server's public realm list. */
@Composable
private fun RealmField(
    form: LoginFormState,
    vm: FleetViewModel,
    fieldColors: androidx.compose.material3.TextFieldColors,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            form.realm,
            { vm.updateLogin { s -> s.copy(realm = it.trim()) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("REALM") },
            placeholder = { Text("pam") },
            singleLine = true,
            enabled = !form.loading,
            colors = fieldColors,
            trailingIcon = {
                IconButton(
                    onClick = {
                        open = true
                        vm.loadRealms()
                    },
                    enabled = !form.loading && form.host.isNotBlank(),
                ) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Pick realm")
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = TechColors.Hull) {
            val realms = form.realms
            when {
                form.realmsError != null -> DropdownMenuItem(
                    text = { Text("Realms did not load: ${form.realmsError}", fontFamily = FontFamily.Monospace) },
                    onClick = { open = false },
                )
                realms == null -> DropdownMenuItem(
                    text = { Text("reading realms…", fontFamily = FontFamily.Monospace) },
                    onClick = {},
                )
                else -> realms.forEach { r ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(r.realm, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                r.comment?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, fontFamily = FontFamily.Monospace, color = TechColors.Mute, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                        onClick = {
                            open = false
                            vm.updateLogin { s -> s.copy(realm = r.realm) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                subtitle,
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TechSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * Asked before any password or token is sent: the server presented a certificate this device
 * cannot verify (PDM's own self-signed one, typically), or a pinned authority changed.
 * Accepting pins it for this host and port; a later different certificate is refused outright.
 */
@Composable
private fun CertificateDialog(
    question: com.pdm.app.ui.CertQuestion,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onReject)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onReject,
        containerColor = TechColors.Hull,
        title = {
            Text(
                if (question.authorityChange) "TRUST NEW CERTIFICATE AUTHORITY?" else "TRUST THIS CERTIFICATE?",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (question.authorityChange) {
                        "${question.host} now has a certificate from a different authority than before. " +
                            "The new authority's SHA-256 key fingerprint:"
                    } else {
                        "${question.host} uses a certificate this device can't verify, such as PDM's " +
                            "default self-signed one. Compare its SHA-256 fingerprint with the one on the " +
                            "server (PDM web UI > Certificates) before trusting it:"
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProtocolTag(Protocol("TLS", ProtocolFamily.SECURE))
                    ProtocolTag(Protocol(if (question.authorityChange) "CA KEY" else "CERTIFICATE", ProtocolFamily.QUIET))
                }
                Text(
                    com.pdm.app.data.api.pinDisplayFingerprint(question.fingerprint),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                    color = TechColors.Amber,
                )
                if (question.authorityChange || question.knownServer) {
                    Text(
                        if (question.authorityChange) {
                            "Accept only if you changed this server's certificate yourself; otherwise the " +
                                "connection may be intercepted."
                        } else {
                            "This device has signed in here before. Accept only if you changed the server's " +
                                "certificate or forgot its pin on purpose; otherwise the connection may be intercepted."
                        },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = TechColors.Amber,
                    )
                }
                Text(
                    "Nothing has been sent yet. Trusting pins it for this host and port; a different " +
                        "certificate later is refused.",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = TechColors.Mute,
                )
            }
        },
        confirmButton = { TechActionPlate(label = "Trust", onClick = onAccept, emphasized = true) },
        dismissButton = { TechActionPlate(label = "Don't trust", onClick = onReject) },
    )
}

/**
 * The second factor PDM asked for. Offers what the account actually has (read from the challenge):
 * a TOTP code, a recovery key, or both. Security keys (WebAuthn/U2F) are bound to the PDM web
 * address and cannot be used by an Android app, so an account with only those is told so plainly.
 */
@Composable
private fun SecondFactorStep(
    form: LoginFormState,
    vm: FleetViewModel,
    fieldColors: androidx.compose.material3.TextFieldColors,
) {
    val methods = form.tfaMethods ?: com.pdm.app.data.api.TfaMethods.UNKNOWN
    val kinds = methods.usable.ifEmpty { listOf(com.pdm.app.data.api.TfaKind.TOTP, com.pdm.app.data.api.TfaKind.RECOVERY) }
    Text("SECOND FACTOR", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    if (methods.onlyHardwareKeys) {
        Text(
            "This account signs in with a security key (WebAuthn). Those are tied to the PDM web address, so an " +
                "Android app cannot use them. Add a TOTP authenticator in PDM (Access Control > Two Factor), or use " +
                "a recovery key if you have one.",
            fontFamily = FontFamily.Monospace,
            color = TechColors.Amber,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
    }
    if (kinds.size > 1) {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { kind ->
                TechActionPlate(
                    label = if (kind == com.pdm.app.data.api.TfaKind.TOTP) "Authenticator code" else "Recovery key",
                    onClick = { vm.updateLogin { s -> s.copy(tfaKind = kind, otp = "") } },
                    emphasized = form.tfaKind == kind,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    val recovery = form.tfaKind == com.pdm.app.data.api.TfaKind.RECOVERY
    OutlinedTextField(
        form.otp,
        { typed ->
            vm.updateLogin { s ->
                s.copy(
                    otp = if (recovery) {
                        typed.filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.take(24)
                    } else {
                        typed.filter { ch -> ch.isDigit() }.take(8)
                    },
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (recovery) "RECOVERY KEY (xxxx-xxxx-xxxx-xxxx)" else "6-DIGIT CODE") },
        singleLine = true,
        enabled = !form.loading,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (recovery) KeyboardType.Ascii else KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { if (!form.loading) vm.connect() }),
        colors = fieldColors,
    )
    Text(
        when {
            recovery && methods.recoveryLeft != null ->
                "Each key works once. ${methods.recoveryLeft} left" +
                    (if (methods.recoveryLeft <= 2) " — make new ones in PDM soon." else ".")
            recovery -> "Each key works once. Case and dashes do not matter here."
            else -> "From your authenticator app. Codes change every 30 seconds."
        },
        fontFamily = FontFamily.Monospace,
        color = if (recovery && (methods.recoveryLeft ?: 3) <= 2) TechColors.Amber else TechColors.Mute,
        style = MaterialTheme.typography.labelSmall,
    )
}

