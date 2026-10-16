package com.kanso.spending.domain.goals

/**
 * Savings goals and clearly-labelled forecasts. All projections run on
 * historical confirmed data only; every result carries isEstimate=true and is
 * never merged into ledger totals.
 */
data class SavingsGoal(
    val id: String,
    val title: String,
    val targetCents: Long,
    val savedCents: Long,            // from confirmed contributions
    val deadlineMillis: Long?,
    val linkedAccountId: String? = null,
    val createdAtMillis: Long = 0L
) {
    val progressPercent: Int
        get() = if (targetCents <= 0L) 0 else ((savedCents.toDouble() / targetCents) * 100).toInt().coerceIn(0, 100)
    val remainingCents: Long
        get() = (targetCents - savedCents).coerceAtLeast(0L)
}

data class GoalContribution(val goalId: String, val amountCents: Long, val timestampMillis: Long, val accountId: String?)

data class GoalForecast(
    val goalId: String,
    val title: String,
    val targetCents: Long,
    val savedCents: Long,
    /** Projected savings at deadline using recent saving rate — estimate. */
    val projectedSavedCents: Long,
    val projectedAtMillis: Long,
    val onTrack: Boolean,
    val requiredMonthlyCents: Long,
    val isEstimate: Boolean = true
)

data class MonthEndForecast(
    val projectedBalanceCents: Long,
    val currentBalanceCents: Long,
    val avgNetMonthlyCents: Long,
    val daysRemaining: Int,
    val isEstimate: Boolean = true
)

object SavingsGoalEngine {

    /**
     * Progress + projection for one goal. Saving rate uses contributions from
     * the last [windowDays] days (default 90) annualized linearly to deadline.
     */
    fun forecast(
        goal: SavingsGoal,
        contributions: List<GoalContribution>,
        nowMillis: Long,
        windowDays: Int = 90
    ): GoalForecast {
        val own = contributions.filter { it.goalId == goal.id }
        val saved = own.sumOf { it.amountCents }
        val windowStart = nowMillis - windowDays * 86_400_000L
        val recent = own.filter { it.timestampMillis >= windowStart }.sumOf { it.amountCents }
        val ratePerDay = recent.toDouble() / windowDays

        val deadline = goal.deadlineMillis
            ?: (nowMillis + 90L * 86_400_000L)   // default 90-day horizon
        val daysLeft = (((deadline - nowMillis) / 86_400_000L).coerceAtLeast(0L)).toDouble()
        val projected = saved + (ratePerDay * daysLeft).toLong()

        val remaining = (goal.targetCents - saved).coerceAtLeast(0L)
        val requiredMonthly = if (daysLeft > 0) {
            (remaining / (daysLeft / 30.0)).toLong().coerceAtLeast(0L)
        } else remaining

        return GoalForecast(
            goalId = goal.id,
            title = goal.title,
            targetCents = goal.targetCents,
            savedCents = saved,
            projectedSavedCents = projected,
            projectedAtMillis = deadline,
            onTrack = projected >= goal.targetCents,
            requiredMonthlyCents = requiredMonthly
        )
    }

    /**
     * Month-end balance estimate: current balance + average monthly net from
     * the last [months] months of confirmed activity (scaled to remaining days).
     * Clearly an estimate — the caller must label it.
     */
    fun monthEndForecast(
        currentBalanceCents: Long,
        monthlyNetsCents: List<Long>,      // newest last; at least 1 entry
        daysElapsed: Int,
        daysInMonth: Int,
        isEstimate: Boolean = true
    ): MonthEndForecast {
        if (monthlyNetsCents.isEmpty() || daysElapsed <= 0 || daysInMonth <= 0) {
            return MonthEndForecast(currentBalanceCents, currentBalanceCents, 0L, 0, isEstimate)
        }
        val avgNet = (monthlyNetsCents.average()).toLong()
        val daysRemaining = (daysInMonth - daysElapsed).coerceAtLeast(0)
        val projectedDaily = (avgNet.toDouble() / daysInMonth).toLong()
        return MonthEndForecast(
            projectedBalanceCents = currentBalanceCents + projectedDaily * daysRemaining,
            currentBalanceCents = currentBalanceCents,
            avgNetMonthlyCents = avgNet,
            daysRemaining = daysRemaining,
            isEstimate = isEstimate
        )
    }

    /** Total saved across goals (confirmed contributions only). */
    fun totalSaved(goals: List<SavingsGoal>): Long = goals.sumOf { it.savedCents }
}
