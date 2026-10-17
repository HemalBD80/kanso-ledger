package com.kanso.spending.domain.ai

import com.kanso.spending.data.model.PaymentMethod

/**
 * Maps the model's free-form `payment_method_suggestion` onto a real
 * [PaymentMethod] the ledger already supports.
 *
 * DESIGN RULE — the AI is the LOWEST-priority signal here too:
 *
 *  1. The repository consults this only when the resolved account does not
 *     already determine the method, and before the passive provider heuristics.
 *  2. It can only ever return a real [PaymentMethod]. An unknown phrase, a blank
 *     string, or a suggestion that does not map cleanly returns null, and the
 *     caller falls back to the provider heuristic / "other". The model therefore
 *     cannot invent a payment method the ledger does not have.
 *
 * It never changes an account, an amount, or a balance — it is a display hint.
 */
object AiPaymentMethodMapper {

    /** Lowercased fragment → payment-method id. Order matters (most specific first). */
    private val keywordToMethod: List<Pair<String, String>> = listOf(
        "credit card" to PaymentMethod.CREDIT_CARD.id,
        "credit" to PaymentMethod.CREDIT_CARD.id,
        "debit card" to PaymentMethod.DEBIT_CARD.id,
        "debit" to PaymentMethod.DEBIT_CARD.id,
        "bkash" to PaymentMethod.MOBILE_WALLET.id,
        "nagad" to PaymentMethod.MOBILE_WALLET.id,
        "rocket" to PaymentMethod.MOBILE_WALLET.id,
        "upay" to PaymentMethod.MOBILE_WALLET.id,
        "mobile wallet" to PaymentMethod.MOBILE_WALLET.id,
        "mfs" to PaymentMethod.MOBILE_WALLET.id,
        "wallet" to PaymentMethod.MOBILE_WALLET.id,
        "bank transfer" to PaymentMethod.BANK_TRANSFER.id,
        "neft" to PaymentMethod.BANK_TRANSFER.id,
        "beftn" to PaymentMethod.BANK_TRANSFER.id,
        "rtgs" to PaymentMethod.BANK_TRANSFER.id,
        "bank" to PaymentMethod.BANK_TRANSFER.id,
        "cash" to PaymentMethod.CASH.id
    )

    /**
     * Map a model suggestion to a real payment-method id, or null when it cannot
     * be safely resolved. Never throws; an empty/blank suggestion is null.
     */
    fun toMethodId(rawSuggestion: String?): String? {
        val text = rawSuggestion?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null

        // Direct id hit (the model sometimes echoes "wallet"/"debit" verbatim).
        PaymentMethod.entries.firstOrNull { it.id == text }?.let { return it.id }

        // Longest matching keyword wins so "credit card" beats bare "credit".
        val hit = keywordToMethod
            .filter { text.contains(it.first) }
            .maxByOrNull { it.first.length }
            ?: return null

        return PaymentMethod.entries.firstOrNull { it.id == hit.second }?.id
    }
}
