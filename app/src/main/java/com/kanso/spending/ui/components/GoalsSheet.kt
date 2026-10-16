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
 * Savings goals: labelled targets and forecasts (never implied balances),
 * plus contributions — records that show progress without pretending the
 * money left your accounts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsSheet(
    smartState: SmartLedgerUiState,
    currencySymbol: String,
    actions: SmartLedgerActions,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    var showAdd by remember { mutableStateOf(false) }
    var expandedGoal by remember { mutableStateOf<String?>(null) }

    var title by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf(LocalDate.now().plusMonths(6)) }
    var showDeadlinePicker by remember { mutableStateOf(false) }
    var contributeAmount by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("SAVINGS GOALS", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
            Spacer(Modifier.height(4.dp))
            Text("Save for Something", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
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

            smartState.goalForecasts.forEach { forecast ->
                val goal = smartState.savingsGoals.firstOrNull { it.id == forecast.goalId }
                val contributions = smartState.contributions.filter { it.goalId == forecast.goalId }
                val expanded = expandedGoal == forecast.goalId
                val progress = if (forecast.targetCents > 0) {
                    (forecast.savedCents.toDouble() / forecast.targetCents).toFloat().coerceIn(0f, 1f)
                } else 0f

                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        expandedGoal = if (expanded) null else forecast.goalId
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = if (expanded) colors.elevatedSurface else colors.background,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (forecast.onTrack) colors.hairlineBorder else colors.warningAccent
                    )
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(forecast.title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                                Text(
                                    "${CurrencyFormatter.formatCents(forecast.savedCents, currencySymbol)} of " +
                                        CurrencyFormatter.formatCents(forecast.targetCents, currencySymbol) +
                                        (goal?.deadlineMillis?.let {
                                            " · by " + LocalDate.ofEpochDay(it / 86_400_000L)
                                                .format(dateFmt)
                                        } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkSecondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${(progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (forecast.onTrack) colors.positiveAccent else colors.warningAccent
                                )
                                Text(
                                    if (expanded) "TAP TO CLOSE" else "TAP TO OPEN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkMuted
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = if (forecast.onTrack) colors.positiveAccent else colors.warningAccent,
                            trackColor = colors.elevatedSurface
                        )

                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Projected ${CurrencyFormatter.formatCents(forecast.projectedSavedCents, currencySymbol)} " +
                                "by the deadline (estimate from your recent saving rate — " +
                                (if (forecast.onTrack) "on track)" else
                                    "needs about ${CurrencyFormatter.formatCents(forecast.requiredMonthlyCents, currencySymbol)}/month))"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted
                        )
                        Text(
                            "Contributions are labelled progress records — account balances are unchanged; " +
                                "the money is wherever you keep it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted
                        )

                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = contributeAmount,
                                    onValueChange = { contributeAmount = it },
                                    label = { Text("Contribute ${currencySymbol}") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        val cents = KeypadExpressionEvaluator.evaluateToCents(contributeAmount.ifBlank { "0" })
                                        if (cents > 0L) {
                                            actions.onAddContribution(forecast.goalId, cents, "")
                                            contributeAmount = ""
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text("ADD") }
                            }

                            if (contributions.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("CONTRIBUTIONS", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                                contributions.takeLast(10).reversed().forEach { contribution ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            LocalDate.ofEpochDay(contribution.timestampEpochMillis / 86_400_000L)
                                                .format(dateFmt) +
                                                (if (contribution.note.isNotBlank()) " · ${contribution.note}" else ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.inkSecondary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            CurrencyFormatter.formatCents(contribution.amountCents, currencySymbol),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.inkPrimary
                                        )
                                        TextButton(onClick = { actions.onDeleteContribution(contribution.id) }) {
                                            Text("×", style = MaterialTheme.typography.labelSmall, color = colors.alertAccent)
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        actions.onSaveGoal(
                                            goal?.id,
                                            goal?.title ?: forecast.title,
                                            goal?.targetCents ?: forecast.targetCents,
                                            goal?.deadlineMillis,
                                            goal?.linkedAccountId ?: ""
                                        )
                                    },
                                    modifier = Modifier.weight(1f).height(42.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text("EDIT GOAL", style = MaterialTheme.typography.labelSmall) }
                                OutlinedButton(
                                    onClick = { actions.onDeleteGoal(forecast.goalId) },
                                    modifier = Modifier.weight(1f).height(42.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text("DELETE GOAL", style = MaterialTheme.typography.labelSmall, color = colors.alertAccent) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (smartState.goalForecasts.isEmpty()) {
                Text(
                    "No goals yet — set a target and track it without touching your ledger balances.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted
                )
                Spacer(Modifier.height(8.dp))
            }

            if (showAdd) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = colors.elevatedSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("NEW GOAL", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Goal name (e.g. Emergency fund)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = target,
                            onValueChange = { target = it },
                            label = { Text("Target ${currencySymbol}") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Deadline: $deadline (optional)", style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                            TextButton(onClick = { showDeadlinePicker = true }) { Text("PICK", style = MaterialTheme.typography.labelSmall) }
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
                                    actions.onSaveGoal(
                                        null,
                                        title.trim(),
                                        KeypadExpressionEvaluator.evaluateToCents(target.ifBlank { "0" }),
                                        deadline.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                        ""
                                    )
                                    title = ""; target = ""
                                    showAdd = false
                                },
                                enabled = title.isNotBlank() &&
                                    KeypadExpressionEvaluator.evaluateToCents(target.ifBlank { "0" }) > 0L,
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
                ) { Text("+ NEW GOAL", style = MaterialTheme.typography.labelLarge) }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Net worth on the dashboard counts your account balances only. Goal progress is a " +
                    "label on the same money — never double-counted, and projections are labelled estimates.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }

    if (showDeadlinePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = deadline.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDeadlinePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        deadline = LocalDate.ofEpochDay(millis / 86_400_000L)
                    }
                    showDeadlinePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDeadlinePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}
