package com.kanso.spending.domain.ai

/**
 * Optional AI narration over a month's figures for the observations card.
 *
 * STRICT FINANCIAL SAFETY: the income/expense/category numbers are computed
 * locally by [com.kanso.spending.domain.insights.InsightsEngine] and the ledger
 * and are the sole authority. This class does NOT compute, infer or override any
 * figure — it only asks the model to describe numbers the local engine already
 * produced, in plain language, for readability.
 *
 * The prompt explicitly forbids inventing any number that was not supplied, and
 * the reply is length-capped and rejected if it does not come back as usable
 * text. Any failure returns null so the caller simply shows no narrative — the
 * local observations remain fully intact.
 *
 * The transport is injected so the whole path is unit-testable with no network
 * and no credential. Only aggregate totals and category labels are sent — never
 * SMS text, never identifiers, never a raw transaction list.
 */
object AiInsightsNarrator {

    /** Hard cap on the narrative the model may return, in characters. */
    private const val MAX_NARRATIVE_CHARS = 480

    private const val SYSTEM_PROMPT =
        "You write one short, plain-language observation about a person's monthly " +
            "spending, using ONLY the exact figures provided. You never invent, estimate, " +
            "round differently, or introduce any number, percentage or category that was " +
            "not given. If the figures show nothing notable, say so briefly. Two sentences " +
            "maximum. Do not add a heading, markdown, or a sign-off."

    /**
     * Produce a short narrative, or null when the model cannot be reached or the
     * reply is unusable. Never throws.
     */
    fun narrate(
        transport: AiTransport,
        config: AiConfig,
        incomeCents: Long,
        expenseCents: Long,
        topCategories: List<Pair<String, Long>>
    ): String? {
        if (!config.isConfigured) return null

        val categoryLine = topCategories
            .take(3)
            .joinToString("; ") { (name, cents) -> "$name ${formatTaka(cents)}" }
            .ifEmpty { "none recorded" }

        val userMessage = buildString {
            append("Describe this month in 1-2 sentences using ONLY these figures:\n")
            append("Income: ${formatTaka(incomeCents)}\n")
            append("Expenses: ${formatTaka(expenseCents)}\n")
            append("Top categories: $categoryLine")
        }

        val result = transport.sendChat(config, SYSTEM_PROMPT, userMessage)
        if (result !is AiTransportResult.Success) return null

        return sanitize(result.replyText)
    }

    /**
     * Trim, strip any markdown code fences, and enforce the length cap. Returns
     * null for an empty reply. The narrative is plain display text only — it is
     * never parsed into a figure and never touches the ledger.
     */
    private fun sanitize(raw: String?): String? {
        val cleaned = raw
            ?.replace(Regex("```[a-zA-Z]*"), "")   // drop code fences
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (cleaned.length > MAX_NARRATIVE_CHARS) {
            cleaned.take(MAX_NARRATIVE_CHARS).trimEnd() + "…"
        } else {
            cleaned
        }
    }

    /** Minor units → "৳1,234.56" for the prompt. BDT symbol throughout. */
    private fun formatTaka(cents: Long): String {
        val negative = cents < 0
        val abs = kotlin.math.abs(cents)
        val major = abs / 100
        val minor = abs % 100
        val majorStr = major.toString().reversed()
            .chunked(3).joinToString(",").reversed()
        return "${if (negative) "-" else ""}৳$majorStr.${minor.toString().padStart(2, '0')}"
    }
}
