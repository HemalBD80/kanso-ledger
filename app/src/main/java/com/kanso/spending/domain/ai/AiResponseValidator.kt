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

    fun validate(
        proposal: AiTransactionProposal,
        sender: String,
        receivedAtMillis: Long
    ): AiValidationResult {
        if (!proposal.isFinancial) {
            return AiValidationResult.NotFinancial(proposal.modelReasoningOr("Model marked the message non-financial"))
        }

        val provider = proposal.provider?.trim().orEmpty()
        if (provider.isEmpty()) {
            return AiValidationResult.Rejected("No provider could be identified.")
        }

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
                reviewReason = null
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
        val modelConfidence = (proposal.confidence ?: 0).coerceIn(0, 100)
        val direction = proposal.direction?.trim()?.lowercase(Locale.ROOT)

        val needsReview = when {
            warnings.isNotEmpty() -> true
            modelConfidence < AUTO_COMMIT_CONFIDENCE -> true
            direction != null && direction != "credit" && direction != "debit" -> true
            else -> false
        }
        val reason = when {
            warnings.isNotEmpty() -> "Model flagged: ${warnings.joinToString("; ")}"
            modelConfidence < AUTO_COMMIT_CONFIDENCE -> "Confidence $modelConfidence is below the auto-commit threshold."
            else -> null
        }

        val parsed = buildParsed(
            proposal = proposal, provider = provider, type = type,
            amountCents = amountCents, feeCents = feeCents, sender = sender,
            receivedAtMillis = receivedAtMillis, needsReview = needsReview,
            reviewReason = reason
        )
        return AiValidationResult.Accepted(parsed)
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
        reviewReason: String?
    ): ParsedSmsTransaction {
        val reference = proposal.reference?.trim()?.takeIf { it.isNotEmpty() }
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
            confidence = (proposal.confidence ?: 0).coerceIn(0, 100),
            needsReview = needsReview,
            reviewReason = reviewReason,
            fingerprint = fingerprint
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
            SmsTxType.UNKNOWN -> LedgerAction.REVIEW_ONLY
            SmsTxType.MONEY_SENT, SmsTxType.PURCHASE, SmsTxType.WITHDRAWAL -> LedgerAction.RECORD_EXPENSE
            SmsTxType.MONEY_RECEIVED, SmsTxType.DEPOSIT -> LedgerAction.RECORD_INCOME
            SmsTxType.REFUND -> LedgerAction.RECORD_INCOME
            SmsTxType.TRANSFER_SELF -> LedgerAction.RECORD_TRANSFER
            SmsTxType.FEE -> LedgerAction.RECORD_FEE
        }
    }

    /** Lenient string→enum mapping; anything unrecognised becomes UNKNOWN. */
    private fun mapType(raw: String?): SmsTxType {
        val key = raw?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_').orEmpty()
        return when (key) {
            "MONEY_SENT", "SENT", "SEND", "PAYMENT", "TRANSFER_OUT" -> SmsTxType.MONEY_SENT
            "MONEY_RECEIVED", "RECEIVED", "RECEIVE", "CREDIT", "TRANSFER_IN" -> SmsTxType.MONEY_RECEIVED
            "PURCHASE", "DEBIT", "SPEND", "CARD_PURCHASE" -> SmsTxType.PURCHASE
            "WITHDRAWAL", "CASH_OUT", "CASHOUT", "ATM" -> SmsTxType.WITHDRAWAL
            "DEPOSIT", "CASH_IN", "CASHIN" -> SmsTxType.DEPOSIT
            "REFUND", "REVERSAL", "CHARGEBACK" -> SmsTxType.REFUND
            "FEE", "CHARGE", "SERVICE_FEE" -> SmsTxType.FEE
            "TRANSFER_SELF", "SELF_TRANSFER", "OWN_TRANSFER" -> SmsTxType.TRANSFER_SELF
            "BALANCE_ONLY", "BALANCE", "BALANCE_NOTICE" -> SmsTxType.BALANCE_ONLY
            "PROMO", "MARKETING", "OFFER" -> SmsTxType.PROMO
            else -> SmsTxType.UNKNOWN
        }
    }
}

/** Small helper so a missing rationale still reads sensibly in review. */
private fun AiTransactionProposal.modelReasoningOr(fallback: String): String = fallback
