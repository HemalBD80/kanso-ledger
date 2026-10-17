package com.kanso.spending.data.model

/**
 * Sources of money-in entries. `refund` entries count exclusively as money
 * added — never also as a reduction of expenses — so a refunded purchase does
 * not double-count in the ledger.
 */
enum class MoneySource(
    val id: String,
    val label: String
) {
    INITIAL_BALANCE(id = "initial_balance", label = "Initial Balance"),
    SALARY(id = "salary", label = "Salary / Income"),
    FREELANCE(id = "freelance", label = "Freelance Earnings"),
    GIFT(id = "gift", label = "Gift"),
    CASHBACK(id = "cashback", label = "Cashback / Reward"),
    REFUND(id = "refund", label = "Refund"),
    TRANSFER_IN(id = "transfer_in", label = "Transfer Received"),
    OTHER(id = "other", label = "Other");

    companion object {
        fun fromId(id: String): MoneySource =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OTHER
    }
}
