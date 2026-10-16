package com.kanso.spending.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.model.MoneySource
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.KeypadExpressionEvaluator
import com.kanso.spending.ui.SpendingDashboardUiState
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Money In / Add Money sheet: salary, freelance, gifts, refunds, transfers or
 * the very first opening balance. Every save is a new row — an earlier balance
 * or addition is never overwritten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyInSheet(
    uiState: SpendingDashboardUiState,
    onSaveMoneyIn: (
        existingId: Long?,
        title: String,
        amountCents: Long,
        source: MoneySource,
        paymentMethod: PaymentMethod,
        paymentProvider: String,
        timestampMillis: Long,
        note: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current

    var expression by remember { mutableStateOf("0") }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(MoneySource.INITIAL_BALANCE) }
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var provider by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val evaluatedCents = remember(expression) { KeypadExpressionEvaluator.evaluateToCents(expression) }
    val showProviderField = method in listOf(PaymentMethod.MOBILE_WALLET, PaymentMethod.BANK_TRANSFER, PaymentMethod.OTHER)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("ADD MONEY / MONEY IN", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
            Spacer(Modifier.height(4.dp))
            Text("Record Money Added", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairlineBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "AMOUNT TO ADD",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.currencySymbol + expression,
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.positiveAccent
                )
            }

            Spacer(Modifier.height(10.dp))

            // Source picker
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MoneySource.entries.forEach { s ->
                    val active = source == s
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { source = s }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(s.label, style = MaterialTheme.typography.labelSmall, color = if (active) colors.surface else colors.inkSecondary)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Source description (e.g. October Salary)") },
                placeholder = { Text(source.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            // Payment method picker
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PaymentMethod.entries.forEach { m ->
                    val active = method == m
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { method = m }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(m.label, style = MaterialTheme.typography.labelSmall, color = if (active) colors.surface else colors.inkSecondary)
                    }
                }
            }
            if (showProviderField) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = provider,
                    onValueChange = { provider = it },
                    label = { Text("Wallet / Bank Name (bKash, Nagad, Rocket...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Optional Note") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Date: $selectedDate (time: now)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary
                )
                TextButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.inkMuted)
                    Spacer(Modifier.width(6.dp))
                    Text("PICK DATE", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(6.dp))
            KEYPAD_ROWS_NOOPS.forEach { rowKeys ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKeys.forEach { key ->
                        val isOp = key in listOf("C", "⌫")
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isOp) colors.elevatedSurface else colors.background)
                                .border(1.dp, colors.hairlineBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    expression = when (key) {
                                        "C" -> KeypadExpressionEvaluator.clear()
                                        "⌫" -> KeypadExpressionEvaluator.backspace(expression)
                                        else -> KeypadExpressionEvaluator.appendToken(expression, key)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (key == "C") colors.alertAccent else colors.inkPrimary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val zone = ZoneId.systemDefault()
                    val millis = selectedDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                    onSaveMoneyIn(
                        null,
                        title.ifBlank { source.label },
                        evaluatedCents.coerceAtLeast(1L),
                        source,
                        method,
                        provider,
                        millis,
                        note
                    )
                    onDismiss()
                },
                enabled = evaluatedCents > 0L,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.positiveAccent, contentColor = colors.surface)
            ) {
                Text("ADD ${CurrencyFormatter.formatCents(evaluatedCents, uiState.currencySymbol)} TO BALANCE", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = "Each addition is its own transaction. Available balance = opening balance + money added − expenses.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(0).toEpochMilli() + selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        selectedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private val KEYPAD_ROWS_NOOPS = listOf(
    listOf("7", "8", "9"),
    listOf("4", "5", "6"),
    listOf("1", "2", "3"),
    listOf("C", "0", "⌫", ".")
)
