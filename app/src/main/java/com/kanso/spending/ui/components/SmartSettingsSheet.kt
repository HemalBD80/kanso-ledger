package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kanso.spending.ui.SmartLedgerActions
import com.kanso.spending.ui.SmartLedgerUiState
import com.kanso.spending.ui.theme.LocalKansoColors

/**
 * Settings for the intelligence layer: configurable notifications and the
 * optional biometric app lock — both with honest descriptions of what they do
 * and do not protect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartSettingsSheet(
    smartState: SmartLedgerUiState,
    actions: SmartLedgerActions,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    val settings = smartState.smartSettings

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("INTELLIGENCE SETTINGS", style = MaterialTheme.typography.labelSmall, color = colors.fjordAccent)
            Spacer(Modifier.height(4.dp))
            Text("Privacy & Alerts", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(10.dp))

            smartState.statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fjordAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.elevatedSurface)
                        .padding(10.dp)
                )
                Spacer(Modifier.height(10.dp))
            }

            // ---- notifications
            Text("NOTIFICATIONS", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(6.dp))

            SettingRow(
                title = "New detected transactions",
                description = "Count + review prompt when messages match a transaction. No message text is ever shown in notifications.",
                checked = settings.notifyNewDetections,
                onCheckedChange = actions.onSetNotifyDetected
            )
            SettingRow(
                title = "Budget threshold reached",
                description = "One alert per month when recorded spending reaches your monthly limit.",
                checked = settings.notifyBudgetWarnings,
                onCheckedChange = actions.onSetNotifyBudget
            )
            SettingRow(
                title = "Reconciliation warnings",
                description = "One alert per day when a provider-reported balance differs from your ledger.",
                checked = settings.notifyReconciliation,
                onCheckedChange = actions.onSetNotifyReconcile
            )
            SettingRow(
                title = "Recurring payments due",
                description = "Reminders for confirmed recurring rules coming due — a forecast until you record it.",
                checked = settings.notifyRecurringDue,
                onCheckedChange = actions.onSetNotifyRecurring
            )

            Spacer(Modifier.height(14.dp))

            // ---- app lock
            Text("APP LOCK", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(6.dp))
            SettingRow(
                title = "Require fingerprint / device unlock",
                description = "Hides the app behind your fingerprint, face or screen lock when you leave it. " +
                    "This is a privacy screen — it does NOT encrypt your database or protect files " +
                    "extracted from the device. Turn it off any time; nothing else changes.",
                checked = settings.appLockEnabled,
                onCheckedChange = actions.onSetAppLock
            )

            Spacer(Modifier.height(14.dp))

            // ---- privacy summary
            Text("PRIVACY SUMMARY", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.elevatedSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Column(Modifier.padding(14.dp)) {
                    PrivacyLine("No network permission — nothing can be uploaded.")
                    PrivacyLine("SMS bodies are parsed in memory only: never stored, logged, backed up or notified.")
                    PrivacyLine("Backups contain structured ledger data and SMS fingerprints/metadata only.")
                    PrivacyLine("Notifications carry counts and amounts you recorded — never message content.")
                    PrivacyLine("Categorisation is keyword rules you can see and edit — no cloud ML, no external AI.")
                    PrivacyLine("'Clear SMS history' erases import metadata and the review queue; your recorded transactions stay.")
                    PrivacyLine("Turning Smart Tracking off disables all message reading — manual tracking is unaffected.")
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Known limitations, stated plainly: release APKs are signed with the debug keystore " +
                    "(no Play upload key); the optional biometric lock is not device encryption; declaring " +
                    "READ_SMS in Google Play Console requires a policy declaration and review because this " +
                    "feature is optional and off by default; forecasts and pending detections are labelled " +
                    "estimates and never counted in confirmed totals.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalKansoColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = colors.background,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.positiveAccent)
            )
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    val colors = LocalKansoColors.current
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = colors.inkSecondary,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
