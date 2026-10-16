package com.kanso.spending.domain.insights

import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsEngineTest {

    private fun base(
        currentExpense: Long = 0L,
        prevExpense: Long = 0L,
        income: Long = 0L,
        fees: Long = 0L,
        catCurrent: Map<String, Long> = emptyMap(),
        catPrev: Map<String, Long> = emptyMap(),
        budgets: Map<String, Long> = emptyMap(),
        daysElapsed: Int = 15,
        pending: Int = 0,
        warnings: Int = 0
    ) = InsightInput(
        currentMonthIncomeCents = income,
        currentMonthExpenseCents = currentExpense,
        previousMonthExpenseCents = prevExpense,
        currentMonthFeesCents = fees,
        perCategoryCurrentCents = catCurrent,
        perCategoryPreviousCents = catPrev,
        budgets = budgets,
        largestExpenseCents = 0L,
        largestExpenseMerchant = null,
        dayCountInMonth = 31,
        daysElapsedInMonth = daysElapsed,
        accountReconciliationWarnings = warnings,
        pendingReviewCount = pending
    )

    @Test
    fun categoryIncreaseOverThresholdIsFlagged() {
        val insights = InsightsEngine.compute(
            base(catCurrent = mapOf("dining" to 40_000L), catPrev = mapOf("dining" to 20_000L))
        )
        assertTrue(insights.any { it.kind == "category-mom-dining" && it.severity == InsightSeverity.WARNING })
    }

    @Test
    fun tinyCategoryChangeIsNotFlagged() {
        val insights = InsightsEngine.compute(
            base(catCurrent = mapOf("dining" to 20_500L), catPrev = mapOf("dining" to 20_000L))
        )
        assertTrue(insights.none { it.kind.startsWith("category-mom-") })
    }

    @Test
    fun budgetNearLimitWarnsAtEightyPercent() {
        val insights = InsightsEngine.compute(
            base(budgets = mapOf("dining" to 50_000L), catCurrent = mapOf("dining" to 42_000L))
        )
        assertTrue(insights.any { it.kind == "budget-near-dining" })
    }

    @Test
    fun budgetOverLimitWarns() {
        val insights = InsightsEngine.compute(
            base(budgets = mapOf("dining" to 50_000L), catCurrent = mapOf("dining" to 55_000L))
        )
        assertTrue(insights.any { it.kind == "budget-over-dining" })
    }

    @Test
    fun monthEndProjectionIsLabelledEstimate() {
        val insights = InsightsEngine.compute(
            base(currentExpense = 100_000L, daysElapsed = 10)
        )
        val forecast = insights.firstOrNull { it.kind == "month-forecast" }
        assertTrue(forecast != null && forecast.isEstimate && forecast.text.contains("estimate", true))
    }

    @Test
    fun feesOverThresholdAreSurfaced() {
        val insights = InsightsEngine.compute(base(fees = 800L))
        assertTrue(insights.any { it.kind == "fees" })
    }

    @Test
    fun reconciliationAndPendingWarningsSurface() {
        val insights = InsightsEngine.compute(base(warnings = 2, pending = 3))
        assertTrue(insights.any { it.kind == "reconcile" })
        assertTrue(insights.any { it.kind == "pending-review" })
    }

    @Test
    fun surplusIsRecognisedAsGood() {
        val insights = InsightsEngine.compute(
            base(income = 500_000L, currentExpense = 200_000L, prevExpense = 350_000L)
        )
        assertTrue(insights.any { it.kind == "surplus" && it.severity == InsightSeverity.GOOD })
    }

    @Test
    fun nothingInventsDataWhenLedgerIsEmpty() {
        val insights = InsightsEngine.compute(base())
        // Empty month → only genuinely zero-derived observations (none).
        assertTrue(insights.isEmpty())
    }

    @Test
    fun largestExpenseCalloutCitesRealRecord() {
        val input = base().copy(largestExpenseCents = 25_000L, largestExpenseMerchant = "Agora")
        val insights = InsightsEngine.compute(input)
        assertTrue(insights.any { it.kind == "largest" && it.text.contains("Agora") })
    }
}
