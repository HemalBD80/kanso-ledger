package com.kanso.spending.domain.ai

import com.kanso.spending.domain.sms.LedgerAction
import com.kanso.spending.domain.sms.ParsedSmsTransaction
import com.kanso.spending.domain.sms.SmsParser
import com.kanso.spending.domain.sms.SmsTxType
import java.util.Locale

/** Outcome of validating one model proposal against the ledger's rules. */
sealed class AiValidationResult {
    /** A fully-validated transaction that can enter the existing ingest planner. */
    data class Accepted(val transaction: ParsedSmsTransaction) : AiValidationResult()

    /**
     * The model said this is not a financial message. Treated as "ignore" by the
     * caller — never a transaction, never a guess.
     */
    data class NotFinancial(val reason: String) : AiValidationResult()

    /** The proposal was malformed, contradictory, or unsafe → queue for review. */
    data class Rejected(val reason: String) : AiValidationResult()
}

/**
 * Pure, local validation of an untrusted model proposal.
 *
 * The model is never trusted. It cannot execute a database operation, write a
 * balance, or express an instruction — it can only propose field values, and
 * every one of them is checked here before the ledger can see it. Rules:
 *
 *  - amounts must parse, be positive, and fit the ledger's poisha range;
 *  - only BDT is accepted (the ledger is single-currency); an explicit foreign
 *    currency is rejected rather than silently converted;
 *  - a missing amount or type is NEVER guessed — it queues for review;
 *  - a provider-reported balance is advisory only and can never overwrite a
 *    calculated balance (it is carried through for reconciliation, nothing more);
 *  - the fingerprint is computed with the same [SmsParser.fingerprint] the local
 *    parser uses, so a message parsed by AI and by the local parser dedups to
 *    one record, never two.
 */
object AiResponseValidator {

    /** Confidence at or above which an unambiguous transaction may auto-commit. */
    const val AUTO_COMMIT_CONFIDENCE = 85

    private val ALLOWED_CURRENCIES = setOf("BDT", "BDT.", "৳", "TK", "TAKA")

    /** Used when neither the model nor the sender yields a provider name. */
    private const val UNKNOWN_PROVIDER = "unknown"

    /**
     * Directions GLM 5.3 Flash actually emits. Observed: "debit" and "in" (it
     * says "in", not "credit"). Anything outside this set queues for review.
     */
    private val KNOWN_DIRECTIONS = setOf("credit", "debit", "in", "out")

    fun validate(
        proposal: AiTransactionProposal,
        sender: String,
        receivedAtMillis: Long
    ): AiValidationResult {
        if (!proposal.isFinancial) {
            return AiValidationResult.NotFinancial(proposal.modelReasoningOr("Model marked the message non-financial"))
        }

        // The model may set is_financial=true while calling the type
        // "non_financial"; honour the more specific signal rather than guessing.
        if (proposal.type?.trim()?.let { normalizeType(it) } == "NON_FINANCIAL") {
            return AiValidationResult.NotFinancial("Model typed the message non-financial.")
        }

        // GLM 5.3 Flash returns provider: null even for clearly financial messages
        // (it honors "never guess"). The sender is a reliable, already-known
        // fallback, so a null provider must NOT reject a correct extraction.
        val provider = proposal.provider?.trim()?.takeIf { it.isNotEmpty() }
            ?: sender.trim().takeIf { it.isNotEmpty() }
            ?: UNKNOWN_PROVIDER

        // Currency: only the ledger's own BDT is accepted. An explicitly foreign
        // currency is rejected — never converted, never assumed.
        val currency = proposal.currency?.trim().orEmpty()
        if (currency.isNotEmpty() && currency.uppercase(Locale.ROOT) !in ALLOWED_CURRENCIES) {
            return AiValidationResult.Rejected("Unsupported currency '$currency' — the ledger records BDT only.")
        }

        val type = mapType(proposal.type)
        if (type == SmsTxType.UNKNOWN) {
            return AiValidationResult.Rejected("Transaction type was missing or unrecognised.")
        }

        // Balance-only and promo messages carry no amount and must never invent one.
        if (type == SmsTxType.BALANCE_ONLY || type == SmsTxType.PROMO) {
            val parsed = buildParsed(
                proposal = proposal, provider = provider, type = type,
                amountCents = null, feeCents = 0L, sender = sender,
                receivedAtMillis = receivedAtMillis, needsReview = false,
                reviewReason = null,
                confidencePct = normalizeConfidence(proposal.confidence)
            )
            return AiValidationResult.Accepted(parsed)
        }

        val amountCents = proposal.amount?.let { SmsParser.parseAmount(it) }
        if (amountCents == null) {
            return AiValidationResult.Rejected("Amount was missing or unparseable — never guessed.")
        }
        if (amountCents <= 0L) {
            return AiValidationResult.Rejected("Amount must be positive.")
        }

        val feeCents = proposal.fee?.let { SmsParser.parseAmount(it) } ?: 0L
        if (feeCents < 0L) {
            return AiValidationResult.Rejected("Fee cannot be negative.")
        }

        // Ambiguity the model itself flagged, or a low confidence, always queues.
        val warnings = proposal.warnings.orEmpty().filter { it.isNotBlank() }
        val confidencePct = normalizeConfidence(proposal.confidence)
        val direction = proposal.direction?.trim()?.lowercase(Locale.ROOT)

        val needsReview = when {
            type == SmsTxType.REVERSAL -> true
            warnings.isNotEmpty() -> true
            confidencePct < AUTO_COMMIT_CONFIDENCE -> true
            direction != null && direction !in KNOWN_DIRECTIONS -> true
            else -> false
        }
        val reason = when {
            type == SmsTxType.REVERSAL -> "Reversal — match it to the original transaction before recording."
            warnings.isNotEmpty() -> "Model flagged: ${warnings.joinToString("; ")}"
            confidencePct < AUTO_COMMIT_CONFIDENCE -> "Confidence $confidencePct is below the auto-commit threshold."
            else -> null
        }

        val parsed = buildParsed(
            proposal = proposal, provider = provider, type = type,
            amountCents = amountCents, feeCents = feeCents, sender = sender,
            receivedAtMillis = receivedAtMillis, needsReview = needsReview,
            reviewReason = reason,
            confidencePct = confidencePct
        )
        return AiValidationResult.Accepted(parsed)
    }

