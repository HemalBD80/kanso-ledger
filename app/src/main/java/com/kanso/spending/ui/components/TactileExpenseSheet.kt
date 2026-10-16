package com.kanso.spending.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.KeypadExpressionEvaluator
import com.kanso.spending.ui.theme.LocalKansoColors

private val KEYPAD_ROWS = listOf(
    listOf("7", "8", "9", "+"),
    listOf("4", "5", "6", "-"),
    listOf("1", "2", "3", "."),
    listOf("C", "0", "⌫", "=")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TactileExpenseSheet(
    editingExpense: ExpenseEntity?,
    currencySymbol: String,
    merchantSuggestions: List<String>,
    onSaveExpense: (
        existingId: Long?,
        title: String,
        amountCents: Long,
        category: SpendingCategory,
        paymentMethod: PaymentMethod,
        timestampMillis: Long,
        note: String,
        isRecurring: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    val haptics = LocalHapticFeedback.current

    var expression by remember(editingExpense) {
        mutableStateOf(
            if (editingExpense != null) {
                val d = editingExpense.amountCents / 100.0
                if (editingExpense.amountCents % 100L == 0L) (editingExpense.amountCents / 100L).toString() else "%.2f".format(d)
            } else "0"
        )
    }
    var title by remember(editingExpense) { mutableStateOf(editingExpense?.title ?: "") }
    var note by remember(editingExpense) { mutableStateOf(editingExpense?.note ?: "") }
    var selectedCategory by remember(editingExpense) {
        mutableStateOf(editingExpense?.let { SpendingCategory.fromId(it.categoryId) } ?: SpendingCategory.DINING)
    }
    var selectedPayment by remember(editingExpense) {
        mutableStateOf(editingExpense?.let { PaymentMethod.fromId(it.paymentMethod) } ?: PaymentMethod.CARD)
    }
    var isRecurring by remember(editingExpense) { mutableStateOf(editingExpense?.isRecurring ?: false) }

    val evaluatedCents = remember(expression) { KeypadExpressionEvaluator.evaluateToCents(expression) }
    val hasOperator = remember(expression) { KeypadExpressionEvaluator.containsOperator(expression) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            // Live Amount Readout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.elevatedSurface)
                    .border(1.dp, colors.hairlineBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = if (editingExpense == null) "NEW LEDGER ENTRY" else "EDIT LEDGER ENTRY",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary
                    )
                    if (hasOperator) {
                        Text(expression, style = MaterialTheme.typography.labelLarge, color = colors.positiveAccent)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (hasOperator) {
                        "= ${CurrencyFormatter.formatCents(evaluatedCents, currencySymbol)}"
                    } else {
                        "$currencySymbol$expression"
                    },
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.inkPrimary
                )
            }

            Spacer(Modifier.height(12.dp))

            // Merchant Title + Quick Auto-Complete Chips
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Merchant or Description") },
                placeholder = { Text(selectedCategory.displayName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (merchantSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    merchantSuggestions.forEach { suggestion ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.elevatedSurface)
                                .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                                .clickable { title = suggestion }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(suggestion, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Category Selector Strip
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpendingCategory.entries.forEach { cat ->
                    val sel = selectedCategory == cat
                    val c = Color(cat.accentHex)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) c.copy(alpha = 0.18f) else colors.elevatedSurface)
                            .border(1.dp, if (sel) c else colors.hairlineBorder, RoundedCornerShape(10.dp))
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(6.dp))
                        Text(cat.displayName, style = MaterialTheme.typography.labelLarge, color = colors.inkPrimary)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaymentMethod.entries.forEach { m ->
                        val active = selectedPayment == m
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                                .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedPayment = m }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(m.badgeCode, style = MaterialTheme.typography.labelSmall, color = if (active) colors.surface else colors.inkSecondary)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { isRecurring = !isRecurring }.padding(horizontal = 4.dp)
                ) {
                    Checkbox(checked = isRecurring, onCheckedChange = { isRecurring = it })
                    Text("Recurring", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Optional Note") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            KEYPAD_ROWS.forEach { rowKeys ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKeys.forEach { key ->
                        val isOp = key in listOf("+", "-", "=", "C", "⌫")
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isOp) colors.elevatedSurface else colors.background)
                                .border(1.dp, colors.hairlineBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    expression = when (key) {
                                        "C" -> KeypadExpressionEvaluator.clear()
                                        "⌫" -> KeypadExpressionEvaluator.backspace(expression)
                                        "=" -> {
                                            val cents = KeypadExpressionEvaluator.evaluateToCents(expression)
                                            if (cents % 100L == 0L) (cents / 100L).toString() else "%.2f".format(cents / 100.0)
                                        }
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
                    val finalCents = evaluatedCents.coerceAtLeast(1L)
                    onSaveExpense(
                        editingExpense?.id,
                        title.ifBlank { selectedCategory.displayName },
                        finalCents,
                        selectedCategory,
                        selectedPayment,
                        editingExpense?.timestampEpochMillis ?: System.currentTimeMillis(),
                        note,
                        isRecurring
                    )
                    onDismiss()
                },
                enabled = evaluatedCents > 0L,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.inkPrimary, contentColor = colors.surface)
            ) {
                Text("COMMIT ${CurrencyFormatter.formatCents(evaluatedCents, currencySymbol)} TO LOCAL LEDGER", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}
