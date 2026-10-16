package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kanso.spending.ui.theme.LocalKansoColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalVaultSheet(
    backupJsonPreview: String,
    statusMessage: String?,
    onGenerateBackupJson: () -> Unit,
    onImportBackupJson: (String) -> Unit,
    onWipeAllLocalData: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    val clipboard = LocalClipboardManager.current
    var importText by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onGenerateBackupJson() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("ZERO-CLOUD PRIVACY GUARANTEE", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
            Spacer(Modifier.height(4.dp))
            Text("On-Device SQLite Vault", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Kanso requests zero internet permissions. Every transaction and budget you enter lives exclusively in this phone's local SQLite vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Export a JSON snapshot any time to keep an off-device copy.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = colors.inkMuted
            )

            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.positiveAccent.copy(alpha = 0.14f))
                        .border(1.dp, colors.positiveAccent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(statusMessage, style = MaterialTheme.typography.labelLarge, color = colors.positiveAccent)
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    onGenerateBackupJson()
                    if (backupJsonPreview.isNotBlank()) clipboard.setText(AnnotatedString(backupJsonPreview))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("COPY JSON BACKUP", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text("Paste Local JSON Snapshot to Restore") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (importText.isNotBlank()) onImportBackupJson(importText) },
                enabled = importText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.inkPrimary, contentColor = colors.surface)
            ) {
                Text("RESTORE SNAPSHOT INTO LOCAL DB", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(14.dp))
            if (!confirmWipe) {
                OutlinedButton(
                    onClick = { confirmWipe = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.alertAccent)
                ) {
                    Text("WIPE ALL LOCAL DATA", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Button(
                    onClick = {
                        onWipeAllLocalData()
                        confirmWipe = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.alertAccent, contentColor = colors.surface)
                ) {
                    Text("CONFIRM PERMANENT LOCAL WIPE", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
