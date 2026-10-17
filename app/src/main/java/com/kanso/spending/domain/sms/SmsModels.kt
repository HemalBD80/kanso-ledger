package com.kanso.spending.domain.sms

/**
 * Transaction types recognised inside financial SMS messages. Parsing is
 * deterministic: rules first, review when ambiguous — never guessing.
 */
enum class SmsTxType {
    MONEY_SENT,       // wallet/bank send
    MONEY_RECEIVED,   // credit to the user
    PURCHASE,         // card purchase at a merchant
    WITHDRAWAL,       // ATM / cash-out (a transfer to cash, not an expense)
    DEPOSIT,          // cash deposit into an account
    CASHBACK,         // reward/bonus credited back — incoming money, NOT a refund
    REFUND,           // refund of a previous payment (explicitly linked when known)
    REVERSAL,         // reversal/chargeback of a previous transaction — never new income
    FEE,              // standalone fee/charge debited
    TRANSFER_SELF,    // user's own account-to-account transfer (best-effort)
    BALANCE_ONLY,     // balance inquiry/notice — never a transaction
    PROMO,            // marketing message — ignored
    UNKNOWN           // recognised as financial but ambiguous → review
}

/** What the ledger should do with a parsed message. */
enum class LedgerAction {
    RECORD_EXPENSE,
    RECORD_INCOME,
    RECORD_TRANSFER,
    RECORD_FEE,
    REVIEW_ONLY,
    IGNORE
}

/**
 * Everything the parser could extract from one SMS. All amounts are minor
 * units (poisha) — the ledger's integer discipline.
 */
data class ParsedSmsTransaction(
    val provider: String,
    val type: SmsTxType,
    val action: LedgerAction,
    val amountCents: Long?,
    val feeCents: Long,
    val timestampMillis: Long,
    val merchantOrCounterparty: String,
    val referenceId: String?,
    val accountHint: String?,
    /** Provider-reported available balance, minor units, if present. */
    val reportedBalanceCents: Long?,
    /** "credit" or "debit" when the provider states it explicitly. */
    val reportedDirection: String?,
    val confidence: Int,          // 0..100
    val needsReview: Boolean,
    val reviewReason: String?,
    /** Stable fingerprint for idempotent de-duplication. */
    val fingerprint: String,
    /**
     * Optional category hint from the AI path only, used as the LOWEST-priority
     * signal by the repository (after user rules and bundled keywords). It is a
     * free-form string; the repository maps it to a real bundled category id via
     * [com.kanso.spending.domain.ai.AiCategoryMapper] and discards anything that
     * does not resolve. Null for every locally-parsed message.
     */
    val aiCategorySuggestion: String? = null,
    /**
     * Optional payment-method hint from the AI path only. Mapped to a real
     * [com.kanso.spending.data.model.PaymentMethod] by the repository via
     * [com.kanso.spending.domain.ai.AiPaymentMethodMapper]; used only when the
     * resolved account does not already determine the method. Null unless AI ran.
     */
    val aiPaymentMethodSuggestion: String? = null
)

/** Result of parsing one message: recognised financial content or not. */
sealed class SmsParseResult {
    data class Financial(val transaction: ParsedSmsTransaction) : SmsParseResult()
    /** Financial wording detected but nothing usable — queued for review. */
    data class Ambiguous(val provider: String, val reason: String, val fingerprint: String) : SmsParseResult()
    /** Not a financial message (promo spam, personal chat, ...). */
    data class NotFinancial(val reason: String) : SmsParseResult()
}
