package com.kanso.spending.domain.recurring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringDetectorTest {

    private val day = 86_400_000L
    private val now = 1_760_000_000_000L

    @Test
    fun threeMonthlyOccurrenceSameMerchantIsDetected() {
        val base = now - 40 * day
        val expenses = listOf(
            ExpenseRecord("1", "Netflix", 1_200_00L, base, "leisure"),
            ExpenseRecord("2", "Netflix", 1_200_00L, base + 30 * day, "leisure"),
            ExpenseRecord("3", "Netflix", 1_200_00L, base + 60 * day, "leisure")
        )
        val candidates = RecurringDetector.detect(expenses, now)
        assertEquals(1, candidates.size)
        val c = candidates.first()
        assertEquals("netflix", c.merchant.lowercase())
        assertEquals(1_200_00L, c.avgAmountCents)
        assertTrue("interval should be ~30d, got ${c.intervalDays}", c.intervalDays in 28..31)
        assertEquals(3, c.occurrences)
        assertTrue(c.confidence >= 70)
        // Expected next date follows the cadence.
        assertTrue(c.expectedNextMillis > c.lastSeenMillis)
    }

    @Test
    fun twoOccurrencesAreNotEnough() {
        val base = now - 60 * day
        val expenses = listOf(
            ExpenseRecord("1", "Netflix", 1_200_00L, base, "leisure"),
            ExpenseRecord("2", "Netflix", 1_200_00L, base + 30 * day, "leisure")
        )
        assertTrue(RecurringDetector.detect(expenses, now).isEmpty())
    }

    @Test
    fun irregularMerchantCadenceIsNotDetected() {
        // Random gaps (5d, 23d, 41d) — no stable period.
        val base = now - 70 * day
        val expenses = listOf(
            ExpenseRecord("1", "Corner Shop", 500_00L, base, "groceries"),
            ExpenseRecord("2", "Corner Shop", 500_00L, base + 5 * day, "groceries"),
            ExpenseRecord("3", "Corner Shop", 500_00L, base + 28 * day, "groceries"),
            ExpenseRecord("4", "Corner Shop", 500_00L, base + 69 * day, "groceries")
        )
        assertTrue(RecurringDetector.detect(expenses, now).isEmpty())
    }

    @Test
    fun longDormantSubscriptionIsNotSuggested() {
        val base = now - 400 * day
        val expenses = listOf(
            ExpenseRecord("1", "Old Gym", 2_000_00L, base, "wellness"),
            ExpenseRecord("2", "Old Gym", 2_000_00L, base + 30 * day, "wellness"),
            ExpenseRecord("3", "Old Gym", 2_000_00L, base + 60 * day, "wellness")
        )
        assertTrue(RecurringDetector.detect(expenses, now).isEmpty())
    }

    @Test
    fun weeklyInternetBillIsDetected() {
        val base = now - 25 * day
        val expenses = listOf(
            ExpenseRecord("1", "Link3 Internet", 800_00L, base, "bills"),
            ExpenseRecord("2", "Link3 Internet", 800_00L, base + 7 * day, "bills"),
            ExpenseRecord("3", "Link3 Internet", 800_00L, base + 14 * day, "bills"),
            ExpenseRecord("4", "Link3 Internet", 800_00L, base + 21 * day, "bills")
        )
        val c = RecurringDetector.detect(expenses, now).first()
        assertEquals(7, c.intervalDays)
        assertTrue(c.confidence >= 80)
    }

    @Test
    fun merchantNameIsNormalizedSoCasingVariantsGroup() {
        val base = now - 65 * day
        val expenses = listOf(
            ExpenseRecord("1", "Netflix", 1_200_00L, base, "leisure"),
            ExpenseRecord("2", "NETFLIX", 1_200_00L, base + 30 * day, "leisure"),
            ExpenseRecord("3", "netflix.", 1_200_00L, base + 60 * day, "leisure")
        )
        assertEquals(1, RecurringDetector.detect(expenses, now).size)
    }

    // ---- Forecast (estimates, never actual) --------------------------------

    @Test
    fun forecastProducesFutureItemsAndLabelsThemEstimates() {
        val rules = listOf(
            ForecastRule(
                id = "r1", title = "Netflix", amountCents = 1_200_00L,
                intervalDays = 30, expectedNextMillis = now + 5 * day, isActive = true,
                categoryId = "leisure"
            )
        )
        val items = RecurringDetector.forecast(rules, fromMillis = now, days = 35, nowMillis = now)
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.isEstimate })
        assertTrue(items.all { it.dueMillis >= now })
        assertTrue(items.all { it.dueMillis <= now + 35 * day })
    }

    @Test
    fun forecastSkipsOverduePastDatesRatherThanBackfilling() {
        val rules = listOf(
            ForecastRule(
                id = "r1", title = "Gym", amountCents = 2_000_00L,
                intervalDays = 30, expectedNextMillis = now - 90 * day,
                isActive = true, categoryId = "wellness"
            )
        )
        val items = RecurringDetector.forecast(rules, fromMillis = now, days = 40, nowMillis = now)
        // First due date must be in the future, not 3 backdated "missed" items.
        assertTrue(items.all { it.dueMillis >= now })
        assertTrue(items.size <= 2)
    }

    @Test
    fun inactiveRulesProduceNoForecast() {
        val rules = listOf(
            ForecastRule(
                id = "r1", title = "Old", amountCents = 100L, intervalDays = 30,
                expectedNextMillis = now + day, isActive = false, categoryId = null
            )
        )
        assertTrue(RecurringDetector.forecast(rules, now, 60, now).isEmpty())
    }
}
