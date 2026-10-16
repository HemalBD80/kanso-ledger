package com.kanso.spending.data.model

/**
 * Payment methods supported by the ledger. The database stores the stable [id]
 * string; [fromId] keeps legacy ids ("card" from earlier releases) resolving to
 * the closest modern method so historical rows never break.
 */
enum class PaymentMethod(
    val id: String,
    val label: String,
    val badgeCode: String
) {
    CASH(id = "cash", label = "Cash", badgeCode = "CSH"),
    DEBIT_CARD(id = "debit", label = "Debit Card", badgeCode = "DBT"),
    CREDIT_CARD(id = "credit", label = "Credit Card", badgeCode = "CRT"),
    MOBILE_WALLET(id = "wallet", label = "Mobile Wallet (bKash / Nagad / Rocket)", badgeCode = "WLT"),
    BANK_TRANSFER(id = "transfer", label = "Bank Transfer", badgeCode = "BNK"),
    OTHER(id = "other", label = "Other", badgeCode = "OTH");

    companion object {
        fun fromId(id: String): PaymentMethod {
            return resolveId(id)
        }

        /**
         * Legacy ids: "card" (single card entry in v1.0) resolves to Debit Card;
         * unknown or blank ids fall back to OTHER so nothing crashes.
         */
        fun resolveId(id: String): PaymentMethod {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: if (id.equals("card", ignoreCase = true)) DEBIT_CARD else OTHER
        }
    }
}
