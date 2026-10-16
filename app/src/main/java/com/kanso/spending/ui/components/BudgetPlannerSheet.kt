package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.ui.theme.LocalKansoColors
import kotlin.math.roundToLong

private val CURRENCIES = listOf("৳", "$", "€", "£", "₹", "¥", "₩")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetPlannerSheet(
    currentMonthlyBudgetCents: Long,
    categoryBreakdown: List<com.kanso.spending.domain.CategoryBreakdownItem>,
    categories: List<CategoryEntity>,
    currencySymbol: String,
    onSaveConfiguration: (Long, String, Map<String, Long>) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    var selectedCurrency by remember(currencySymbol) { mutableStateOf(currencySymbol) }
    var overallText by remember(currentMonthlyBudgetCents) { mutableStateOf((currentMonthlyBudgetCents / 100L).toString()) }
    val catInputs = remember(categoryBreakdown, categories) {
        mutableStateMapOf<String, String>().apply {
            categories.forEach { cat ->
                val lim = categoryBreakdown.firstOrNull { it.categoryId == cat.id }?.budgetLimitCents
                put(cat.id, if (lim != null && lim > 0L) (lim / 100L).toString() else "")
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("MONTHLY LEDGER CAPS", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(4.dp))
            Text("Budget & Currency", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CURRENCIES.forEach { sym ->
                    val active = selectedCurrency == sym
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(10.dp))
                            .clickable { selectedCurrency = sym },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(sym, style = MaterialTheme.typography.titleMedium, color = if (active) colors.surface else colors.inkPrimary)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = overallText,
                onValueChange = { overallText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Overall Monthly Cap ($selectedCurrency)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            Text("CATEGORY CAPS (OPTIONAL)", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Spacer(Modifier.height(8.dp))

            categories.forEach { cat ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(cat.colorHex)))
                        Spacer(Modifier.width(10.dp))
                        Text(cat.displayName, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                    }
                    OutlinedTextField(
                        value = catInputs[cat.id].orEmpty(),
                        onValueChange = { v -> catInputs[cat.id] = v.filter { ch -> ch.isDigit() || ch == '.' } },
                        placeholder = { Text("No cap") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    // Testing discipline: an empty field saves 0.00 — no hidden default.
                    val overall = ((overallText.toDoubleOrNull() ?: 0.0) * 100.0).roundToLong().coerceAtLeast(0L)
                    val map = catInputs.mapValues { (_, t) -> ((t.toDoubleOrNull() ?: 0.0) * 100.0).roundToLong().coerceAtLeast(0L) }
                    onSaveConfiguration(overall, selectedCurrency, map)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.inkPrimary, contentColor = colors.surface)
            ) {
                Text("SAVE BUDGET TO LOCAL SQLITE", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
