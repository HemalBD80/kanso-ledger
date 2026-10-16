package com.kanso.spending.domain.goals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsGoalEngineTest {

    private val day = 86_400_000L
    private val now = 1_760_000_000_000L

    private val goal = SavingsGoal(
        id = "g1", title = "Trip", targetCents = 100_000L,
        savedCents = 0L, deadlineMillis = now + 90 * day
    )

    @Test
    fun progressUsesConfirmedContributions() {
        val contributions = listOf(
            GoalContribution("g1", 30_000L, now - 10 * day, "acc_cash"),
            GoalContribution("g1", 20_000L, now - 5 * day, "acc_cash"),
            GoalContribution("other", 999_000L, now - 5 * day, "acc_cash") // different goal
        )
        val f = SavingsGoalEngine.forecast(goal, contributions, now)
        assertEquals(50_000L, f.savedCents)
        assertEquals(50, goal.copy(savedCents = f.savedCents).progressPercent)
        assertEquals(50_000L, f.targetCents - f.savedCents)
    }

    @Test
    fun steadySavingProjectsOnTrack() {
        // 10,000 poisha/day for 90 days → ~900,000 projected vs 100,000 target.
        val contributions = (0 until 30).map { i ->
            GoalContribution("g1", 10_000L, now - i * day, "acc_cash")
        }
        val f = SavingsGoalEngine.forecast(goal, contributions, now, windowDays = 30)
        assertTrue(f.isEstimate)
        assertTrue("projected ${f.projectedSavedCents} should exceed target", f.onTrack)
    }

    @Test
    fun noSavingRateProjectsSavedAtCurrentLevel() {
        val contributions = listOf(GoalContribution("g1", 40_000L, now - 200 * day, "acc_cash"))
        val f = SavingsGoalEngine.forecast(goal, contributions, now, windowDays = 90)
        // Nothing saved recently → projection stays at what's already saved.
        assertEquals(40_000L, f.projectedSavedCents)
        assertFalse(f.onTrack)
        assertTrue(f.requiredMonthlyCents > 0)
    }

    @Test
    fun forecastIsNeverTreatedAsActual() {
        val f = SavingsGoalEngine.forecast(goal, emptyList(), now)
        assertTrue(f.isEstimate)
    }

    @Test
    fun monthEndForecastScalesAvgNetToRemainingDays() {
        val f = SavingsGoalEngine.monthEndForecast(
            currentBalanceCents = 100_000L,
            monthlyNetsCents = listOf(30_000L, 30_000L, 30_000L),
            daysElapsed = 16,
            daysInMonth = 31
        )
        assertTrue(f.isEstimate)
        assertEquals(100_000L, f.currentBalanceCents)
        assertEquals(30_000L, f.avgNetMonthlyCents)
        assertEquals(15, f.daysRemaining)
        // avg daily net 30000/31 ≈ 967 × 15 days ≈ 14,516 added.
        assertEquals(100_000L + (30_000L / 31) * 15, f.projectedBalanceCents)
    }

    @Test
    fun monthEndForecastWithNoHistoryReturnsCurrentBalance() {
        val f = SavingsGoalEngine.monthEndForecast(50_000L, emptyList(), 10, 31)
        assertEquals(50_000L, f.projectedBalanceCents)
        assertTrue(f.isEstimate)
    }

    @Test
    fun totalSavedSumsGoals() {
        val goals = listOf(
            SavingsGoal("g1", "A", 100_000L, 25_000L, null),
            SavingsGoal("g2", "B", 200_000L, 30_000L, null)
        )
        assertEquals(55_000L, SavingsGoalEngine.totalSaved(goals))
    }

    @Test
    fun progressClampsAtHundredPercent() {
        val over = SavingsGoal("g", "Over", 10_000L, 15_000L, null)
        assertEquals(100, over.progressPercent)
        assertEquals(0L, over.remainingCents)
    }
}
