package com.pdm.app.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdm.app.data.shell.Snippet
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors

/**
 * Snippets above the key row. Tap one to type it; you press Enter. Long-press a saved one to change
 * or delete it. Disabled until the terminal shows a shell prompt, so nothing lands in a login box.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnippetPanel(
    builtins: List<Snippet>,
    saved: List<Snippet>,
    ready: Boolean,
    onInsert: (Snippet) -> Unit,
    onSave: (id: String?, title: String, command: String) -> Boolean,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (!ready) {
            Text(
                "Sign in first; snippets work at the prompt.",
                fontFamily = FontFamily.Monospace,
                color = TechColors.Mute,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            builtins.forEach { s -> SnippetChip(s, ready, onTap = { onInsert(s) }) }
        }
        Spacer(Modifier.height(10.dp))
        Text("YOURS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            saved.forEach { s -> SnippetChip(s, ready, onTap = { onInsert(s) }, onLongPress = { editing = s }) }
            TechActionPlate(label = "+ Save one", onClick = { adding = true })
        }
    }
    if (adding || editing != null) {
        SnippetEditDialog(
            existing = editing,
            onSave = { title, command ->
                if (onSave(editing?.id, title, command)) {
                    adding = false
                    editing = null
                }
            },
            onDelete = editing?.let { e -> { onDelete(e.id); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnippetChip(snippet: Snippet, ready: Boolean, onTap: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val shape = CutCornerShape(bottomEnd = 10.dp)
    Column(
        Modifier
            .widthIn(max = 260.dp)
            .clip(shape)
            .background(TechColors.Hull)
            .border(1.dp, TechColors.Edge, shape)
            .combinedClickable(onClick = { if (ready) onTap() }, onLongClick = onLongPress)
            .alpha(if (ready) 1f else 0.45f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(snippet.title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Text(
            snippet.command,
            fontFamily = FontFamily.Monospace,
            color = TechColors.Mute,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SnippetEditDialog(
    existing: Snippet?,
    onSave: (title: String, command: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var command by remember { mutableStateOf(existing?.command.orEmpty()) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = TechColors.Edge,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        cursorColor = MaterialTheme.colorScheme.primary,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TechColors.Hull,
        title = {
            Text(if (existing == null) "SAVE A SNIPPET" else "EDIT SNIPPET", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                OutlinedTextField(title, { title = it.take(40) }, label = { Text("Name") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    command,
                    { command = it.take(500) },
                    label = { Text("Command") },
                    placeholder = { Text("nvidia-smi", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Saved on this device, encrypted. Tapping it types the command; nothing runs until you press Enter.",
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (onDelete != null) {
                    Spacer(Modifier.height(10.dp))
                    TechActionPlate(label = "Delete", onClick = onDelete)
                }
            }
        },
        confirmButton = {
            TechActionPlate(label = "Save", onClick = { onSave(title, command) }, emphasized = true, enabled = command.isNotBlank())
        },
        dismissButton = { TechActionPlate(label = "Cancel", onClick = onDismiss) },
    )
}
