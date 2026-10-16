package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object SpendingAnalyticsEngine {

    private val dayHeaderFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

    fun computeMonthlyAnalytics(
        yearMonth: YearMonth,
        monthExpenses: List<ExpenseEntity>,
        filteredExpenses: List<ExpenseEntity>,
        monthlyBudgetCents: Long,
        categoryBudgets: List<CategoryBudgetEntity>,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): MonthlyLedgerAnalytics {
        val totalSpent = monthExpenses.sumOf { it.amountCents }
        val budget = if (monthlyBudgetCents > 0L) monthlyBudgetCents else 1L
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

        val budgetProgress = (totalSpent.toFloat() / budget.toFloat()).coerceAtLeast(0f)
        val monthElapsed = (elapsedDays.toFloat() / daysInMonth.toFloat()).coerceIn(0f, 1f)
        val pacingDelta = ((budgetProgress - monthElapsed) * 100f).roundToInt()
        val pacingStatus = when {
            totalSpent > budget -> BudgetPacingStatus.OVER_BUDGET
            pacingDelta > 8 -> BudgetPacingStatus.WATCH_PACE
            else -> BudgetPacingStatus.ON_TRACK
        }

        val catBudgetMap = categoryBudgets.associate { it.categoryId to it.limitCents }
        val byCategory = monthExpenses.groupBy { SpendingCategory.fromId(it.categoryId) }
        val categoryBreakdown = SpendingCategory.entries.map { cat ->
            val items = byCategory[cat].orEmpty()
            val spent = items.sumOf { it.amountCents }
            CategoryBreakdownItem(
                category = cat,
                spentCents = spent,
                budgetLimitCents = catBudgetMap[cat.id],
                shareOfTotalSpend = if (totalSpent > 0L) spent.toFloat() / totalSpent.toFloat() else 0f,
                transactionCount = items.size
            )
        }.sortedWith(compareByDescending<CategoryBreakdownItem> { it.spentCents }.thenBy { it.category.ordinal })

        val spendByDay = monthExpenses.groupBy {
            Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zoneId).toLocalDate().dayOfMonth
        }
        val dailyBars = (1..daysInMonth).map { day ->
            val date = yearMonth.atDay(day)
            DailyBarPoint(date, day, spendByDay[day].orEmpty().sumOf { it.amountCents }, date == today)
        }

        val daySections = filteredExpenses
            .sortedWith(compareByDescending<ExpenseEntity> { it.timestampEpochMillis }.thenByDescending { it.id })
            .groupBy { Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zoneId).toLocalDate() }
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
                    dayTotalCents = list.sumOf { it.amountCents },
                    entries = list
                )
            }

        val suggestions = monthExpenses
            .sortedByDescending { it.timestampEpochMillis }
            .distinctBy { it.title.trim().lowercase(Locale.US) }
            .take(8)
            .map {
                MerchantQuickSuggestion(
                    title = it.title,
                    category = SpendingCategory.fromId(it.categoryId),
                    paymentMethod = PaymentMethod.fromId(it.paymentMethod),
                    lastAmountCents = it.amountCents
                )
            }

        return MonthlyLedgerAnalytics(
            yearMonth = yearMonth,
            totalSpentCents = totalSpent,
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
            dailyBars = dailyBars,
            daySections = daySections,
            merchantSuggestions = suggestions
        )
    }
}
