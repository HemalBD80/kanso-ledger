package com.kanso.spending.domain.sms

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Deterministic, on-device financial-SMS parser.
 *
 * Pipeline: classify (promo/OTP first) → pick highest-priority sender rule →
 * type by earliest keyword hit → extract amount/fee/balance/ref/time → score
 * confidence → decide ledger action. Ambiguous input is queued for review,
 * never guessed. No message content ever leaves this object.
 */
object SmsParser {

    /** Tie-break order for keyword classification (most specific first). */
    private val TYPE_PRECEDENCE = listOf(
        SmsTxType.REFUND, SmsTxType.FEE, SmsTxType.WITHDRAWAL, SmsTxType.DEPOSIT,
        SmsTxType.PURCHASE, SmsTxType.MONEY_SENT, SmsTxType.MONEY_RECEIVED,
        SmsTxType.TRANSFER_SELF, SmsTxType.BALANCE_ONLY, SmsTxType.PROMO, SmsTxType.UNKNOWN
    )

    private val merchantPatterns = listOf(
        // "at AGORA on 15/10/26 ..." / "at STAR KITCHEN. Ref ..."
        Regex("\\bat\\s+([A-Za-z][A-Za-z0-9&.'-]{1,30}?)(?=\\s+(?:on|for|ref|trx|rn|bal|avl|new|fee|charge)\\b|[.,]|$)", RegexOption.IGNORE_CASE),
        // "to Recharge Center on ..." after amount
        Regex("\\b(?:paid\\s+to|to)\\s+([A-Za-z][A-Za-z0-9&.' -]{2,35}?)(?=\\s+(?:on|via|ref|trx|for)\\b|[.,]|$)", RegexOption.IGNORE_CASE)
    )
    private val counterpartyPatterns = listOf(
        Regex("\\b(?:to|from)\\s+(01[0-9]{9})(?:\\b|\\s)", RegexOption.IGNORE_CASE),
        Regex("\\b(?:to|from)\\s+([A-Za-z][A-Za-z0-9 ]{2,28}?)(?=\\s+(?:on|via|ref|trx|fee|bal|avl|new|for)\\b|[.,]|$)", RegexOption.IGNORE_CASE)
    )

    fun parse(sender: String, body: String, receivedAtMillis: Long): SmsParseResult {
        val text = body.trim()
        val lower = text.lowercase(Locale.ROOT)

        // 1. Non-financial content (OTP/p personal messages) is dropped outright.
        BuiltinSmsRules.NON_FINANCIAL.forEach { pattern ->
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                return SmsParseResult.NotFinancial("matches non-financial pattern '$pattern'")
            }
        }

        // 2. Marketing messages never become transactions.
        val promoHit = BuiltinSmsRules.PROMO_KEYWORDS.firstOrNull { lower.contains(it) }
        if (promoHit != null) {
            return SmsParseResult.NotFinancial("promotional content ('$promoHit')")
        }

        // 3. Provider rule: highest-priority sender match wins; else generic.
        val rule = BuiltinSmsRules.ALL.filter { it.matchesSender(sender) }
            .maxByOrNull { it.priority } ?: BuiltinSmsRules.GENERIC

