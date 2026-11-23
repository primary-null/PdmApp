package com.pdm.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** The one confirm surface for anything that changes a remote or local trust: plate buttons on the hull. */
@Composable
fun TechConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TechColors.Hull,
        title = { Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { TechActionPlate(label = confirmLabel, onClick = onConfirm, emphasized = true) },
        dismissButton = { TechActionPlate(label = "Cancel", onClick = onDismiss) },
    )
}
