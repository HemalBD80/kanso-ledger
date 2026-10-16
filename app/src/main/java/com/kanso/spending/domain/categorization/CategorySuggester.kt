package com.kanso.spending.domain.categorization

/**
 * Deterministic category suggestion from transaction text.
 *
 * Order of authority: user-defined rules → bundled keyword table → null
 * (never guess a category the user did not earn back).
 */
data class CategoryRule(
    val id: String,
    val keyword: String,
    val categoryId: String,
    val scope: String = "GLOBAL"   // GLOBAL or provider-specific
)

data class CategorySuggestion(
    val categoryId: String,
    val matchedKeyword: String,
    val confidence: Int,           // 0..100
    val source: String             // "user-rule" | "builtin"
)

object CategorySuggester {

    /** Bundled keyword → category id. Category ids match SpendingCategory. */
    private val builtin: Map<String, String> = linkedMapOf(
        // Food & dining
        "restaurant" to "dining", "cafe" to "dining", "coffee" to "dining",
        "food" to "dining", "kfc" to "dining", "pizza" to "dining", "burger" to "dining",
        "star kitchen" to "dining", "agora" to "groceries", "chaldal" to "groceries",
        "hunger" to "dining", "chillox" to "dining", "madchef" to "dining",
        // Groceries
        "grocer" to "groceries", "supermarket" to "groceries", "bazar" to "groceries",
        "market" to "groceries", "shwapno" to "groceries", "meena bazar" to "groceries",
        // Transport
        "uber" to "transit", "pathao" to "transit", "bus" to "transit",
        "fuel" to "transit", "petrol" to "transit", "cng" to "transit",
        "rickshaw" to "transit", "train" to "transit", "ticket" to "transit",
        "bangladesh railway" to "transit",
        // Bills & utilities
        "electricity" to "bills", "gas" to "bills", "water bill" to "bills",
        "wifi" to "bills", "broadband" to "bills", "utility" to "bills",
        "internet bill" to "bills", "desco" to "bills", "wasa" to "bills",
        "titas" to "bills", "invoice" to "bills",
        // Mobile & internet
        "recharge" to "mobile", "top.?up" to "mobile", "gp " to "mobile",
        "grameenphone" to "mobile", "robi" to "mobile", "banglalink" to "mobile",
        "airtel" to "mobile", "teletalk" to "mobile", "data pack" to "mobile",
        // Shopping
        "shopping" to "shopping", "mall" to "shopping", "clothing" to "shopping",
        "daraz" to "shopping", "pickaboo" to "shopping", "boutique" to "shopping",
        "shoe" to "shopping", "store" to "shopping",
        // Health
        "pharmacy" to "wellness", "hospital" to "wellness", "clinic" to "wellness",
        "doctor" to "wellness", "medicine" to "wellness", "diagnostic" to "wellness",
        "square pharma" to "wellness", "express diagnostics" to "wellness",
        // Entertainment
        "cinema" to "leisure", "movie" to "leisure", "netflix" to "leisure",
        "spotify" to "leisure", "game" to "leisure", "subscription" to "leisure",
        "concert" to "leisure", "hotel" to "leisure", "travel" to "leisure",
        // Education
        "course" to "education", "tuition" to "education", "book" to "education",
        "school" to "education", "university" to "education", "exam fee" to "education",
        // Cash / transfers (never a category of spend by themselves)
        "atm withdrawal" to "transit", "cash out" to "transit"
    )

    private val defaultCategory = "other"

    /**
     * Suggest a category for arbitrary transaction text (merchant, note,
     * description). User rules win; longer keyword matches win ties.
     */
    fun suggest(
        text: String,
        userRules: List<CategoryRule> = emptyList(),
        availableCategoryIds: Set<String> = emptySet()
    ): CategorySuggestion? {
        val haystack = text.lowercase().trim()
        if (haystack.isEmpty()) return null

        fun usable(id: String) = availableCategoryIds.isEmpty() || id in availableCategoryIds

        // User rules first, longest keyword wins so "pizza delivery" beats "pizza".
        val userHit = userRules
            .filter { it.keyword.isNotBlank() && usable(it.categoryId) }
            .filter { haystack.contains(it.keyword.lowercase()) }
            .maxByOrNull { it.keyword.length }
        if (userHit != null) {
            return CategorySuggestion(userHit.categoryId, userHit.keyword, 95, "user-rule")
        }

        val builtinHit = builtin.entries
            .filter { (kw, cat) -> usable(cat) && matches(haystack, kw) }
            .maxByOrNull { it.key.length }
        if (builtinHit != null) {
            return CategorySuggestion(builtinHit.value, builtinHit.key, 75, "builtin")
        }

        // Any word that equals a known category id (e.g. "dining") is a fair hint.
        if (usable(defaultCategory) && haystack.length in 3..40) {
            builtin.values.distinct().firstOrNull { usable(it) && haystack == it }?.let {
                return CategorySuggestion(it, it, 70, "builtin")
            }
        }
        return null
    }

    /** Exact or simple fuzzy hit ("data pack" matches "internet data pack today"). */
    private fun matches(haystack: String, keyword: String): Boolean {
        val kw = keyword.lowercase()
        if (kw.contains(' ') || kw.contains('.')) {
            return Regex("\\b" + Regex.escape(kw).replace("\\ ", "\\s+") + "\\b")
                .containsMatchIn(haystack)
        }
        return Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(haystack)
    }

    /** Final fallback when nothing matches: "other". */
    fun fallbackCategory(): String = defaultCategory
}
