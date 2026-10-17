package com.kanso.spending.domain.sms

/**
 * Data-driven detection rule. Rules are additive and ordered by priority, so a
 * new provider or message format can be supported by adding one rule — no
 * parser rewrite required.
 *
 * Matching strategy:
 *  - [senderPatterns] gate which messages a rule applies to (empty = any sender)
 *  - keyword sets classify direction/type ("sent", "credited", ...)
 *  - amount/fee/balance patterns are generic with per-rule overrides
 */
data class SmsParsingRule(
    val id: String,
    val provider: String,
    val senderPatterns: List<String>,          // regexes matched against the SMS address
    val priority: Int = 50,                    // higher wins for provider labelling
    val directionKeywords: Map<SmsTxType, List<String>> = emptyMap(),
    val amountPatterns: List<String> = DEFAULT_AMOUNT_PATTERNS,
    val feePatterns: List<String> = DEFAULT_FEE_PATTERNS,
    val balancePatterns: List<String> = DEFAULT_BALANCE_PATTERNS,
    val referencePatterns: List<String> = DEFAULT_REFERENCE_PATTERNS,
    val accountPatterns: List<String> = DEFAULT_ACCOUNT_PATTERNS,
    val datetimePatterns: List<String> = emptyMapPatterns(),
    val balanceIsLimit: Boolean = false,       // credit cards: "available limit" ≠ spendable balance
    val ignorePatterns: List<String> = DEFAULT_IGNORE_PATTERNS
) {
    fun matchesSender(sender: String): Boolean =
        senderPatterns.isEmpty() || senderPatterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(sender) }

    companion object {
        // Money number: thousands may or may not be comma-separated, so both
        // "9,528.95" and "9528.95" parse whole (a leading-digit run, optional
        // comma groups, optional 2-digit paisha).
        private const val MONEY = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?"

        // "BDT 1,250.00" / "Tk 500" / "Tk 9528.95" / plain "1,250.00 taka"
        val DEFAULT_AMOUNT_PATTERNS = listOf(
            "(?:BDT|Tk\\.?|BDT\\s*|Tk\\s*)($MONEY)",
            "($MONEY)\\s*(?:BDT|Tk\\.?|taka)",
            "(?:amount\\s*(?:of)?\\s*[:\\-]?\\s*)($MONEY)"
        )
        val DEFAULT_FEE_PATTERNS = listOf(
            "(?:fee|charge|charges|charged|commission)\\s*(?:is|of)?\\s*[:\\-]?\\s*(?:BDT|Tk\\.?)?\\s*($MONEY)"
        )
        val DEFAULT_BALANCE_PATTERNS = listOf(
            "(?:new\\s+balance|avl\\.?\\s*bal(?:ance)?\\.?|available\\s+balance|available\\s+limit|balance\\s*(?:is)?\\s*[:\\-]?|bal\\s*[:\\-]?)\\s*(?:BDT|Tk\\.?)?\\s*($MONEY)"
        )
        val DEFAULT_REFERENCE_PATTERNS = listOf(
            "(?:TrxID|Ref(?:erence)?|TxnID|RefNo|RN)\\s*[:#\\-]?\\s*([A-Za-z0-9]{4,})",
            // "Transaction ID is 000319000740" — leading zeros are preserved
            // because the capture is kept as a String, never parsed as a number.
            "(?:Transaction|Txn)\\s*(?:ID|Id|id|No|Number)\\s*(?:is|[:#\\-])?\\s*([A-Za-z0-9]{4,})"
        )
        val DEFAULT_ACCOUNT_PATTERNS = listOf(
            // Keep masked leading '*' (e.g. "***4512") in the captured hint.
            "(?:A\\/c|Acct|Account|Card|AC)\\s*(?:no\\.?|number)?\\s*[:#\\-]?\\s*([*0-9][0-9*]{1,})"
        )
        val DEFAULT_IGNORE_PATTERNS = listOf(
            "t&c\\s*apply",
            "terms\\s+and\\s+conditions",
            "download\\s+our\\s+app",
            "win\\s+(?:a|the)\\s+",
            "offer\\s+valid"
        )

        fun emptyMapPatterns(): List<String> = listOf(
            "([0-9]{1,2}[/-][0-9]{1,2}[/-](?:20)?[0-9]{2}\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?\\s*(?:AM|PM|am|pm)?)",
            "([0-9]{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(?:20)?[0-9]{2}\\s+[0-9]{1,2}:[0-9]{2}\\s*(?:AM|PM|am|pm)?)"
        )
    }
}

