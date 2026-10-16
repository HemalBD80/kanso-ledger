package com.kanso.spending.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.model.SpendingCategory
import com.kanso.spending.domain.BudgetPacingStatus
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.MonthlyLedgerAnalytics
import com.kanso.spending.ui.theme.LocalKansoColors
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HeroLedgerCard(
    analytics: MonthlyLedgerAnalytics,
    currencySymbol: String,
    selectedCategory: SpendingCategory?,
    onSelectCategory: (SpendingCategory?) -> Unit,
    onEditBudgetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKansoColors.current
    val statusColor = when (analytics.pacingStatus) {
        BudgetPacingStatus.ON_TRACK -> colors.positiveAccent
        BudgetPacingStatus.WATCH_PACE -> colors.warningAccent
        BudgetPacingStatus.OVER_BUDGET -> colors.alertAccent
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TOTAL OUTFLOW", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            val d = analytics.pacingDeltaPercent
            val deltaText = if (d > 0) "+$d% VS PACE" else if (d < 0) "$d% UNDER PACE" else "ON TARGET"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(statusColor.copy(alpha = 0.14f))
                    .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("${analytics.pacingStatus.badgeLabel} • $deltaText", style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(CurrencyFormatter.formatCents(analytics.totalSpentCents, currencySymbol), style = MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onEditBudgetClick).padding(4.dp)
            ) {
                Text("MONTHLY CAP", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                Text(CurrencyFormatter.formatCentsCompact(analytics.monthlyBudgetCents, currencySymbol), style = MaterialTheme.typography.titleMedium, color = colors.inkSecondary)
            }
        }

        Spacer(Modifier.height(14.dp))

        val active = analytics.categoryBreakdown.filter { it.spentCents > 0L }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(colors.elevatedSurface)) {
            if (active.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(analytics.budgetProgressFraction.coerceIn(0.06f, 1f)).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    active.forEach { item ->
                        val c = Color(item.category.accentHex)
                        val dim = selectedCategory != null && selectedCategory != item.category
                        Box(
                            modifier = Modifier
                                .weight(item.shareOfTotalSpend.coerceAtLeast(0.03f))
                                .fillMaxHeight()
                                .background(if (dim) c.copy(alpha = 0.28f) else c)
                                .clickable { onSelectCategory(if (selectedCategory == item.category) null else item.category) }
                        )
                    }
                }
            }
        }

        if (active.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                active.forEach { item ->
                    val sel = selectedCategory == item.category
                    val c = Color(item.category.accentHex)
                    val pct = (item.shareOfTotalSpend * 100f).roundToInt()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) c.copy(alpha = 0.16f) else colors.elevatedSurface)
                            .border(1.dp, if (sel) c else colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { onSelectCategory(if (sel) null else item.category) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(6.dp))
                        Text("${item.category.shortCode} $pct%", style = MaterialTheme.typography.labelSmall, color = colors.inkPrimary)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = colors.hairlineBorder, thickness = 1.dp)
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricCol(
                label = if (analytics.remainingBudgetCents >= 0L) "REMAINING" else "OVER CAP",
                value = CurrencyFormatter.formatCentsCompact(abs(analytics.remainingBudgetCents), currencySymbol),
                color = if (analytics.remainingBudgetCents >= 0L) colors.inkPrimary else colors.alertAccent
            )
            MetricCol("SAFE / DAY", CurrencyFormatter.formatCentsCompact(analytics.dailySafeToSpendCents, currencySymbol), colors.positiveAccent, Alignment.CenterHorizontally)
            MetricCol("DAILY AVG", CurrencyFormatter.formatCentsCompact(analytics.averageDailyBurnCents, currencySymbol), colors.inkSecondary, Alignment.End)
        }
    }
}

@Composable
private fun MetricCol(label: String, value: String, color: Color, align: Alignment.Horizontal = Alignment.Start) {
    val colors = LocalKansoColors.current
    Column(horizontalAlignment = align) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = color)
    }
}
