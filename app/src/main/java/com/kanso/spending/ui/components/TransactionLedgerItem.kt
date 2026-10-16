package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.domain.CategoryDisplayInfo
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.DayLedgerSection
import com.kanso.spending.domain.LedgerDisplayEntry
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DayLedgerSectionCard(
    section: DayLedgerSection,
    currencySymbol: String,
    categoryDisplay: (String) -> CategoryDisplayInfo,
    onEditExpense: (ExpenseEntity) -> Unit,
    onDeleteExpense: (ExpenseEntity) -> Unit,
    onDeleteIncome: (com.kanso.spending.data.local.entity.IncomeEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKansoColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.elevatedSurface.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(section.headerTitle, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            // Day header shows outflow, plus the net when money-in happened that day.
            if (section.inflowTotalCents > 0L) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "-${CurrencyFormatter.formatCents(section.outflowTotalCents, currencySymbol)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.inkPrimary
                    )
                    Text(
                        "+${CurrencyFormatter.formatCents(section.inflowTotalCents, currencySymbol)} IN",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.positiveAccent
                    )
                }
            } else {
                Text(
                    "-${CurrencyFormatter.formatCents(section.outflowTotalCents, currencySymbol)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkPrimary
                )
            }
        }

        section.entries.forEachIndexed { idx, entry ->
            if (idx > 0) HorizontalDivider(color = colors.hairlineBorder, thickness = 0.75.dp)
            entry.expense?.let { expense ->
                TransactionLedgerRow(
                    expense = expense,
                    currencySymbol = currencySymbol,
                    categoryDisplay = categoryDisplay,
                    onClick = { onEditExpense(expense) },
                    onDelete = { onDeleteExpense(expense) }
                )
            }
            entry.income?.let { income ->
                MoneyInLedgerRow(
                    income = income,
                    currencySymbol = currencySymbol,
                    onDelete = { onDeleteIncome(income) }
                )
            }
        }
    }
}

/** Shared time label for ledger rows. */
private fun rowTimeLabel(millis: Long): String =
    DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

@Composable
fun TransactionLedgerRow(
    expense: ExpenseEntity,
    currencySymbol: String,
    categoryDisplay: (String) -> CategoryDisplayInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalKansoColors.current
    val display = categoryDisplay(expense.categoryId)
    val payment = PaymentMethod.resolveId(expense.paymentMethod)
    val accent = Color(display.colorHex)
    val providerSuffix = if (expense.paymentProvider.isNotBlank()) " (${expense.paymentProvider})" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.elevatedSurface)
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    display.displayName.take(3).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = expense.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (expense.isRecurring) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Repeat, contentDescription = "Recurring", tint = colors.positiveAccent, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                val methodText = "${payment.badgeCode}${providerSuffix} • ${rowTimeLabel(expense.timestampEpochMillis)}"
                val subtitle = if (expense.note.isNotBlank()) {
                    "${display.displayName} • $methodText — ${expense.note}"
                } else {
                    "${display.displayName} • $methodText"
                }
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("-${CurrencyFormatter.formatCents(expense.amountCents, currencySymbol)}", style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = colors.inkMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun MoneyInLedgerRow(
    income: com.kanso.spending.data.local.entity.IncomeEntity,
    currencySymbol: String,
    onDelete: () -> Unit
) {
    val colors = LocalKansoColors.current
    val source = com.kanso.spending.data.model.MoneySource.fromId(income.sourceId)
    val payment = PaymentMethod.resolveId(income.paymentMethod)
    val providerSuffix = if (income.paymentProvider.isNotBlank()) " (${income.paymentProvider})" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.positiveAccent.copy(alpha = 0.12f))
                    .border(1.dp, colors.positiveAccent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("IN", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = income.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val subtitle = "${source.label} • ${payment.badgeCode}$providerSuffix • ${rowTimeLabel(income.timestampEpochMillis)}"
                Text(
                    subtitle + if (income.note.isNotBlank()) " — ${income.note}" else "",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "+${CurrencyFormatter.formatCents(income.amountCents, currencySymbol)}",
                style = MaterialTheme.typography.titleMedium,
                color = colors.positiveAccent
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete money-in", tint = colors.inkMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}
