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
    REFUND,           // refund of a previous payment
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
    val fingerprint: String
)

/** Result of parsing one message: recognised financial content or not. */
sealed class SmsParseResult {
    data class Financial(val transaction: ParsedSmsTransaction) : SmsParseResult()
    /** Financial wording detected but nothing usable — queued for review. */
    data class Ambiguous(val provider: String, val reason: String, val fingerprint: String) : SmsParseResult()
    /** Not a financial message (promo spam, personal chat, ...). */
    data class NotFinancial(val reason: String) : SmsParseResult()
}
