package com.kanso.spending.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.SmsDetectionEntity
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.KeypadExpressionEvaluator
import com.kanso.spending.domain.sms.LedgerAction
import com.kanso.spending.domain.sms.SmsImportMode
import com.kanso.spending.ui.SmartLedgerActions
import com.kanso.spending.ui.SmartLedgerUiState
import com.kanso.spending.ui.SpendingDashboardUiState
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Smart SMS Tracking: opt-in activation with an honest rationale, import mode,
 * date-filtered history import, paste alternative, and the review queue where
 * every detection is confirmed, edited or rejected — nothing is ever guessed,
 * and unreviewed detections never touch confirmed totals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartSmsSheet(
    uiState: SpendingDashboardUiState,
    smartState: SmartLedgerUiState,
    categories: List<CategoryEntity>,
    actions: SmartLedgerActions,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    val context = LocalContext.current

    var permissionNote by remember { mutableStateOf<String?>(null) }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionNote = if (granted) {
            "SMS access granted. Messages are read on this device only."
        } else {
            "SMS access denied — you can still paste messages or share them into the app."
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    var fromDate by remember { mutableStateOf(LocalDate.now().minusDays(7)) }
    var toDate by remember { mutableStateOf(LocalDate.now()) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    val zone = ZoneId.systemDefault()

    fun hasSmsPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("SMART TRACKING · ON-DEVICE ONLY", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
            Spacer(Modifier.height(4.dp))
            Text("Smart SMS Tracking", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.elevatedSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Read incoming messages", style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                        Text(
                            "Why: only transaction messages (bKash, Nagad, Rocket, banks, cards) are parsed — " +
                                "on this device, never uploaded. Message bodies are never stored, logged or " +
                                "backed up; only fingerprints and extracted amounts are kept. Turning this off " +
                                "leaves manual tracking untouched.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = smartState.smartSettings.smsEnabled,
                        onCheckedChange = { enabled ->
                            actions.onSetSmsEnabled(enabled)
                            if (enabled) {
                                smsLauncher.launch(Manifest.permission.READ_SMS)
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.positiveAccent)
                    )
                }
            }

            permissionNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.fjordAccent)
            }

            smartState.statusMessage?.let { message ->
                Spacer(Modifier.height(6.dp))
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
            }

            // ---- import mode
            Spacer(Modifier.height(14.dp))
            Text("IMPORT MODE", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(
                    label = "REVIEW BEFORE SAVE",
                    active = smartState.smartSettings.smsImportMode == SmsImportMode.REVIEW,
                    colors = colors,
                    onClick = { actions.onSetImportMode(SmsImportMode.REVIEW) },
                    modifier = Modifier.weight(1f)
                )
                ModeChip(
                    label = "AUTO (CONFIDENT ONLY)",
                    active = smartState.smartSettings.smsImportMode == SmsImportMode.AUTO,
                    colors = colors,
                    onClick = { actions.onSetImportMode(SmsImportMode.AUTO) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (smartState.smartSettings.smsImportMode == SmsImportMode.REVIEW) {
                    "Every recognised transaction waits in the review queue below before it counts."
                } else {
                    "Confident transactions record immediately; ambiguous messages still always wait — the app never guesses."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted
            )

            // ---- history import with date range
            Spacer(Modifier.height(14.dp))
            Text("IMPORT EXISTING MESSAGES (DATE-RANGED)", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(6.dp))
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
            OutlinedButton(
                onClick = {
                    val fromMillis = fromDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val toMillis = toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    if (hasSmsPermission()) {
                        actions.onImportHistory(context, fromMillis, toMillis)
                    } else {
                        permissionNote = "SMS access is needed for history import — or paste messages below instead."
                        smsLauncher.launch(Manifest.permission.READ_SMS)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("IMPORT MESSAGES IN RANGE", style = MaterialTheme.typography.labelLarge)
            }
            // ---- paste alternative
            Spacer(Modifier.height(10.dp))
            Text("PASTE OR SHARE A MESSAGE (NO PERMISSION NEEDED)", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("Paste one message here") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    actions.onPasteImport(pasteText)
                    pasteText = ""
                },
                enabled = pasteText.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("IMPORT PASTED TEXT") }

            // ---- review queue
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("REVIEW QUEUE", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${smartState.pendingDetections.size} PENDING",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (smartState.pendingDetections.isEmpty()) colors.positiveAccent else colors.warningAccent,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            if (smartState.pendingDetections.isEmpty()) {
                Text(
                    "Nothing waiting. Confirmed transactions stay outside this queue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted
                )
            }
            smartState.pendingDetections.forEach { detection ->
                DetectionCard(
                    detection = detection,
                    smartState = smartState,
                    categories = categories,
                    currencySymbol = uiState.currencySymbol,
                    actions = actions
                )
                Spacer(Modifier.height(8.dp))
            }

            // ---- recent outcomes
            val decided = smartState.recentDetections.filter { it.reviewStatus != "PENDING" }
            if (decided.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("RECENTLY DECIDED", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                Spacer(Modifier.height(4.dp))
                decided.take(8).forEach { d ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${d.merchant.ifBlank { d.provider }} · " +
                                (d.amountCents?.let { CurrencyFormatter.formatCents(it, uiState.currencySymbol) }
                                    ?: CurrencyFormatter.formatCents(d.feeCents, uiState.currencySymbol)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            d.reviewStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (d.reviewStatus == "APPROVED") colors.positiveAccent else colors.alertAccent
                        )
                    }
                }
            }

            // ---- utilities
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actions.onUndoLastImport() },
                    enabled = smartState.lastBatchId != null || smartState.pendingDetections.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("UNDO LAST IMPORT", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(
                    onClick = { actions.onClearSmsHistory() },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("CLEAR SMS HISTORY", style = MaterialTheme.typography.labelSmall) }
            }
            smartState.lastIngestSummary?.let { summary ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last import: ${summary.totalMessages} read · ${summary.recorded} recorded · " +
                        "${summary.queuedForReview} to review · ${summary.duplicates} duplicates · " +
                        "${summary.ignored} not financial.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Deduplication: each message has a fingerprint (reference id when present, otherwise " +
                    "sender + amount + type + time). The same message never records twice — but identical " +
                    "amounts alone are never treated as the same transaction. Play Store policy: apps using " +
                    "READ_SMS must declare it in Play Console; this feature is optional and off by default.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }

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

@Composable
private fun ModeChip(
    label: String,
    active: Boolean,
    colors: com.kanso.spending.ui.theme.KansoExtendedColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.inkPrimary else colors.elevatedSurface)
            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) colors.surface else colors.inkSecondary
        )
    }
}

/** One pending detection with inline editing before confirmation. */
@Composable
private fun DetectionCard(
    detection: SmsDetectionEntity,
    smartState: SmartLedgerUiState,
    categories: List<CategoryEntity>,
    currencySymbol: String,
    actions: SmartLedgerActions
) {
    val colors = LocalKansoColors.current
    var expanded by remember(detection.id) { mutableStateOf(false) }
    var amountText by remember(detection.id) {
        mutableStateOf(
            detection.amountCents?.let { CurrencyFormatter.formatCentsPlain(it) } ?: ""
        )
    }
    var categoryId by remember(detection.id) { mutableStateOf(detection.categoryId) }
    var accountId by remember(detection.id) { mutableStateOf(detection.accountId) }
    var fromId by remember(detection.id) { mutableStateOf(detection.accountId) }
    var toId by remember(detection.id) { mutableStateOf("") }
    var actionOverride by remember(detection.id) { mutableStateOf<LedgerAction?>(null) }
    var saveRule by remember(detection.id) { mutableStateOf(false) }
    var validationNote by remember(detection.id) { mutableStateOf<String?>(null) }

    val action = actionOverride ?: runCatching { LedgerAction.valueOf(detection.action) }.getOrNull()
    val isBalanceOnly = action == LedgerAction.IGNORE
    val isUnclear = action == LedgerAction.REVIEW_ONLY
    val isTransfer = action == LedgerAction.RECORD_TRANSFER

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.background,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (detection.confidence >= 65) colors.hairlineBorder else colors.warningAccent
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        detection.txType.replace('_', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted
                    )
                    Text(
                        detection.merchant.ifBlank { detection.provider },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary
                    )
                    Text(
                        buildString {
                            append(detection.provider)
                            if (detection.refId.isNotBlank()) append(" · Ref ${detection.refId}")
                            if (detection.accountHint.isNotBlank()) append(" · ${detection.accountHint}")
                            append(" · confidence ${detection.confidence}%")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val headline = detection.amountCents?.let {
                        CurrencyFormatter.formatCents(it, currencySymbol)
                    } ?: if (detection.feeCents > 0) {
                        CurrencyFormatter.formatCents(detection.feeCents, currencySymbol)
                    } else {
                        detection.reportedBalanceCents?.let {
                            CurrencyFormatter.formatCents(it, currencySymbol)
                        } ?: "—"
                    }
                    Text(headline, style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) "CLOSE" else "REVIEW",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.fjordAccent
                        )
                    }
                }
            }

            if (detection.reviewReason.isNotBlank()) {
                Text(
                    "Review reason: ${detection.reviewReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warningAccent
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))

                if (isBalanceOnly) {
                    Text(
                        "Provider reports " +
                            (detection.reportedBalanceCents?.let { CurrencyFormatter.formatCents(it, currencySymbol) }
                                ?: "a balance") +
                            ". Choose the account it belongs to — your ledger balance is compared, never replaced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (isUnclear) {
                    Text("No clear type detected — choose what this is:", style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(LedgerAction.RECORD_EXPENSE to "SPENT", LedgerAction.RECORD_INCOME to "RECEIVED").forEach { (a, label) ->
                            val active = actionOverride == a
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                                    .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable { actionOverride = a }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) colors.surface else colors.inkSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (!isBalanceOnly && (detection.amountCents != null || isUnclear)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (edit if wrong)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                }

                if (!isBalanceOnly && action != LedgerAction.RECORD_TRANSFER) {
                    Text("CATEGORY", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.take(6).forEach { cat ->
                            val active = categoryId == cat.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                                    .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable { categoryId = cat.id }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    cat.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) colors.surface else colors.inkSecondary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (!saveRule) {
                        TextButton(onClick = { saveRule = true }) {
                            Text("□ SAVE AS RULE FOR THIS MERCHANT", style = MaterialTheme.typography.labelSmall, color = colors.fjordAccent)
                        }
                    } else {
                        TextButton(onClick = { saveRule = false }) {
                            Text("☑ SAVE AS RULE — future matches suggest this category", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
                        }
                    }
                }

                if (isBalanceOnly || accountId.isBlank() || isTransfer) {
                    Text("ACCOUNT", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        smartState.accountStates.filter { !it.isArchived }.forEach { s ->
                            val active = accountId == s.accountId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                                    .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable { accountId = s.accountId; if (fromId.isBlank()) fromId = s.accountId }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(s.name, style = MaterialTheme.typography.labelSmall, color = if (active) colors.surface else colors.inkSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (isTransfer) {
                    Text("TRANSFER: FROM → TO (two different accounts)", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    Spacer(Modifier.height(4.dp))
                    AccountPickerRow(smartState, fromId) { fromId = it }
                    AccountPickerRow(smartState, toId) { toId = it }
                    Spacer(Modifier.height(4.dp))
                }

                validationNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warningAccent,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val amountCents = amountText.trim().takeIf { it.isNotEmpty() }?.let {
                                KeypadExpressionEvaluator.evaluateToCents(it)
                            }
                            val needsAccount = isBalanceOnly && accountId.isBlank()
                            val transferIncomplete = isTransfer && (fromId.isBlank() || toId.isBlank() || fromId == toId)
                            val unclearWithoutChoice = isUnclear && actionOverride == null
                            validationNote = when {
                                needsAccount -> "Choose which account this balance belongs to."
                                transferIncomplete -> "Pick two different accounts for this transfer."
                                unclearWithoutChoice -> "Choose what this transaction is (spent or received)."
                                else -> null
                            }
                            if (validationNote == null) {
                                actions.onApproveDetection(
                                    detection.id,
                                    com.kanso.spending.data.repository.DetectionApproval(
                                        categoryId = categoryId,
                                        accountId = accountId,
                                        amountCents = amountCents,
                                        action = actionOverride,
                                        transferFromAccountId = fromId.takeIf { isTransfer && it.isNotBlank() },
                                        transferToAccountId = toId.takeIf { isTransfer && it.isNotBlank() },
                                        saveAsRuleKeyword = if (saveRule) {
                                            detection.merchant.ifBlank { detection.provider }
                                        } else null
                                    )
                                )
                            }
                        },
                        modifier = Modifier.weight(1.4f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.positiveAccent, contentColor = colors.surface)
                    ) { Text("CONFIRM", style = MaterialTheme.typography.labelLarge) }
                    OutlinedButton(
                        onClick = { actions.onRejectDetection(detection.id) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("REJECT", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
private fun AccountPickerRow(
    smartState: SmartLedgerUiState,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    val colors = LocalKansoColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        smartState.accountStates.filter { !it.isArchived }.forEach { s ->
            val active = selectedId == s.accountId
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                    .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                    .clickable { onSelect(s.accountId) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) { Text(s.name, style = MaterialTheme.typography.labelSmall, color = if (active) colors.surface else colors.inkSecondary) }
        }
    }
}
