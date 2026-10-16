package com.kanso.spending.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kanso.spending.data.repository.SmsImportPreview
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.sms.SmsAccessPolicy
import com.kanso.spending.domain.sms.SmsImportMode
import com.kanso.spending.ui.SmartLedgerActions
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.LocalDate
import java.time.ZoneId

/**
 * Date-ranged history import with a **preview-first** flow, shared by the
 * Smart SMS sheet and the first-launch setup screen:
 *
 *  1. pick a range → PREVIEW runs a dry import (writes nothing) and shows
 *     confident / needs-review / duplicate / ignored counts plus parsed
 *     samples (never message bodies);
 *  2. the user then explicitly chooses "record the confident ones now"
 *     ([SmsImportMode.AUTO]) or "review everything first"
 *     ([SmsImportMode.REVIEW]) — one-shot per import, the saved default mode
 *     is not rewritten;
 *
 * It also carries the honesty disclosure: partial SMS history is never used to
 * infer an opening or current balance. [SmsImportMode] aside, nothing here
 * needs SMS access to remain a working app — denial falls back to paste/share
 * and manual tracking.
 */
@OptIn(ExperimentalMaterial3Api::class) // DatePickerDialog / DatePicker are experimental Material3
@Composable
fun HistoryImportSection(
    actions: SmartLedgerActions,
    currencySymbol: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalKansoColors.current
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()

    var fromDate by remember { mutableStateOf(LocalDate.now().minusDays(30)) }
    var toDate by remember { mutableStateOf(LocalDate.now()) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var permissionNote by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<SmsImportPreview?>(null) }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionNote = if (granted) {
            "SMS access granted — run the preview below when ready."
        } else {
            SmsAccessPolicy.denialMessage(
                permanentlyDenied = (context as? android.app.Activity)
                    ?.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS) == false
            )
        }
    }

    fun hasSmsPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

    fun rangeMillis(): Pair<Long, Long> {
        val fromMillis = fromDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return fromMillis to toMillis
    }

    fun requestOrRun(onReady: () -> Unit) {
        if (hasSmsPermission()) onReady() else {
            permissionNote = "SMS access is needed to read history — or paste messages instead."
            smsLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    Column(modifier) {
        Text(
            "IMPORT EXISTING MESSAGES (DATE-RANGED)",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkSecondary
        )
        Spacer(Modifier.height(6.dp))

        // ---- range pickers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showFromPicker = true }) {
                Icon(Icons.Outlined.CalendarToday, contentDescription = null, Modifier.size(14.dp), tint = colors.inkMuted)
                Spacer(Modifier.width(4.dp))
                Text("FROM $fromDate", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { showToPicker = true }) {
                Icon(Icons.Outlined.CalendarToday, contentDescription = null, Modifier.size(14.dp), tint = colors.inkMuted)
                Spacer(Modifier.width(4.dp))
                Text("TO $toDate", style = MaterialTheme.typography.labelSmall)
            }
        }

        // ---- preview (dry run — writes nothing)
        OutlinedButton(
            onClick = {
                val (fromMillis, toMillis) = rangeMillis()
                requestOrRun {
                    actions.onPreviewHistory(context, fromMillis, toMillis) { result ->
                        preview = result
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("PREVIEW MESSAGES IN RANGE (NO CHANGES)", style = MaterialTheme.typography.labelLarge)
        }

        permissionNote?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.fjordAccent)
        }

        // ---- preview card
        val shown = preview
        if (shown != null) {
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colors.elevatedSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "PREVIEW — NOTHING IMPORTED YET",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.positiveAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    if (shown.totalMessages == 0) {
                        Text(
                            "No messages in that date range.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted
                        )
                    } else {
                        Text(
                            "${shown.totalMessages} message(s): " +
                                "${shown.confidentCount} confident · " +
                                "${shown.queueCount} would wait for review · " +
                                "${shown.duplicateCount} duplicate(s) skipped · " +
                                "${shown.ignoredCount} not financial · " +
                                "${shown.balanceNoticeCount} balance notice(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary
                        )

                        if (shown.samples.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            shown.samples.forEach { sample ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${sample.provider}" +
                                            (sample.merchantOrCounterparty.takeIf { it.isNotBlank() }
                                                ?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.inkSecondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        (sample.amountCents?.let {
                                            CurrencyFormatter.formatCents(it, currencySymbol)
                                        } ?: "—") +
                                            if (sample.confidence >= 65) "  ✓" else "  ?",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (sample.confidence >= 65) colors.positiveAccent else colors.warningAccent
                                    )
                                }
                            }
                        }

                        if (shown.duplicateItems.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "DUPLICATES (ALREADY IN YOUR LEDGER — SKIPPED)",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.inkMuted
                            )
                            shown.duplicateItems.forEach { dup ->
                                Text(
                                    "${dup.provider}" +
                                        (dup.amountCents?.let {
                                            " · " + CurrencyFormatter.formatCents(it, currencySymbol)
                                        } ?: "") +
                                        (dup.referenceId?.let { " · ref $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkMuted
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            "✓ = confident, ? = needs your review first. " +
                                "Duplicates are matched by provider reference (or full fingerprint) — never by amount alone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted
                        )
                    }
                }
            }

            // ---- explicit choice: auto-record vs review-first (one-shot)
            if (shown.totalMessages > 0 && (shown.confidentCount + shown.queueCount) > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val (fromMillis, toMillis) = rangeMillis()
                        preview = null
                        actions.onImportHistoryAs(context, fromMillis, toMillis, SmsImportMode.AUTO)
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "IMPORT: RECORD ${shown.confidentCount} CONFIDENT NOW",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        val (fromMillis, toMillis) = rangeMillis()
                        preview = null
                        actions.onImportHistoryAs(context, fromMillis, toMillis, SmsImportMode.REVIEW)
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "IMPORT: REVIEW ${shown.confidentCount + shown.queueCount} FIRST",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // ---- honesty disclosure: partial history never implies balances
        Spacer(Modifier.height(10.dp))
        Text(
            "Older messages may be missing (retention limits, new number, cleared inbox), so this app " +
                "never assumes your current balance, opening balance, or history totals from SMS alone. " +
                "Enter opening balances in Accounts, or reconcile a provider-reported balance there when " +
                "you choose to. Balance-only notices never create transactions.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
        )

        // ---- date pickers
        if (showFromPicker || showToPicker) {
            val initial = if (showFromPicker) fromDate else toDate
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = initial.atStartOfDay(zone).toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { showFromPicker = false; showToPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val picked = LocalDate.ofEpochDay(millis / 86_400_000L)
                            if (showFromPicker) fromDate = picked else toDate = picked
                        }
                        showFromPicker = false; showToPicker = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showFromPicker = false; showToPicker = false }) { Text("Cancel") }
                }
            ) { DatePicker(state = pickerState) }
        }
    }
}
