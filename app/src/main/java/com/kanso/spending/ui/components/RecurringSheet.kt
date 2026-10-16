package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.KeypadExpressionEvaluator
import com.kanso.spending.ui.SmartLedgerActions
import com.kanso.spending.ui.SmartLedgerUiState
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Recurring payments: confirmed rules with their forecasts (always labelled as
 * estimates), user-confirmed pattern suggestions, and marking a payment as
 * paid — which records a real expense, keeping actuals separate from forecasts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringSheet(
    smartState: SmartLedgerUiState,
    categories: List<CategoryEntity>,
    currencySymbol: String,
    actions: SmartLedgerActions,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    val now = System.currentTimeMillis()
    var showAdd by remember { mutableStateOf(false) }

    // add-form state
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var intervalDays by remember { mutableStateOf("30") }
    var categoryId by remember { mutableStateOf(CategoryEntity.UNCATEGORIZED_ID) }
    var dueDate by remember { mutableStateOf(LocalDate.now().plusDays(30)) }
    var showDuePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("RECURRING PAYMENTS", style = MaterialTheme.typography.labelSmall, color = colors.ochreAccent)
            Spacer(Modifier.height(4.dp))
            Text("Bills & Subscriptions", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
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

            // ---- forecasts (estimate, never recorded)
            if (smartState.forecastItems.isNotEmpty()) {
                Text("NEXT 30 DAYS · ESTIMATE — NOT YET SPENT", style = MaterialTheme.typography.labelSmall, color = colors.ochreAccent)
                Spacer(Modifier.height(6.dp))
                var forecastTotal = 0L
                smartState.forecastItems.forEach { item ->
                    forecastTotal += item.amountCents
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
                            Text(
                                Instant.ofEpochDay(item.dueMillis / 86_400_000L).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkMuted
                            )
                        }
                        Text(
                            CurrencyFormatter.formatCents(item.amountCents, currencySymbol),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ochreAccent
                        )
                    }
                }
                Text(
                    "Forecast total ${CurrencyFormatter.formatCents(forecastTotal, currencySymbol)} — an estimate " +
                        "from your confirmed rules; it never counts as actual spending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(Modifier.height(6.dp))
            }

            // ---- detected candidates
            if (smartState.recurringCandidates.isNotEmpty()) {
                Text("DETECTED PATTERNS · CONFIRM TO CREATE", style = MaterialTheme.typography.labelSmall, color = colors.fjordAccent)
                Spacer(Modifier.height(6.dp))
                smartState.recurringCandidates.forEach { candidate ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.elevatedSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.merchant, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                                Text(
                                    "~${CurrencyFormatter.formatCents(candidate.avgAmountCents, currencySymbol)} " +
                                        "every ${candidate.intervalDays} days · ${candidate.occurrences} occurrences · " +
                                        "confidence ${candidate.confidence}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkSecondary
                                )
                                Text(
                                    "Next expected " + Instant.ofEpochDay(candidate.expectedNextMillis / 86_400_000L)
                                        .atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkMuted
                                )
                            }
                            TextButton(onClick = { actions.onConfirmCandidate(candidate, null) }) {
                                Text("CREATE", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ---- my rules
            Text("MY RECURRING PAYMENTS", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(6.dp))
            if (smartState.recurringRules.isEmpty()) {
                Text("No recurring payments yet.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            }
            smartState.recurringRules.forEach { rule ->
                val overdue = rule.isActive && rule.nextDueMillis < now
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.background,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (overdue) colors.warningAccent else colors.hairlineBorder
                    )
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.title, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                                Text(
                                    "${CurrencyFormatter.formatCents(rule.amountCents, currencySymbol)} · " +
                                        "every ${rule.intervalDays} days · due " +
                                        Instant.ofEpochDay(rule.nextDueMillis / 86_400_000L)
                                            .atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt) +
                                        if (overdue) " · past due (not counted until you record it)" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (overdue) colors.warningAccent else colors.inkSecondary
                                )
                            }
                            Switch(
                                checked = rule.isActive,
                                onCheckedChange = { actions.onSetRecurringActive(rule.id, it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = colors.positiveAccent)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { actions.onMarkRecurringPaid(rule.id) },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("MARK PAID (RECORDS IT)", style = MaterialTheme.typography.labelSmall) }
                            OutlinedButton(
                                onClick = { actions.onDeleteRecurring(rule.id) },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("DELETE", style = MaterialTheme.typography.labelSmall, color = colors.alertAccent)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(6.dp))
            if (showAdd) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = colors.elevatedSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("NEW RECURRING PAYMENT", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title (e.g. Internet bill)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it },
                                label = { Text("Amount ${currencySymbol}") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = intervalDays,
                                onValueChange = { intervalDays = it.filter { c -> c.isDigit() } },
                                label = { Text("Every N days") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
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
                                        .background(if (active) colors.inkPrimary else colors.background)
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Next due: $dueDate", style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                            TextButton(onClick = { showDuePicker = true }) { Text("PICK DATE", style = MaterialTheme.typography.labelSmall) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showAdd = false },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("CANCEL") }
                            Button(
                                onClick = {
                                    actions.onSaveRecurring(
                                        null,
                                        title.trim(),
                                        KeypadExpressionEvaluator.evaluateToCents(amount.ifBlank { "0" }),
                                        categoryId,
                                        intervalDays.toIntOrNull()?.coerceAtLeast(1) ?: 30,
                                        dueDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                        ""
                                    )
                                    title = ""; amount = ""
                                    showAdd = false
                                },
                                enabled = title.isNotBlank() &&
                                    KeypadExpressionEvaluator.evaluateToCents(amount.ifBlank { "0" }) > 0L,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.inkPrimary, contentColor = colors.surface)
                            ) { Text("SAVE", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("+ ADD RECURRING PAYMENT", style = MaterialTheme.typography.labelLarge) }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Detection proposes, you confirm: patterns found in your history are only suggestions. " +
                    "Forecasts are labelled estimates and are never recorded as actual spending until you " +
                    "mark them paid.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }

    if (showDuePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDuePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        dueDate = Instant.ofEpochDay(millis / 86_400_000L).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDuePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDuePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}
