package com.kanso.spending.data.model

enum class PaymentMethod(
    val id: String,
    val label: String,
    val badgeCode: String
) {
    CARD(id = "card", label = "Card", badgeCode = "CRD"),
    CASH(id = "cash", label = "Cash", badgeCode = "CSH"),
    TRANSFER(id = "transfer", label = "Instant / UPI", badgeCode = "TRF"),
    WALLET(id = "wallet", label = "Digital Wallet", badgeCode = "WLT");

    companion object {
        fun fromId(id: String): PaymentMethod {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CARD
        }
    }
}
