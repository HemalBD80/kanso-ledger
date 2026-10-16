package com.kanso.spending.domain.ai

/**
 * The strict structure GLM 5.3 Flash must return for one financial message.
 *
 * This is a *proposal* from the model — never trusted on its own. Every field is
 * validated locally by [AiResponseValidator] before it can reach the ledger, and
 * the model has no way to express a database operation, a balance overwrite, or
 * an arbitrary instruction. Unknown/extra fields in the raw JSON are ignored.
 *
 * Amounts are expressed exactly as the provider states them (major units, e.g.
 * "1250.00") so validation can compare them against what was actually sent; the
 * ledger itself keeps integer poisha, converted only after validation.
 */
data class AiTransactionProposal(
    /** True when the message describes a financial transaction at all. */
    val isFinancial: Boolean,
    /** Raw provider name as extracted, e.g. "bKash". */
    val provider: String?,
    /** Transaction kind, as a string; mapped to the ledger's enum after validation. */
    val type: String?,
    /** Amount in major units (BDT taka), e.g. "1250.00". */
    val amount: String?,
    /** ISO-4217 currency code. Only "BDT" is accepted for the ledger. */
    val currency: String?,
    /** ISO-8601 timestamp stated in the message, if any. */
    val timestamp: String?,
    /** Provider transaction reference, if stated. */
    val reference: String?,
    /** Fee in major units, if stated. */
    val fee: String?,
    /** Merchant or counterparty name, if stated. */
    val merchant: String?,
    /** Masked account hint, e.g. "***4512". */
    val accountHint: String?,
    /** Balance the provider reports, in major units. Advisory only — never authoritative. */
    val reportedBalance: String?,
    /** "credit"|"debit"|"in"|"out" | null, when the provider states direction explicitly. */
    val direction: String?,
    /**
     * Model's own confidence. NOTE: GLM 5.3 Flash returns this on a **0..1**
     * scale (e.g. 0.95), not 0..100. Kept as a Double so the validator can
     * normalise either scale; treated as a hint, never a fact.
     */
    val confidence: Double?,
    /** Category suggestion, if the model offers one. Never overrides user categories. */
    val categorySuggestion: String?,
    /** Anything the model flags as uncertain, conflicting, or incomplete. */
    val warnings: List<String>?
)