/**
 * Built-in rules for Bangladeshi providers. Patterns are intentionally
 * keyword-driven rather than whole-message templates, so minor wording
 * variations still parse. All example messages in tests are fictional.
 */
object BuiltinSmsRules {

    val BKASH = SmsParsingRule(
        id = "builtin_bkash",
        provider = "bKash",
        senderPatterns = listOf("^\\s*bKash\\b", "01715\\d{6}"),
        priority = 90,
        directionKeywords = mapOf(
            SmsTxType.MONEY_SENT to listOf("have sent", "you have sent", "sent bdt", "payment sent", "send money"),
            SmsTxType.MONEY_RECEIVED to listOf("have received", "you have received", "payment received", "received bdt"),
            SmsTxType.PURCHASE to listOf(
                "payment to", "merchant payment", "spent",
                // bill payments and recharges are purchases (money out)
                "recharge", "bill payment", "electricity bill", "internet package"
            ),
            SmsTxType.WITHDRAWAL to listOf("cash out", "withdrawn", "withdraw"),
            SmsTxType.FEE to listOf("fee", "charge"),
            SmsTxType.BALANCE_ONLY to listOf("current balance", "available balance is", "balance is"),
            SmsTxType.REFUND to listOf("refund", "returned")
        )
    )

    val NAGAD = SmsParsingRule(
        id = "builtin_nagad",
        provider = "Nagad",
        senderPatterns = listOf("^\\s*Nagad\\b", "0161\\d{6}"),
        priority = 90,
        directionKeywords = mapOf(
            SmsTxType.MONEY_SENT to listOf("withdrawn", "sent to", "paid to", "payment of"),
            SmsTxType.MONEY_RECEIVED to listOf("received", "credited"),
            SmsTxType.PURCHASE to listOf(
                "purchase", "spent", "merchant",
                "recharge", "bill payment", "electricity bill", "internet package"
            ),
            SmsTxType.WITHDRAWAL to listOf("withdrawn", "cash out"),
            SmsTxType.FEE to listOf("fee", "charge"),
            SmsTxType.BALANCE_ONLY to listOf("avl bal", "available balance", "current balance"),
            SmsTxType.REFUND to listOf("refund", "returned")
        )
    )

    val ROCKET = SmsParsingRule(
        id = "builtin_rocket",
        provider = "Rocket",
        senderPatterns = listOf("^\\s*Rocket\\b", "DBBL", "0191\\d{6}"),
        priority = 90,
        directionKeywords = mapOf(
            SmsTxType.MONEY_SENT to listOf("sent to", "you have sent", "paid to"),
            SmsTxType.MONEY_RECEIVED to listOf("received", "credited"),
            SmsTxType.PURCHASE to listOf(
                "spent", "purchase", "merchant payment",
                "recharge", "bill payment", "electricity bill", "internet package"
            ),
            SmsTxType.WITHDRAWAL to listOf("withdrawn", "cash out"),
            SmsTxType.FEE to listOf("fee", "charge"),
            SmsTxType.BALANCE_ONLY to listOf("new balance", "available balance", "current balance"),
            SmsTxType.REFUND to listOf("refund", "returned")
        )
    )