        // Must at least look financial (currency mention) to continue.
        if (!Regex("\\b(?:BDT|Tk\\.?|taka)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return SmsParseResult.NotFinancial("no currency reference")
        }

        // 4. Type classification: earliest keyword hit wins, so a fee mention
        //    after the main action does not misclassify the whole message.
        //    Equal-position ties resolve by specificity (refund > fee > ... ).
        var type = SmsTxType.UNKNOWN
        var typeHitIndex = Int.MAX_VALUE
        for (candidate in TYPE_PRECEDENCE) {
            for (kw in rule.directionKeywords[candidate].orEmpty()) {
                val idx = lower.indexOf(kw.lowercase(Locale.ROOT))
                if (idx in 0 until typeHitIndex) {
                    typeHitIndex = idx
                    type = candidate
                }
            }
        }
        // Balance-only notices where the only hits are balance keywords.
        if (type == SmsTxType.UNKNOWN) {
            if (BuiltinSmsRules.BALANCE_ONLY_KEYWORDS.any { lower.contains(it) }) {
                type = SmsTxType.BALANCE_ONLY
            }
        }

        // 5. Amount extraction with fee/balance ranges excluded from the
        //    principal amount so "Sent BDT 1,250 ... Fee BDT 5 ... Bal BDT 4,320"
        //    yields principal=1,250, fee=5, balance=4,320.
        val feeRange = firstMatch(rule.feePatterns, text)
        val balanceRange = firstMatch(rule.balancePatterns, text)

        val feeCents = feeRange?.let { parseAmount(it.groupValues[1]) } ?: 0L

        var reportedBalance: Long? = null
        if (balanceRange != null) {
            val label = balanceRange.groupValues[0].lowercase(Locale.ROOT)
            val isCreditLimit = label.contains("available limit")
            if (!isCreditLimit) {
                reportedBalance = parseAmount(balanceRange.groupValues[1])
            } else if (rule.balanceIsLimit) {
                reportedBalance = null // credit-card limit ≠ ledger balance
            }
        }

        val excluded = listOfNotNull(feeRange, balanceRange)
        val amountMatch = rule.amountPatterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(text)
                .firstOrNull { m -> excluded.none { e -> m.range.first in e.range || e.range.first in m.range } }
        }
        var amountCents = amountMatch?.let { parseAmount(it.groupValues[1]) }

        // Standalone fee messages: the fee IS the debit; no principal amount.
        if (type == SmsTxType.FEE && feeCents > 0) {
            amountCents = null
        }

        val reference = firstMatch(rule.referencePatterns, text)?.groupValues?.get(1)
        val accountHint = firstMatch(rule.accountPatterns, text)?.groupValues?.get(1)
        val timestamp = firstMatch(rule.datetimePatterns, text)
            ?.let { parseTimestamp(it.groupValues[1], receivedAtMillis) } ?: receivedAtMillis

        val merchant = extractCounterparty(text, type)

        // 6. Confidence and review decision. A standalone fee IS a debit, so a
        //    parsed fee counts exactly like a parsed principal amount.
        var confidence = 0
        if (amountCents != null || feeCents > 0L) confidence += 45
        if (type != SmsTxType.UNKNOWN) confidence += 25
        if (timestamp != receivedAtMillis) confidence += 10
        if (!reference.isNullOrBlank()) confidence += 10
        if (!merchant.isNullOrBlank()) confidence += 5
        if (reportedBalance != null) confidence += 5
        confidence = confidence.coerceAtMost(100)

        val reason: String? = when {
            type == SmsTxType.BALANCE_ONLY || type == SmsTxType.PROMO -> null
            amountCents == null && feeCents == 0L -> "no amount found"
            type == SmsTxType.UNKNOWN -> "transaction type unclear"
            type == SmsTxType.TRANSFER_SELF -> "possible transfer between own accounts"
            confidence < 65 -> "low confidence ($confidence%)"
            else -> null
        }

        val action = when (type) {
            SmsTxType.BALANCE_ONLY, SmsTxType.PROMO -> LedgerAction.IGNORE
            SmsTxType.MONEY_SENT, SmsTxType.PURCHASE -> LedgerAction.RECORD_EXPENSE
            SmsTxType.MONEY_RECEIVED, SmsTxType.REFUND -> LedgerAction.RECORD_INCOME
            SmsTxType.WITHDRAWAL, SmsTxType.DEPOSIT, SmsTxType.TRANSFER_SELF -> LedgerAction.RECORD_TRANSFER
            SmsTxType.FEE -> LedgerAction.RECORD_FEE
            SmsTxType.UNKNOWN -> LedgerAction.REVIEW_ONLY
        }

        val needsReview = when {
            type == SmsTxType.BALANCE_ONLY -> false
            reason != null -> true
            action == LedgerAction.REVIEW_ONLY -> true
            amountCents == null && feeCents == 0L -> true
            else -> false
        }

        // A balance-only notice still counts as "processed" for idempotency.
        val effectiveType = type
        val fingerprint = fingerprint(
            provider = rule.provider,
            sender = sender,
            reference = reference,
            amountCents = amountCents ?: feeCents,
            type = effectiveType,
            timestampMillis = timestamp
        )

