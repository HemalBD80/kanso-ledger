package com.kanso.spending.domain.recurring

/**
 * Detect likely recurring payments from confirmed expense history.
 *
 * A candidate requires ≥3 occurrences of the same merchant (or amount) with a
 * regular cadence (±5 days around a 7/14/28/30/31-day period). Detection only
 * proposes — the user must confirm before any rule exists, and forecasts are
 * never counted as actual payments.
 */
data class ExpenseRecord(
    val id: String,
    val merchant: String,
    val amountCents: Long,
    val timestampMillis: Long,
    val categoryId: String?,
    val accountHint: String? = null
)

data class RecurringCandidate(
    val id: String,                 // stable hash for UI keys
    val merchant: String,
    val avgAmountCents: Long,
    val intervalDays: Int,
    val occurrences: Int,
    val lastSeenMillis: Long,
    val expectedNextMillis: Long,
    val categoryId: String?,
    val accountHint: String?,
    val confidence: Int             // 0..100
)

object RecurringDetector {

    private const val MIN_OCCURRENCES = 3

    fun detect(expenses: List<ExpenseRecord>, nowMillis: Long): List<RecurringCandidate> {
        val grouped = expenses
            .filter { it.merchant.isNotBlank() }
            .groupBy { normalizeKey(it.merchant) }

        val candidates = mutableListOf<RecurringCandidate>()
        for ((_, group) in grouped) {
            val sorted = group.sortedBy { it.timestampMillis }
            if (sorted.size < MIN_OCCURRENCES) continue

            val gaps = sorted.zipWithNext { a, b ->
                (b.timestampMillis - a.timestampMillis) / 86_400_000L
            }.filter { it in 1..90 }
            if (gaps.size < MIN_OCCURRENCES - 1) continue

            val median = gaps.sorted()[gaps.size / 2]
            val interval = listOf(7, 14, 21, 28, 30, 31, 60, 90)
                .minByOrNull { kotlin.math.abs(it - median) } ?: continue
            val tolerance = if (interval <= 7) 2 else 5
            val aligned = gaps.count { kotlin.math.abs(it - interval) <= tolerance }
            if (aligned < gaps.size - 1) continue   // allow one schedule shift

            val last = sorted.last()
            val expected = last.timestampMillis + interval * 86_400_000L
            // Only suggest still-active subscriptions (last seen ≤ 2 cycles ago).
            if (nowMillis - last.timestampMillis > interval * 2 * 86_400_000L + 3L * 86_400_000L) continue

            val amountSpread = sorted.map { it.amountCents }
            val min = amountSpread.min()
            val max = amountSpread.max()
            val stable = max - min <= max / 5 || max - min <= 500L
            val confidence = when {
                sorted.size >= 5 && stable -> 90
                sorted.size >= 4 && stable -> 80
                stable -> 70
                else -> 55
            }

            candidates += RecurringCandidate(
                id = "rec_${normalizeKey(last.merchant)}_$interval",
                merchant = sorted.last().merchant,
                avgAmountCents = amountSpread.average().toLong(),
                intervalDays = interval,
                occurrences = sorted.size,
                lastSeenMillis = last.timestampMillis,
                expectedNextMillis = expected,
                categoryId = sorted.last().categoryId,
                accountHint = sorted.last().accountHint,
                confidence = confidence
            )
        }
        return candidates.sortedByDescending { it.confidence }
    }

    /**
     * Forecast list for the next [days] days — labelled ESTIMATE everywhere
     * downstream. Uses confirmed history only; never mutates the ledger.
     */
    fun forecast(
        rules: List<ForecastRule>,
        fromMillis: Long,
        days: Int,
        nowMillis: Long
    ): List<ForecastItem> {
        val items = mutableListOf<ForecastItem>()
        val end = fromMillis + days * 86_400_000L
        for (rule in rules.filter { it.isActive }) {
            var next = rule.expectedNextMillis
            // Fast-forward past dates already paid relative to "now".
            var guard = 0
            while (next < nowMillis && guard < 400) {
                next += rule.intervalDays * 86_400_000L
                guard++
            }
            while (next <= end && guard < 400) {
                if (next >= nowMillis) {
                    items += ForecastItem(
                        ruleId = rule.id,
                        title = rule.title,
                        amountCents = rule.amountCents,
                        dueMillis = next,
                        categoryId = rule.categoryId,
                        accountHint = rule.accountHint
                    )
                }
                next += rule.intervalDays * 86_400_000L
                guard++
            }
        }
        return items.sortedBy { it.dueMillis }
    }

    private fun normalizeKey(merchant: String): String =
        merchant.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}

data class ForecastRule(
    val id: String,
    val title: String,
    val amountCents: Long,
    val intervalDays: Int,
    val expectedNextMillis: Long,
    val isActive: Boolean,
    val categoryId: String?,
    val accountHint: String? = null
)

data class ForecastItem(
    val ruleId: String,
    val title: String,
    val amountCents: Long,
    val dueMillis: Long,
    val categoryId: String?,
    val accountHint: String? = null
) {
    // Explicit label: forecasts must never be presented as actual payments.
    val isEstimate: Boolean get() = true
}
