package com.pdm.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pdm.app.data.shell.ShellTarget
import com.pdm.app.ui.PendingShell
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechSwitch

/**
 * Confirm an upgrade shell. For a node it also takes the node's root password: through PDM the
 * node always asks for its own login, and the app answers it so Upgrade lands on a root prompt.
 */
@Composable
fun UpgradeShellDialog(
    pending: PendingShell,
    onConfirm: (password: String, remember: Boolean) -> Unit,
    onForgetSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val target = pending.target
    // A plain terminal on the PDM machine: no upgrade is staged, so none of the upgrade wording applies.
    val plain = target is ShellTarget.Host && !target.upgrade && pending.staged == null
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(pending.savedPassword) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = TechColors.Edge,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        cursorColor = MaterialTheme.colorScheme.primary,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TechColors.Hull,
        title = { Text(if (plain) "OPEN TERMINAL ON ${target.title}?" else "UPGRADE ${target.title}?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    when {
                        plain ->
                            "Opens a terminal on ${target.title}. Anything typed there runs on ${target.title} itself."
                        target is ShellTarget.Host && target.upgrade ->
                            "Opens a terminal on ${target.title} and starts the upgrade straight away. " +
                                "You'll be asked to confirm before anything is installed."
                        pending.nodeLogin ->
                            "Opens a terminal on ${target.title}, signs you in, and gets the upgrade ready. " +
                                "Press Enter to start it, then answer any questions it asks."
                        else ->
                            "Opens a terminal on ${target.title} with the upgrade ready. Press Enter to start it."
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (pending.nodeLogin) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        password,
                        { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(if (saved) "Root password (saved)" else "Root password for ${target.title}")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                        colors = fieldColors,
                    )
                    if (password.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Remember for ${target.title}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text(
                                    "Stored encrypted on this device. Clean Slate removes it.",
                                    fontFamily = FontFamily.Monospace,
                                    color = TechColors.Mute,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            TechSwitch(checked = remember, onCheckedChange = { remember = it })
                        }
                    } else if (saved) {
                        Spacer(Modifier.height(10.dp))
                        TechActionPlate(label = "Forget saved password", onClick = {
                            onForgetSaved()
                            saved = false
                        })
                    }
                }
                if (!plain) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Don't close the terminal until the upgrade finishes. Closing it part way can leave ${target.title} half upgraded.",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TechActionPlate(label = "Open terminal", onClick = { onConfirm(password, remember) }, emphasized = true)
        },
        dismissButton = { TechActionPlate(label = "Cancel", onClick = onDismiss) },
    )
}
