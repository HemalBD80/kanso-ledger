package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.model.PaymentMethod
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Display metadata (name, color) resolved for a category id. */
data class CategoryDisplayInfo(
    val id: String,
    val displayName: String,
    val colorHex: Long
)

/** Map of category id -> display metadata, resolved from the `categories` table. */
typealias CategoryDisplayMap = Map<String, CategoryDisplayInfo>

/**
 * All dashboard math in one place. Reads are pure so every figure the UI shows
 * can be traced straight back to the Room rows and re-verified in tests.
 */
object SpendingAnalyticsEngine {

    fun computeMonthlyAnalytics(
        yearMonth: YearMonth,
        monthExpenses: List<ExpenseEntity>,
        monthIncomes: List<IncomeEntity>,
        filteredEntries: List<LedgerDisplayEntry>,
        categoryDisplayMap: Map<String, CategoryDisplayInfo>,
        monthlyBudgetCents: Long,
        categoryBudgets: List<CategoryBudgetEntity>,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): MonthlyLedgerAnalytics {

        val totalSpent = monthExpenses.sumOf { it.amountCents }
        val moneyIn = monthIncomes.sumOf { it.amountCents }
        val budget = monthlyBudgetCents.coerceAtLeast(0L)
        val remaining = budget - totalSpent
        val daysInMonth = yearMonth.lengthOfMonth()
        val currentMonth = YearMonth.from(today)

        val elapsedDays = when {
            currentMonth == yearMonth -> today.dayOfMonth.coerceAtLeast(1)
            yearMonth.isBefore(currentMonth) -> daysInMonth
            else -> 1
        }
        val remainingDays = when {
            currentMonth == yearMonth -> (daysInMonth - today.dayOfMonth + 1).coerceAtLeast(1)
            yearMonth.isAfter(currentMonth) -> daysInMonth
            else -> 1
        }

        val budgetProgress = if (budget > 0L) (totalSpent.toFloat() / budget.toFloat()).coerceAtLeast(0f) else 0f
        val monthElapsed = (elapsedDays.toFloat() / daysInMonth.toFloat()).coerceIn(0f, 1f)
        val pacingDelta = ((budgetProgress - monthElapsed) * 100f).roundToInt()
        val pacingStatus = when {
            totalSpent > budget -> BudgetPacingStatus.OVER_BUDGET
            pacingDelta > 8 -> BudgetPacingStatus.WATCH_PACE
            else -> BudgetPacingStatus.ON_TRACK
        }

        val catBudgetMap = categoryBudgets.associate { it.categoryId to it.limitCents }
        val byCategory = monthExpenses.groupBy { it.categoryId }
        val breakdownIds = (categoryDisplayMap.keys + byCategory.keys).distinct()
        val categoryBreakdown = breakdownIds.map { id ->
            val info = categoryDisplayMap[id]
                ?: CategoryDisplayInfo(id, id, 0xFF161614)
            val items = byCategory[id].orEmpty()
            val spent = items.sumOf { it.amountCents }
            CategoryBreakdownItem(
                categoryId = id,
                displayName = info.displayName,
                colorHex = info.colorHex,
                spentCents = spent,
                budgetLimitCents = catBudgetMap[id],
                shareOfTotalSpend = if (totalSpent > 0L) spent.toFloat() / totalSpent.toFloat() else 0f,
                transactionCount = items.size
            )
        }.sortedWith(
            compareByDescending<CategoryBreakdownItem> { it.spentCents }
                .thenBy { it.displayName }
        )

        val methodBreakdown = monthExpenses
            .groupBy { PaymentMethod.resolveId(it.paymentMethod) to it.paymentProvider }
            .map { (methodAndProvider, items) ->
                val (methodIdRaw, provider) = methodAndProvider
                val method = PaymentMethod.resolveId(methodIdRaw)
                MethodBreakdownItem(
                    methodId = method.id,
                    label = method.label,
                    provider = provider,
                    spentCents = items.sumOf { it.amountCents },
                    transactionCount = items.size
                )
            }
            .groupBy { it.methodId }
            .map { (_, items) ->
                items.reduce { acc, item ->
                    acc.copy(
                        provider = acc.provider.ifBlank { item.provider },
                        spentCents = acc.spentCents + item.spentCents,
                        transactionCount = acc.transactionCount + item.transactionCount
                    )
                }
            }
            .sortedByDescending { it.spentCents }

        val spendByDay = monthExpenses.groupBy {
            Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zoneId).toLocalDate().dayOfMonth
        }
        val dailyBars = (1..daysInMonth).map { day ->
            val date = yearMonth.atDay(day)
            DailyBarPoint(date, day, spendByDay[day].orEmpty().sumOf { it.amountCents }, date == today)
        }

        val daySections = filteredEntries
            .groupBy {
                Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zoneId).toLocalDate()
            }
            .entries.sortedByDescending { it.key }
            .map { (date, list) ->
                val prefix = when (date) {
                    today -> "TODAY • "
                    today.minusDays(1) -> "YESTERDAY • "
                    else -> ""
                }
                DayLedgerSection(
                    date = date,
                    headerTitle = "$prefix${date.format(dayHeaderFormatter).uppercase(Locale.US)}",
                    outflowTotalCents = list.filter { !it.isIncome }.sumOf { it.amountCents },
                    inflowTotalCents = list.filter { it.isIncome }.sumOf { it.amountCents },
                    entries = list.sortedWith(
                        compareByDescending<LedgerDisplayEntry> { it.timestampEpochMillis }
                            .thenByDescending { it.recordId }
                    )
                )
            }

        val suggestions = monthExpenses
            .sortedByDescending { it.timestampEpochMillis }
            .distinctBy { it.title.trim().lowercase(Locale.US) }
            .take(8)
            .map {
                MerchantQuickSuggestion(
                    title = it.title,
                    categoryId = it.categoryId,
                    paymentMethod = PaymentMethod.resolveId(it.paymentMethod),
                    lastAmountCents = it.amountCents
                )
            }

        return MonthlyLedgerAnalytics(
            yearMonth = yearMonth,
            totalSpentCents = totalSpent,
            moneyInCents = moneyIn,
            monthlyBudgetCents = budget,
            remainingBudgetCents = remaining,
            dailySafeToSpendCents = if (remaining > 0L) remaining / remainingDays else 0L,
            averageDailyBurnCents = totalSpent / elapsedDays,
            dailyTargetCents = budget / daysInMonth,
            budgetProgressFraction = budgetProgress,
            monthElapsedFraction = monthElapsed,
            pacingStatus = pacingStatus,
            pacingDeltaPercent = pacingDelta,
            recurringSpentCents = monthExpenses.filter { it.isRecurring }.sumOf { it.amountCents },
            categoryBreakdown = categoryBreakdown,
            methodBreakdown = methodBreakdown,
            dailyBars = dailyBars,
            daySections = daySections,
            merchantSuggestions = suggestions
        )
    }
}
