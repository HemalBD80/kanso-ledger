package com.kanso.spending.domain.insights

/**
 * Observations derived strictly from recorded data. Nothing is invented: each
 * item cites the comparison or threshold that produced it. Estimates are
 * labelled as such and excluded from confirmed totals.
 */
data class InsightInput(
    val currentMonthIncomeCents: Long,
    val currentMonthExpenseCents: Long,
    val previousMonthExpenseCents: Long,
    val currentMonthFeesCents: Long,
    val perCategoryCurrentCents: Map<String, Long>,
    val perCategoryPreviousCents: Map<String, Long>,
    val budgets: Map<String, Long>,            // categoryId → budget cents
    val largestExpenseCents: Long,
    val largestExpenseMerchant: String?,
    val dayCountInMonth: Int,
    val daysElapsedInMonth: Int,
    val accountReconciliationWarnings: Int,
    val pendingReviewCount: Int,
    val unusualDayCents: Long? = null,          // a day > 2× 30-day average day
    val unusualDayLabel: String? = null
)

enum class InsightSeverity { INFO, GOOD, WARNING }

data class Insight(
    val text: String,
    val severity: InsightSeverity,
    val kind: String,       // stable machine tag
    val isEstimate: Boolean = false
)

object InsightsEngine {

    fun compute(input: InsightInput): List<Insight> {
        val out = mutableListOf<Insight>()

        // 1. Month-over-month category increase (needs ≥100 taka change to matter).
        for ((cat, cur) in input.perCategoryCurrentCents) {
            val prev = input.perCategoryPreviousCents[cat] ?: 0L
            if (prev > 0L && cur > prev) {
                val delta = cur - prev
                if (delta >= 10_000L) {
                    val pct = ((delta.toDouble() / prev) * 100).toInt()
                    out += Insight(
                        text = "Spending on this category is up ৳${fmt(delta)} (${pct}%) versus last month.",
                        severity = InsightSeverity.WARNING,
                        kind = "category-mom-$cat"
                    )
                }
            }
        }

        // 2. Budget pressure at 80% and 100%.
        for ((cat, budget) in input.budgets) {
            if (budget <= 0L) continue
            val spent = input.perCategoryCurrentCents[cat] ?: 0L
            val pct = (spent.toDouble() / budget * 100).toInt()
            when {
                pct >= 100 -> out += Insight(
                    text = "Budget exceeded for this category by ৳${fmt(spent - budget)}.",
                    severity = InsightSeverity.WARNING, kind = "budget-over-$cat"
                )
                pct >= 80 -> out += Insight(
                    text = "Approaching your budget limit: ${pct}% used, ৳${fmt(budget - spent)} left.",
                    severity = InsightSeverity.WARNING, kind = "budget-near-$cat"
                )
            }
        }

        // 3. Run-rate estimate for month-end (clearly labelled).
        if (input.daysElapsedInMonth in 1 until input.dayCountInMonth) {
            val projected = (input.currentMonthExpenseCents.toDouble() /
                input.daysElapsedInMonth * input.dayCountInMonth).toLong()
            if (projected > input.currentMonthExpenseCents + 5_000L) {
                out += Insight(
                    text = "At this pace, month-end spending could reach ৳${fmt(projected)} (estimate).",
                    severity = InsightSeverity.INFO, kind = "month-forecast", isEstimate = true
                )
            }
        }

        // 4. Fee awareness.
        if (input.currentMonthFeesCents >= 500L) {
            out += Insight(
                text = "You've paid ৳${fmt(input.currentMonthFeesCents)} in transaction fees this month.",
                severity = InsightSeverity.INFO, kind = "fees"
            )
        }

        // 5. Largest expense callout.
        if (input.largestExpenseCents >= 5_000L && input.largestExpenseMerchant != null) {
            out += Insight(
                text = "Largest expense: ৳${fmt(input.largestExpenseCents)} at ${input.largestExpenseMerchant}.",
                severity = InsightSeverity.INFO, kind = "largest"
            )
        }

        // 6. Unusual spending day (>2× average) — flagged from real data only.
        if (input.unusualDayCents != null && input.unusualDayLabel != null) {
            out += Insight(
                text = "Unusual spending on ${input.unusualDayLabel}: ৳${fmt(input.unusualDayCents)}.",
                severity = InsightSeverity.WARNING, kind = "unusual-day"
            )
        }

        // 7. Operational warnings surfaced on the dashboard.
        if (input.accountReconciliationWarnings > 0) {
            out += Insight(
                text = "${input.accountReconciliationWarnings} account balance(s) differ from provider reports. Review reconciliation.",
                severity = InsightSeverity.WARNING, kind = "reconcile"
            )
        }
        if (input.pendingReviewCount > 0) {
            out += Insight(
                text = "${input.pendingReviewCount} imported transaction(s) are waiting for your review.",
                severity = InsightSeverity.INFO, kind = "pending-review"
            )
        }

        // 8. Positive income signal.
        if (input.currentMonthIncomeCents > 0L &&
            input.currentMonthIncomeCents > input.currentMonthExpenseCents
        ) {
            out += Insight(
                text = "Income exceeds spending by ৳${fmt(input.currentMonthIncomeCents - input.currentMonthExpenseCents)} this month.",
                severity = InsightSeverity.GOOD, kind = "surplus"
            )
        }

        return out.take(8)
    }

    /** Minor units → "1,250.00" (symbol rendered by the UI). */
    private fun fmt(cents: Long): String {
        val neg = cents < 0
        val abs = kotlin.math.abs(cents)
        val whole = abs / 100
        val frac = abs % 100
        val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + grouped + "." + frac.toString().padStart(2, '0')
    }
}
