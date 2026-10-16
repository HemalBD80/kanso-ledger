package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import java.time.LocalDate
import java.time.YearMonth

enum class BudgetPacingStatus(val badgeLabel: String) {
    ON_TRACK("ON PACE"),
    WATCH_PACE("PACING WARM"),
    OVER_BUDGET("OVER TARGET")
}

data class CategoryBreakdownItem(
    val category: SpendingCategory,
    val spentCents: Long,
    val budgetLimitCents: Long?,
    val shareOfTotalSpend: Float, // 0f..1f
    val transactionCount: Int
) {
    val budgetUtilization: Float?
        get() = budgetLimitCents?.takeIf { it > 0L }?.let { spentCents.toFloat() / it.toFloat() }

    val isOverCategoryLimit: Boolean
        get() = budgetLimitCents != null && budgetLimitCents > 0L && spentCents > budgetLimitCents
}

data class DailyBarPoint(
    val date: LocalDate,
    val dayOfMonth: Int,
    val spentCents: Long,
    val isToday: Boolean
)

data class DayLedgerSection(
    val date: LocalDate,
    val headerTitle: String,
    val dayTotalCents: Long,
    val entries: List<ExpenseEntity>
)

data class MerchantQuickSuggestion(
    val title: String,
    val category: SpendingCategory,
    val paymentMethod: PaymentMethod,
    val lastAmountCents: Long
)

data class MonthlyLedgerAnalytics(
    val yearMonth: YearMonth,
    val totalSpentCents: Long,
    val monthlyBudgetCents: Long,
    val remainingBudgetCents: Long,
    val dailySafeToSpendCents: Long,
    val averageDailyBurnCents: Long,
    val dailyTargetCents: Long,
    val budgetProgressFraction: Float,
    val monthElapsedFraction: Float,
    val pacingStatus: BudgetPacingStatus,
    val pacingDeltaPercent: Int,
    val recurringSpentCents: Long,
    val categoryBreakdown: List<CategoryBreakdownItem>,
    val dailyBars: List<DailyBarPoint>,
    val daySections: List<DayLedgerSection>,
    val merchantSuggestions: List<MerchantQuickSuggestion>
)