        if (type == SmsTxType.BALANCE_ONLY && action == LedgerAction.IGNORE) {
            return SmsParseResult.Financial(
                ParsedSmsTransaction(
                    provider = rule.provider,
                    type = type,
                    action = action,
                    amountCents = null,
                    feeCents = 0L,
                    timestampMillis = timestamp,
                    merchantOrCounterparty = "",
                    referenceId = reference,
                    accountHint = accountHint,
                    reportedBalanceCents = reportedBalance,
                    reportedDirection = null,
                    confidence = confidence,
                    needsReview = false,
                    reviewReason = null,
                    fingerprint = fingerprint
                )
            )
        }

        if (needsReview && type == SmsTxType.UNKNOWN && amountCents == null) {
            return SmsParseResult.Ambiguous(
                provider = rule.provider,
                reason = reason ?: "unrecognised financial message",
                fingerprint = fingerprint
            )
        }

        return SmsParseResult.Financial(
            ParsedSmsTransaction(
                provider = rule.provider,
                type = type,
                action = action,
                amountCents = amountCents,
                feeCents = feeCents,
                timestampMillis = timestamp,
                merchantOrCounterparty = merchant ?: "",
                referenceId = reference,
                accountHint = accountHint,
                reportedBalanceCents = reportedBalance,
                reportedDirection = null,
                confidence = confidence,
                needsReview = needsReview,
                reviewReason = reason,
                fingerprint = fingerprint
            )
        )
    }

    /**
     * Idempotency key. Reference id when available (two legitimate same-amount
     * transactions get distinct refs); otherwise a composite of sender, amount,
     * second-precision time and type — never amount alone.
     */
    fun fingerprint(
        provider: String,
        sender: String,
        reference: String?,
        amountCents: Long,
        type: SmsTxType,
        timestampMillis: Long
    ): String {
        val raw = if (!reference.isNullOrBlank()) {
            "$provider|ref|${reference.uppercase(Locale.ROOT)}"
        } else {
            "$provider|${sender.trim().lowercase(Locale.ROOT)}|$amountCents|$type|${timestampMillis / 1000}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** "1,250.00" → 125000 minor units (poisha). */
    fun parseAmount(raw: String): Long? {
        val cleaned = raw.replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.contains('.')) {
            val parts = cleaned.split(".")
            val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
            val frac = parts[1].padEnd(2, '0').take(2)
            whole * 100 + (frac.toLongOrNull() ?: return null)
        } else {
            val whole = cleaned.toLongOrNull() ?: return null
            whole * 100
        }
    }

    private fun firstMatch(patterns: List<String>, text: String): MatchResult? =
        patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).find(text)
        }

    private fun extractCounterparty(text: String, type: SmsTxType): String? {
        val patterns = if (type == SmsTxType.PURCHASE || type == SmsTxType.DEPOSIT) merchantPatterns else counterpartyPatterns
        for (p in patterns) {
            val hit = p.find(text)?.groupValues?.get(1)?.trim()
            if (!hit.isNullOrBlank()) return hit
        }
        // Fall back to the other family so purchases still get a merchant label.
        for (p in (merchantPatterns + counterpartyPatterns)) {
            val hit = p.find(text)?.groupValues?.get(1)?.trim()
            if (!hit.isNullOrBlank()) return hit
        }
        return null
    }

    private val timeFormats = listOf(
        "dd/MM/yy HH:mm:ss", "dd/MM/yy HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
        "d/M/yy HH:mm", "d/M/yyyy HH:mm",
        "dd MMM yyyy h:mm a", "dd MMM yyyy hh:mm a", "dd MMM yyyy HH:mm",
        "d MMM yyyy h:mm a", "d MMM yyyy HH:mm"
    )

    private fun parseTimestamp(raw: String, fallback: Long): Long {
        val candidate = raw.trim()
        for (pattern in timeFormats) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.ENGLISH)
                fmt.isLenient = false
                return fmt.parse(candidate)?.time ?: fallback
            } catch (_: Exception) {
                // try next format
            }
        }
        return fallback
    }
}
