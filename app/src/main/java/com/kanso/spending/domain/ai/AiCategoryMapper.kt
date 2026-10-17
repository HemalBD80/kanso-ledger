package com.kanso.spending.domain.ai

import com.kanso.spending.data.model.SpendingCategory

/**
 * Maps the model's free-form `categorySuggestion` string onto a category id
 * that actually exists in the user's ledger.
 *
 * DESIGN RULE — the AI is the LOWEST-priority signal, never the highest:
 *
 *  1. This mapper is consulted only after the deterministic path (user rules →
 *     bundled keywords) produced nothing. A user rule or a bundled keyword hit
 *     always wins; the model can never override what the user already taught
 *     the app.
 *  2. It can only ever return an id from the real, bundled category set (the
 *     [SpendingCategory] enum plus the "bills" bucket). A suggestion that does
 *     not map cleanly ("salary", "gift", "crypto", an unknown word, an empty
 *     string) returns null and the caller falls back to the local "other"
 *     default. The model therefore cannot invent a new category, and cannot
 *     push a transaction into a category the user never had.
 *
 * This keeps categorisation a *suggestion the user can override* while making
 * the AI incapable of corrupting the category taxonomy.
 */
object AiCategoryMapper {

    /** The ledger's "Bills & Utilities" bucket. Mobile/internet map here. */
    private const val BILLS_ID = "bills"

    /** Every id this mapper may ever return: the enum set plus the bills bucket. */
    private val ALLOWED_IDS: Set<String> =
        SpendingCategory.entries.map { it.id }.toSet() + BILLS_ID

    /** Lowercased keyword/phrase fragments → bundled category id. */
    private val keywordToCategory: Map<String, String> = linkedMapOf(
        // dining & coffee
        "restaurant" to SpendingCategory.DINING.id,
        "cafe" to SpendingCategory.DINING.id,
        "coffee" to SpendingCategory.DINING.id,
        "dining" to SpendingCategory.DINING.id,
        "food" to SpendingCategory.DINING.id,
        "meal" to SpendingCategory.DINING.id,
        "eat" to SpendingCategory.DINING.id,
        "bakery" to SpendingCategory.DINING.id,
        "kfc" to SpendingCategory.DINING.id,
        "pizza" to SpendingCategory.DINING.id,
        "burger" to SpendingCategory.DINING.id,
        // groceries & market
        "grocer" to SpendingCategory.GROCERIES.id,
        "supermarket" to SpendingCategory.GROCERIES.id,
        "market" to SpendingCategory.GROCERIES.id,
        "bazar" to SpendingCategory.GROCERIES.id,
        "provision" to SpendingCategory.GROCERIES.id,
        "greengrocer" to SpendingCategory.GROCERIES.id,
        // transit & fuel
        "transport" to SpendingCategory.TRANSIT.id,
        "transit" to SpendingCategory.TRANSIT.id,
        "fuel" to SpendingCategory.TRANSIT.id,
        "petrol" to SpendingCategory.TRANSIT.id,
        "rideshare" to SpendingCategory.TRANSIT.id,
        "taxi" to SpendingCategory.TRANSIT.id,
        "bus" to SpendingCategory.TRANSIT.id,
        "train" to SpendingCategory.TRANSIT.id,
        "rickshaw" to SpendingCategory.TRANSIT.id,
        "ticket" to SpendingCategory.TRANSIT.id,
        "travel" to SpendingCategory.TRANSIT.id,
        // housing & utilities
        "housing" to SpendingCategory.HOUSING.id,
        "rent" to SpendingCategory.HOUSING.id,
        "electric" to SpendingCategory.HOUSING.id,
        "gas bill" to SpendingCategory.HOUSING.id,
        "water" to SpendingCategory.HOUSING.id,
        "maintenance" to SpendingCategory.HOUSING.id,
        // mobile & internet → the ledger's "Bills & Utilities" bucket
        "bill" to BILLS_ID,
        "utility" to BILLS_ID,
        "internet" to BILLS_ID,
        "broadband" to BILLS_ID,
        "wifi" to BILLS_ID,
        "mobile" to BILLS_ID,
        "recharge" to BILLS_ID,
        "top up" to BILLS_ID,
        "topup" to BILLS_ID,
        "data pack" to BILLS_ID,
        "data package" to BILLS_ID,
        "telecom" to BILLS_ID,
        // shopping & goods
        "shopping" to SpendingCategory.SHOPPING.id,
        "retail" to SpendingCategory.SHOPPING.id,
        "apparel" to SpendingCategory.SHOPPING.id,
        "clothing" to SpendingCategory.SHOPPING.id,
        "mall" to SpendingCategory.SHOPPING.id,
        "store" to SpendingCategory.SHOPPING.id,
        "ecommerce" to SpendingCategory.SHOPPING.id,
        "online store" to SpendingCategory.SHOPPING.id,
        // subscriptions & digital
        "subscription" to SpendingCategory.SUBSCRIPTIONS.id,
        "streaming" to SpendingCategory.SUBSCRIPTIONS.id,
        "software" to SpendingCategory.SUBSCRIPTIONS.id,
        "saas" to SpendingCategory.SUBSCRIPTIONS.id,
        "membership" to SpendingCategory.SUBSCRIPTIONS.id,
        "digital service" to SpendingCategory.SUBSCRIPTIONS.id,
        // wellness & health
        "health" to SpendingCategory.WELLNESS.id,
        "medical" to SpendingCategory.WELLNESS.id,
        "pharmacy" to SpendingCategory.WELLNESS.id,
        "hospital" to SpendingCategory.WELLNESS.id,
        "clinic" to SpendingCategory.WELLNESS.id,
        "doctor" to SpendingCategory.WELLNESS.id,
        "medicine" to SpendingCategory.WELLNESS.id,
        "diagnostic" to SpendingCategory.WELLNESS.id,
        "fitness" to SpendingCategory.WELLNESS.id,
        "gym" to SpendingCategory.WELLNESS.id,
        // leisure & culture
        "leisure" to SpendingCategory.LEISURE.id,
        "entertainment" to SpendingCategory.LEISURE.id,
        "cinema" to SpendingCategory.LEISURE.id,
        "movie" to SpendingCategory.LEISURE.id,
        "game" to SpendingCategory.LEISURE.id,
        "book" to SpendingCategory.LEISURE.id,
        "culture" to SpendingCategory.LEISURE.id,
        "concert" to SpendingCategory.LEISURE.id,
        "hotel" to SpendingCategory.LEISURE.id
    )

    /**
     * Map a model suggestion to a bundled category id, or null when it cannot
     * be safely resolved. Never throws; an empty/blank suggestion is null.
     */
    fun toCategoryId(rawSuggestion: String?): String? {
        val text = rawSuggestion?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null

        // Direct id hit (the model sometimes echoes "dining" verbatim).
        if (text in ALLOWED_IDS) return text

        // Earliest-occurring keyword wins (the head noun: "grocery shopping" →
        // groceries, not shopping); ties broken by longest keyword.
        val hit = keywordToCategory.entries
            .filter { text.contains(it.key) }
            .minWithOrNull(compareBy({ text.indexOf(it.key) }, { -it.key.length }))
            ?: return null

        // Only ever return an id that exists in the real bundled set.
        return hit.value.takeIf { it in ALLOWED_IDS }
    }
}