    /**
     * GLM 5.3 Flash reports confidence on a 0..1 scale (observed 0.9 / 0.95 /
     * 0.97). Other providers may use 0..100. Values in (0,1] are treated as
     * fractions and scaled to percent; anything above 1 is already percent.
     */
    fun normalizeConfidence(raw: Double?): Int {
        val v = raw ?: return 0
        val pct = if (v in 0.0..1.0) v * 100.0 else v
        return pct.toInt().coerceIn(0, 100)
    }

    private fun buildParsed(
        proposal: AiTransactionProposal,
        provider: String,
        type: SmsTxType,
        amountCents: Long?,
        feeCents: Long,
        sender: String,
        receivedAtMillis: Long,
        needsReview: Boolean,
        reviewReason: String?,
        confidencePct: Int
    ): ParsedSmsTransaction {
        val reference = proposal.reference?.trim()?.takeIf { it.isNotEmpty() }
        // Evidence is advisory context for the reviewer; it never changes a value.
        val evidence = proposal.evidence?.trim()?.takeIf { it.isNotEmpty() }
        val finalReason = listOfNotNull(reviewReason, evidence?.let { "Evidence: $it" })
            .joinToString(" · ")
            .ifBlank { null }
        // Reuse the local parser's fingerprint so AI and local parses of the same
        // message collapse to a single record — dedup is never amount-only.
        val fingerprint = SmsParser.fingerprint(
            provider = provider,
            sender = sender,
            reference = reference,
            amountCents = amountCents ?: 0L,
            type = type,
            timestampMillis = receivedAtMillis
        )
        return ParsedSmsTransaction(
            provider = provider,
            type = type,
            action = actionFor(type, needsReview),
            amountCents = amountCents,
            feeCents = feeCents,
            timestampMillis = receivedAtMillis,
            merchantOrCounterparty = proposal.merchant?.trim().orEmpty(),
            referenceId = reference,
            accountHint = proposal.accountHint?.trim()?.takeIf { it.isNotEmpty() },
            reportedBalanceCents = proposal.reportedBalance?.let { SmsParser.parseAmount(it) },
            reportedDirection = proposal.direction?.trim(),
            confidence = confidencePct,
            needsReview = needsReview,
            reviewReason = finalReason,
            fingerprint = fingerprint,
            aiCategorySuggestion = proposal.categorySuggestion?.trim()?.takeIf { it.isNotEmpty() },
            aiPaymentMethodSuggestion = proposal.paymentMethodSuggestion?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Map a validated type to the ledger's action, preserving financial
     * integrity: a self-transfer is a transfer (never income or expense), a
     * standalone fee is a fee (recorded exactly once, separately), and anything
     * unambiguous enough to auto-commit gets the concrete record action. The AI
     * never chooses these — the local policy does.
     */
    private fun actionFor(type: SmsTxType, needsReview: Boolean): LedgerAction {
        if (needsReview) return LedgerAction.REVIEW_ONLY
        return when (type) {
            SmsTxType.BALANCE_ONLY, SmsTxType.PROMO -> LedgerAction.IGNORE
            SmsTxType.UNKNOWN, SmsTxType.REVERSAL -> LedgerAction.REVIEW_ONLY
            SmsTxType.MONEY_SENT, SmsTxType.PURCHASE -> LedgerAction.RECORD_EXPENSE
            // Cashback is incoming money; a refund is incoming money with an
            // explicit refund source. Neither is ever an expense reduction.
            SmsTxType.MONEY_RECEIVED, SmsTxType.CASHBACK, SmsTxType.REFUND -> LedgerAction.RECORD_INCOME
            // Withdrawals and deposits move cash, and self-transfers move money
            // between the user's own accounts — none is income or expense.
            SmsTxType.WITHDRAWAL, SmsTxType.DEPOSIT, SmsTxType.TRANSFER_SELF -> LedgerAction.RECORD_TRANSFER
            SmsTxType.FEE -> LedgerAction.RECORD_FEE
        }
    }

    /** Fold spaces/hyphens/underscores to a single separator and upper-case. */
    private fun normalizeType(raw: String): String = raw.trim()
        .uppercase(Locale.ROOT)
        .replace(Regex("[\\s\\-]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')

    /**
     * Lenient string→enum mapping; anything unrecognised becomes UNKNOWN.
     *
     * Normalisation is deliberately generous because GLM 5.3 Flash emits types
     * inconsistently — observed live: "send_money", "received", "debit_card_
     * purchase", "ATM withdrawal" (with a space), "fee", "refund",
     * "balance_inquiry". The v2.4 vocabulary adds expense, income, cashback,
     * reversal, cash_withdrawal, cash_deposit and balance_notification. A bare
     * "transfer" is treated as a self-transfer (neutral) so an account-to-account
     * move is never miscounted as income or expense.
     */
    private fun mapType(raw: String?): SmsTxType {
        val key = raw?.let { normalizeType(it) }.orEmpty()
        return when (key) {
            "EXPENSE", "DEBIT", "PURCHASE", "PAYMENT", "SPEND", "SPENT",
            "CARD_PURCHASE", "DEBIT_CARD_PURCHASE", "CARD_SPEND", "POINT_OF_SALE", "POS",
            "BILL_PAYMENT", "RECHARGE", "MOBILE_RECHARGE" -> SmsTxType.PURCHASE
            "MONEY_SENT", "SENT", "SEND", "SEND_MONEY", "MONEY_TRANSFER", "TRANSFER_OUT", "OUT" -> SmsTxType.MONEY_SENT
            "INCOME", "MONEY_RECEIVED", "RECEIVED", "RECEIVE", "MONEY_IN", "CREDIT",
            "TRANSFER_IN", "IN", "DEPOSIT_IN" -> SmsTxType.MONEY_RECEIVED
            "CASHBACK", "CASH_BACK", "REWARD", "REWARDS", "BONUS", "REWARD_POINTS" -> SmsTxType.CASHBACK
            "REFUND", "REFUNDED", "RETURNED" -> SmsTxType.REFUND
            "REVERSAL", "REVERSED", "CHARGEBACK", "CHARGE_BACK" -> SmsTxType.REVERSAL
            "WITHDRAWAL", "CASH_WITHDRAWAL", "CASH_OUT", "CASHOUT", "ATM", "ATM_WITHDRAWAL", "ATM_CASH_OUT" -> SmsTxType.WITHDRAWAL
            "DEPOSIT", "CASH_DEPOSIT", "CASH_IN", "CASHIN" -> SmsTxType.DEPOSIT
            "FEE", "CHARGE", "SERVICE_FEE", "TRANSACTION_FEE", "FEE_CHARGED" -> SmsTxType.FEE
            "TRANSFER", "TRANSFER_SELF", "SELF_TRANSFER", "OWN_TRANSFER", "BETWEEN_OWN_ACCOUNTS" -> SmsTxType.TRANSFER_SELF
            "BALANCE_ONLY", "BALANCE", "BALANCE_NOTICE", "BALANCE_NOTIFICATION",
            "BALANCE_INQUIRY", "BALANCE_ENQUIRY", "ACCOUNT_BALANCE" -> SmsTxType.BALANCE_ONLY
            "PROMO", "MARKETING", "OFFER", "PROMOTION" -> SmsTxType.PROMO
            else -> SmsTxType.UNKNOWN
        }
    }
}

/** Small helper so a missing rationale still reads sensibly in review. */
private fun AiTransactionProposal.modelReasoningOr(fallback: String): String = fallback
