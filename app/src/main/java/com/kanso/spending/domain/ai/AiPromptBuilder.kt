package com.kanso.spending.domain.ai

/**
 * Builds the exact JSON payload sent to the model, and the strict schema the
 * model is asked to answer with.
 *
 * Two responsibilities, both pure and testable:
 *  - [redact] removes personal identifiers from the message body *before* it is
 *    sent, so only the financial essentials leave the device;
 *  - [buildUserMessage] frames a single message and demands a machine-readable
 *    JSON answer, so the response can be validated rather than parsed loosely.
 *
 * The system prompt is deliberately explicit that the model must never invent a
 * number: an absent amount must be returned as null, not estimated.
 */
object AiPromptBuilder {

    /** Identifier patterns stripped from outgoing text. */
    private val PHONE = Regex("(?<!\\d)(?:\\+?88)?01[3-9]\\d{8}(?!\\d)")
    private val LONG_DIGITS = Regex("(?<!\\d)\\d{9,}(?!\\d)")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val CARD_LIKE = Regex("(?<!\\d)\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{4}(?!\\d)")

    /**
     * Strip personal identifiers while keeping the financial core intact.
     * Masked hints like "***4512" are preserved (they are already non-identifying
     * and needed for account matching); full phone numbers, emails, long digit
     * runs and card-like numbers are removed.
     */
    fun redact(body: String): String {
        var out = body
        out = CARD_LIKE.replace(out, " ")
        out = PHONE.replace(out, " [redacted] ")
        out = EMAIL.replace(out, " [redacted] ")
        out = LONG_DIGITS.replace(out) { m ->
            // Keep short reference-ish runs; blank out long identifier-like runs.
            if (m.value.length > 12) " [redacted] " else m.value
        }
        return out.replace(Regex("[ \\t]+"), " ").trim()
    }

    const val SYSTEM_PROMPT =
        "You extract personal-finance transactions from bank and mobile-wallet SMS. " +
            "You never guess: if a field is absent from the message, return null for it. " +
            "Never invent an amount, fee, balance, timestamp or reference. " +
            "Answer ONLY with a single JSON object, no prose, no markdown fences."

    /** The strict JSON schema the model must fill in for one message. */
    const val RESPONSE_SCHEMA =
        "{" +
            "\"isFinancial\": boolean, " +
            "\"provider\": string|null, " +
            "\"type\": string|null, " + // MONEY_SENT|MONEY_RECEIVED|PURCHASE|WITHDRAWAL|DEPOSIT|REFUND|FEE|TRANSFER_SELF|BALANCE_ONLY|PROMO
            "\"amount\": string|null, " + // major units, e.g. "1250.00"
            "\"currency\": string|null, " + // "BDT" when stated
            "\"timestamp\": string|null, " + // ISO-8601 as stated
            "\"reference\": string|null, " +
            "\"fee\": string|null, " +
            "\"merchant\": string|null, " +
            "\"accountHint\": string|null, " + // e.g. "***4512"
            "\"reportedBalance\": string|null, " + // advisory only
            "\"direction\": string|null, " + // "credit" | "debit"
            "\"confidence\": number|null, " + // 0..100
            "\"categorySuggestion\": string|null, " +
            "\"warnings\": string[]" +
            "}"

    /** Wrap already-redacted text into the user turn demanding the schema. */
    fun buildUserMessage(redactedBody: String): String =
        "Extract the transaction from this SMS. Return JSON matching exactly this shape:\n" +
            RESPONSE_SCHEMA + "\n" +
            "Message:\n\"\"\"\n$redactedBody\n\"\"\""
}