    val BANK = SmsParsingRule(
        id = "builtin_bank",
        provider = "Bank",
        senderPatterns = listOf("\\bBANK\\b", "\\bBRAC\\b", "\\bEBL\\b", "\\bCITY\\b", "\\bDBBL\\b", "\\bISLAMIC\\b", "^\\s*8424\\d*\\s*$"),
        priority = 70,
        directionKeywords = mapOf(
            SmsTxType.MONEY_SENT to listOf("debited", "paid to", "transferred to", "sent to"),
            SmsTxType.MONEY_RECEIVED to listOf("credited", "deposited", "received"),
            SmsTxType.PURCHASE to listOf(
                "spent on", "used for", "purchase at", "pos purchase",
                "recharge", "bill payment", "electricity bill", "utility bill", "internet package"
            ),
            SmsTxType.WITHDRAWAL to listOf("withdrawn", "atm withdrawal", "cash withdrawal"),
            SmsTxType.DEPOSIT to listOf("deposited", "cash deposit"),
            SmsTxType.FEE to listOf("maintenance fee", "sms fee", "account fee", "charge debited", "fee debited"),
            SmsTxType.BALANCE_ONLY to listOf("balance is", "available balance", "avl bal"),
            SmsTxType.REFUND to listOf("refund", "reversed", "returned")
        )
    )

    val CARD = SmsParsingRule(
        id = "builtin_card",
        provider = "Card",
        senderPatterns = listOf("\\bVISA\\b", "\\bMASTER\\b", "CARD", "01719\\d{6}"),
        priority = 80,
        balanceIsLimit = true,
        directionKeywords = mapOf(
            SmsTxType.PURCHASE to listOf("used for", "spent", "purchase", "pos", "txn at"),
            SmsTxType.MONEY_SENT to listOf("debited", "transferred"),
            SmsTxType.MONEY_RECEIVED to listOf("credited", "refund"),
            SmsTxType.FEE to listOf("annual fee", "interest", "charge"),
            SmsTxType.BALANCE_ONLY to listOf("available limit", "outstanding", "balance is"),
            SmsTxType.REFUND to listOf("refund", "reversed", "credited back")
        )
    )

    /** Fallback applied when no sender-specific rule matches but content looks financial. */
    val GENERIC = SmsParsingRule(
        id = "builtin_generic",
        provider = "SMS",
        senderPatterns = emptyList(),
        priority = 10,
        directionKeywords = mapOf(
            SmsTxType.MONEY_SENT to listOf("sent", "paid", "debited", "withdrawn", "transferred"),
            SmsTxType.MONEY_RECEIVED to listOf("received", "credited", "deposited"),
            SmsTxType.PURCHASE to listOf(
                "spent", "purchase", "bought",
                "recharge", "bill payment", "electricity bill", "utility bill", "internet package"
            ),
            SmsTxType.REFUND to listOf("refund", "reversed"),
            SmsTxType.FEE to listOf("fee", "charge"),
            SmsTxType.BALANCE_ONLY to listOf("balance is", "current balance", "available balance")
        )
    )

    /** Marketing/OTP fingerprints — never transactions. */
    val NON_FINANCIAL = listOf(
        "verification code",
        "otp",
            "your pin",
        "one.?time password",
        "login code",
        "confirm your",
        "click the link"
    )

    val ALL: List<SmsParsingRule> = listOf(BKASH, NAGAD, ROCKET, CARD, BANK, GENERIC)

    /** Keywords that mark a message as a pure balance notice. */
    val BALANCE_ONLY_KEYWORDS = listOf(
        "balance is", "current balance", "avl bal", "available balance", "your balance",
        "account balance", "available limit", "outstanding balance"
    )

    /** Words that make a message promotional even if an amount is present. */
    val PROMO_KEYWORDS = listOf(
        "cashback offer", "win a", "congratulations", "offer valid", "t&c apply",
        "terms and conditions", "download", "use code", "discount", "recharge and get",
        "earn extra", "refer and earn"
    )
}
