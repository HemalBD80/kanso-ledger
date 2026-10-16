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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.DayLedgerSection
import com.kanso.spending.ui.theme.LocalKansoColors

@Composable
fun DayLedgerSectionCard(
    section: DayLedgerSection,
    currencySymbol: String,
    onEditExpense: (ExpenseEntity) -> Unit,
    onDeleteExpense: (ExpenseEntity) -> Unit,
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
            Text("-${CurrencyFormatter.formatCents(section.dayTotalCents, currencySymbol)}", style = MaterialTheme.typography.labelLarge, color = colors.inkPrimary)
        }

        section.entries.forEachIndexed { idx, expense ->
            if (idx > 0) HorizontalDivider(color = colors.hairlineBorder, thickness = 0.75.dp)
            TransactionLedgerRow(
                expense = expense,
                currencySymbol = currencySymbol,
                onClick = { onEditExpense(expense) },
                onDelete = { onDeleteExpense(expense) }
            )
        }
    }
}

@Composable
fun TransactionLedgerRow(
    expense: ExpenseEntity,
    currencySymbol: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalKansoColors.current
    val category = SpendingCategory.fromId(expense.categoryId)
    val payment = PaymentMethod.fromId(expense.paymentMethod)
    val accent = Color(category.accentHex)
    val badgeBg = if (colors.isDark) Color(category.containerDarkHex) else Color(category.containerLightHex)

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
                    .background(badgeBg)
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(category.shortCode, style = MaterialTheme.typography.labelSmall, color = accent)
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
                val subtitle = if (expense.note.isNotBlank()) {
                    "${category.displayName} • ${payment.badgeCode} — ${expense.note}"
                } else {
                    "${category.displayName} • ${payment.badgeCode}"
                }
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(CurrencyFormatter.formatCents(expense.amountCents, currencySymbol), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = colors.inkMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}
