package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.model.PaymentMethod
import java.time.LocalDate
import java.time.YearMonth

enum class BudgetPacingStatus(val badgeLabel: String) {
    ON_TRACK("ON PACE"),
    WATCH_PACE("PACING WARM"),
    OVER_BUDGET("OVER TARGET")
}

data class CategoryBreakdownItem(
    /** Stable id stored in the expenses table; renaming a category never changes it. */
    val categoryId: String,
    /** Display name resolved from the `categories` table (or the default seed). */
    val displayName: String,
    val colorHex: Long,
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

data class MethodBreakdownItem(
    val methodId: String,
    val label: String,
    /** Highest provider name seen among the month's transactions, if any. */
    val provider: String,
    val spentCents: Long,
    val transactionCount: Int
)

data class DailyBarPoint(
    val date: LocalDate,
    val dayOfMonth: Int,
    val spentCents: Long,
    val isToday: Boolean
)

/**
 * One row in the unified ledger history: either a spending record or a
 * money-in record. [expense] and [income] are mutually exclusive.
 */
data class LedgerDisplayEntry(
    val expense: ExpenseEntity?,
    val income: IncomeEntity?
) {
    val isIncome: Boolean get() = income != null
    val amountCents: Long get() = income?.amountCents ?: expense?.amountCents ?: 0L
    val title: String get() = income?.title ?: expense?.title.orEmpty()
    val categoryId: String
        get() = if (isIncome) MoneyInCategoryFallback else expense?.categoryId.orEmpty()
    val paymentMethodId: String
        get() = income?.paymentMethod ?: expense?.paymentMethod.orEmpty()
    val provider: String
        get() = income?.paymentProvider ?: expense?.paymentProvider.orEmpty()
    val timestampEpochMillis: Long
        get() = income?.timestampEpochMillis ?: expense?.timestampEpochMillis ?: 0L
    val note: String get() = income?.note ?: expense?.note ?: ""
    val recordId: Long get() = income?.id ?: expense?.id ?: 0L

    companion object {
        /** Money-in rows do not carry a category; reports show the source instead. */
        const val MoneyInCategoryFallback = "__money_in__"

        fun fromExpense(expense: ExpenseEntity) = LedgerDisplayEntry(expense = expense, income = null)
        fun fromIncome(income: IncomeEntity) = LedgerDisplayEntry(expense = null, income = income)
    }
}

data class DayLedgerSection(
    val date: LocalDate,
    val headerTitle: String,
    val outflowTotalCents: Long,
    val inflowTotalCents: Long,
    val entries: List<LedgerDisplayEntry>
) {
    /** Net movement for the day: money-in minus spending. */
    val dayNetCents: Long get() = inflowTotalCents - outflowTotalCents
}

data class MerchantQuickSuggestion(
    val title: String,
    val categoryId: String,
    val paymentMethod: PaymentMethod,
    val lastAmountCents: Long
)

data class MonthlyLedgerAnalytics(
    val yearMonth: YearMonth,
    val totalSpentCents: Long,
    /** Money added (income) recorded during this month. */
    val moneyInCents: Long,
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
    val methodBreakdown: List<MethodBreakdownItem>,
    val dailyBars: List<DailyBarPoint>,
    val daySections: List<DayLedgerSection>,
    val merchantSuggestions: List<MerchantQuickSuggestion>
)
