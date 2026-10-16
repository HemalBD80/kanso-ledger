package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class SpendingAnalyticsEngineTest {

    @Test
    fun `computes monthly totals, safe-to-spend, and category breakdown`() {
        val ym = YearMonth.of(2026, 10)
        val zone = ZoneId.systemDefault()
        val day5Millis = ym.atDay(5).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val day10Millis = ym.atDay(10).atTime(18, 30).atZone(zone).toInstant().toEpochMilli()

        val expenses = listOf(
            ExpenseEntity(
                id = 1L,
                title = "Corner Cafe",
                amountCents = 650L,
                categoryId = SpendingCategory.DINING.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = day5Millis
            ),
            ExpenseEntity(
                id = 2L,
                title = "Neighbourhood Market",
                amountCents = 8450L,
                categoryId = SpendingCategory.GROCERIES.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = day10Millis
            )
        )

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = ym,
            monthExpenses = expenses,
            filteredExpenses = expenses,
            monthlyBudgetCents = 150_000L,
            categoryBudgets = emptyList(),
            today = LocalDate.of(2026, 10, 10),
            zoneId = zone
        )

        assertEquals(9100L, analytics.totalSpentCents)
        assertEquals(140_900L, analytics.remainingBudgetCents)
        assertEquals(BudgetPacingStatus.ON_TRACK, analytics.pacingStatus)
        assertEquals(2, analytics.daySections.size)
        assertEquals(SpendingCategory.GROCERIES, analytics.categoryBreakdown.first().category)
    }

    @Test
    fun `fresh install with zero budget and no expenses shows all zero values`() {
        val ym = YearMonth.of(2026, 10)
        val zone = ZoneId.systemDefault()

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = ym,
            monthExpenses = emptyList(),
            filteredExpenses = emptyList(),
            monthlyBudgetCents = 0L,
            categoryBudgets = emptyList(),
            today = LocalDate.of(2026, 10, 10),
            zoneId = zone
        )

        assertEquals(0L, analytics.totalSpentCents)
        assertEquals(0L, analytics.monthlyBudgetCents)
        assertEquals(0L, analytics.remainingBudgetCents)
        assertEquals(0L, analytics.dailySafeToSpendCents)
        assertEquals(0L, analytics.averageDailyBurnCents)
        assertEquals(0L, analytics.dailyTargetCents)
        assertEquals(BudgetPacingStatus.ON_TRACK, analytics.pacingStatus)
        assertTrue(analytics.daySections.isEmpty())
        assertTrue(analytics.categoryBreakdown.all { it.spentCents == 0L })
    }

    @Test
    fun `zero budget with spending is not over budget sentinel`() {
        val ym = YearMonth.of(2026, 10)
        val zone = ZoneId.systemDefault()
        val day5Millis = ym.atDay(5).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val expenses = listOf(
            ExpenseEntity(
                id = 3L,
                title = "Rickshaw Ride",
                amountCents = 120_00L,
                categoryId = SpendingCategory.TRANSIT.id,
                paymentMethod = PaymentMethod.CASH.id,
                timestampEpochMillis = day5Millis
            )
        )

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = ym,
            monthExpenses = expenses,
            filteredExpenses = expenses,
            monthlyBudgetCents = 0L,
            categoryBudgets = emptyList(),
            today = LocalDate.of(2026, 10, 10),
            zoneId = zone
        )

        assertEquals(120_00L, analytics.totalSpentCents)
        assertEquals(0L, analytics.monthlyBudgetCents)
        assertEquals(-120_00L, analytics.remainingBudgetCents)
        assertEquals(0L, analytics.dailySafeToSpendCents)
        assertEquals(0L, analytics.dailyTargetCents)
    }

    @Test
    fun `formats amounts with the default BDT taka symbol`() {
        assertEquals("৳0.00", CurrencyFormatter.formatCents(0L))
        assertEquals("৳120.00", CurrencyFormatter.formatCents(12_000L))
        assertEquals("-৳3.50", CurrencyFormatter.formatCents(-350L))
        assertEquals("৳1,800", CurrencyFormatter.formatCentsCompact(180_000L))
        assertEquals("৳0", CurrencyFormatter.formatCentsCompact(0L))
    }
}
