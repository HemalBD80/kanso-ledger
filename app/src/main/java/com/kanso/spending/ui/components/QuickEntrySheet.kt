package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.ui.SpendingDashboardUiState
import com.kanso.spending.ui.theme.LocalKansoColors

/**
 * Quick entry: the fastest confirmed path for a normal expense — amount,
 * category, method, done. Everything defaults to today's date, manual source,
 * confirmed status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickEntrySheet(
    uiState: SpendingDashboardUiState,
    categories: List<CategoryEntity>,
    onSaveExpense: (
        id: Long?, title: String, amountCents: Long, categoryId: String,
        paymentMethod: PaymentMethod, provider: String, timestamp: Long,
        note: String, isRecurring: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current

    var amountText by remember { mutableStateOf("") }
    var expression by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var categoryId by remember {
        mutableStateOf(categories.firstOrNull()?.id ?: CategoryEntity.UNCATEGORIZED_ID)
    }
    var methodId by remember { mutableStateOf(PaymentMethod.CASH.id) }

    fun press(key: String) {
        expression = when (key) {
            "C" -> "0"
            "⌫" -> expression.dropLast(1).ifBlank { "0" }
            else -> (expression + key).let { raw ->
                if (raw.count { it == '.' } > 1) expression else raw
            }
        }
        amountText = expression
    }

    val previewCents = remember(expression) {
        runCatching { com.kanso.spending.domain.KeypadExpressionEvaluator.evaluateToCents(expression.ifBlank { "0" }) }
            .getOrDefault(0L)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("QUICK ENTRY", style = MaterialTheme.typography.labelSmall, color = colors.fjordAccent)
            Spacer(Modifier.height(4.dp))
            Text("Record an Expense", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.elevatedSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.End) {
                    Text("AMOUNT", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    Text(
                        CurrencyFormatter.formatCents(previewCents, uiState.currencySymbol),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (previewCents > 0) colors.inkPrimary else colors.inkMuted
                    )
                    if (expression.contains('+') || expression.contains('-')) {
                        Text(expression, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Description (optional — defaults to category name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Text("CATEGORY", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                categories.take(6).forEach { cat ->
                    val active = categoryId == cat.id
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { categoryId = cat.id }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            cat.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) colors.surface else colors.inkSecondary,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("PAID BY", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                PaymentMethod.entries.forEach { method ->
                    val active = methodId == method.id
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { methodId = method.id }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(
                            method.id.replace('_', ' ').uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) colors.surface else colors.inkSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // tactile keypad
            listOf(
                listOf("7", "8", "9", "⌫"),
                listOf("4", "5", "6", "+"),
                listOf("1", "2", "3", "-"),
                listOf("C", "0", ".", "ENTER")
            ).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(3.dp)
                                .height(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when (key) {
                                        "ENTER" -> colors.inkPrimary
                                        "+", "-", "C", "⌫" -> colors.elevatedSurface
                                        else -> colors.background
                                    }
                                )
                                .border(1.dp, colors.hairlineBorder, RoundedCornerShape(12.dp))
                                .clickable(enabled = key != "ENTER") { press(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (key != "ENTER") {
                                Text(
                                    key,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.inkPrimary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val cents = previewCents
                    if (cents > 0L) {
                        onSaveExpense(
                            null,
                            title.ifBlank {
                                categories.firstOrNull { it.id == categoryId }?.displayName ?: "Quick entry"
                            },
                            cents,
                            categoryId,
                            PaymentMethod.resolveId(methodId),
                            "",
                            System.currentTimeMillis(),
                            "",
                            false
                        )
                        amountText = ""
                        expression = "0"
                        title = ""
                        onDismiss()
                    }
                },
                enabled = previewCents > 0L,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.inkPrimary,
                    contentColor = colors.surface,
                    disabledContainerColor = colors.elevatedSurface
                )
            ) {
                Text(
                    "SAVE ${CurrencyFormatter.formatCents(previewCents, uiState.currencySymbol)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Saved as a confirmed, manual record — a real transaction you entered yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}
