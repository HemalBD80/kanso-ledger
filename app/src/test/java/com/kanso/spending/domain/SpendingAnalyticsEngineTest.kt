package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.model.MoneySource
import com.kanso.spending.data.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class SpendingAnalyticsEngineTest {

    private val zone = ZoneId.systemDefault()

    private fun expense(
        id: Long,
        amountCents: Long,
        categoryId: String,
        method: PaymentMethod = PaymentMethod.CASH,
        yearMonth: YearMonth = YearMonth.of(2026, 10),
        day: Int,
        hour: Int = 12
    ): ExpenseEntity {
        val millis = yearMonth.atDay(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return ExpenseEntity(
            id = id,
            title = "Expense $id",
            amountCents = amountCents,
            categoryId = categoryId,
            paymentMethod = method.id,
            timestampEpochMillis = millis
        )
    }

    private fun income(
        id: Long,
        amountCents: Long,
        source: MoneySource = MoneySource.SALARY,
        yearMonth: YearMonth = YearMonth.of(2026, 10),
        day: Int,
        hour: Int = 10
    ): IncomeEntity {
        val millis = yearMonth.atDay(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return IncomeEntity(
            id = id,
            title = "Income $id",
            amountCents = amountCents,
            sourceId = source.id,
            paymentMethod = PaymentMethod.CASH.id,
            timestampEpochMillis = millis
        )
    }

    @Test
    fun `computes monthly totals, money-in, safe-to-spend, and category breakdown`() {
        val ym = YearMonth.of(2026, 10)

        val expenses = listOf(
            expense(1, 650L, "dining", day = 5),
            expense(2, 8_450L, "groceries", method = PaymentMethod.DEBIT_CARD, day = 10)
        )
        val incomes = listOf(income(10, 150_000L, MoneySource.SALARY, day = 1))

        val categoryMap = mapOf(
            "dining" to CategoryDisplayInfo("dining", "Food & Dining", 0xFFC84B31),
            "groceries" to CategoryDisplayInfo("groceries", "Groceries", 0xFF2D6A4F)
        )

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = ym,
            monthExpenses = expenses,
            monthIncomes = incomes,
            filteredEntries = expenses.map(LedgerDisplayEntry::fromExpense) + incomes.map(LedgerDisplayEntry::fromIncome),
            categoryDisplayMap = categoryMap,
            monthlyBudgetCents = 150_000L,
            categoryBudgets = emptyList(),
            today = LocalDate.of(2026, 10, 10),
            zoneId = zone
        )

        assertEquals(9_100L, analytics.totalSpentCents)
        assertEquals(150_000L, analytics.moneyInCents)
        assertEquals(140_900L, analytics.remainingBudgetCents)
        assertEquals(BudgetPacingStatus.ON_TRACK, analytics.pacingStatus)
        assertEquals(3, analytics.daySections.size)
        assertEquals("groceries", analytics.categoryBreakdown.first().categoryId)
        assertEquals(1, analytics.categoryBreakdown.first().transactionCount)
        assertEquals("Food & Dining", analytics.categoryBreakdown.last().displayName)
    }

    @Test
    fun `fresh install with zero budget and no transactions shows all zero values`() {
        val ym = YearMonth.of(2026, 10)

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = ym,
            monthExpenses = emptyList(),
            monthIncomes = emptyList(),
            filteredEntries = emptyList(),
            categoryDisplayMap = emptyMap(),
            monthlyBudgetCents = 0L,
            categoryBudgets = emptyList(),
            today = LocalDate.of(2026, 10, 10),
            zoneId = zone
        )

        assertEquals(0L, analytics.totalSpentCents)
        assertEquals(0L, analytics.moneyInCents)
        assertEquals(0L, analytics.monthlyBudgetCents)
        assertEquals(0L, analytics.remainingBudgetCents)
        assertEquals(0L, analytics.dailySafeToSpendCents)
        assertEquals(0L, analytics.averageDailyBurnCents)
        assertEquals(0L, analytics.dailyTargetCents)
        assertEquals(BudgetPacingStatus.ON_TRACK, analytics.pacingStatus)
        assertTrue(analytics.daySections.isEmpty())
    }

    @Test
    fun `zero budget with spending keeps truthful figures`() {
        val ym = YearMonth.of(2026, 10)
        val expenses = listOf(expense(3, 12_000L, "transit", day = 5))

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = ym,
            monthExpenses = expenses,
            monthIncomes = emptyList(),
            filteredEntries = expenses.map(LedgerDisplayEntry::fromExpense),
            categoryDisplayMap = mapOf("transit" to CategoryDisplayInfo("transit", "Transportation", 0xFF2B5876)),
            monthlyBudgetCents = 0L,
            categoryBudgets = emptyList(),
            today = LocalDate.of(2026, 10, 10),
            zoneId = zone
        )

        assertEquals(12_000L, analytics.totalSpentCents)
        assertEquals(0L, analytics.monthlyBudgetCents)
        assertEquals(-12_000L, analytics.remainingBudgetCents)
        assertEquals(0L, analytics.dailySafeToSpendCents)
        assertEquals(0L, analytics.dailyTargetCents)
        assertTrue(analytics.totalSpentCents > analytics.monthlyBudgetCents)
    }

    @Test
    fun `payment-method-wise breakdown aggregates provider prefixes`() {
        val ym = YearMonth.of(2026, 10)
        val e1 = expense(1, 500L, "dining", method = PaymentMethod.MOBILE_WALLET, day = 3)
        val e2 = expense(2, 700L, "dining", method = PaymentMethod.MOBILE_WALLET, day = 4).copy(paymentProvider = "bKash")
        val e3 = expense(3, 900L, "dining", method = PaymentMethod.CASH, day = 5)

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = ym,
            monthExpenses = listOf(e1, e2, e3),
            monthIncomes = emptyList(),
            filteredEntries = emptyList(),
            categoryDisplayMap = emptyMap(),
            monthlyBudgetCents = 0L,
            categoryBudgets = emptyList(),
            today = LocalDate.of(2026, 10, 10),
            zoneId = zone
        )

        val wallet = analytics.methodBreakdown.first { it.methodId == PaymentMethod.MOBILE_WALLET.id }
        val cash = analytics.methodBreakdown.first { it.methodId == PaymentMethod.CASH.id }
        assertEquals(1_200L, wallet.spentCents)
        assertEquals(2, wallet.transactionCount)
        assertEquals(900L, cash.spentCents)
    }
}
