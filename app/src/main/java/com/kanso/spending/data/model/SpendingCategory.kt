package com.kanso.spending.data.model

/**
 * Curated spending categories with bespoke editorial mineral & botanical color tokens
 * inspired by Dribbble / Pinterest Swiss-ledger & warm tactile finance systems.
 * Avoids generic saturated neon rainbow defaults.
 */
enum class SpendingCategory(
    val id: String,
    val displayName: String,
    val shortCode: String,
    val accentHex: Long,
    val containerLightHex: Long,
    val containerDarkHex: Long,
    val defaultShareWeight: Float
) {
    DINING(
        id = "dining",
        displayName = "Dining & Coffee",
        shortCode = "DIN",
        accentHex = 0xFFC84B31,        // Terracotta Vermilion
        containerLightHex = 0xFFF9EAE6,
        containerDarkHex = 0xFF331D18,
        defaultShareWeight = 0.20f
    ),
    GROCERIES(
        id = "groceries",
        displayName = "Groceries & Market",
        shortCode = "GRO",
        accentHex = 0xFF2D6A4F,        // Botanical Pine
        containerLightHex = 0xFFE6F2EC,
        containerDarkHex = 0xFF172C22,
        defaultShareWeight = 0.22f
    ),
    TRANSIT(
        id = "transit",
        displayName = "Transit & Fuel",
        shortCode = "TRN",
        accentHex = 0xFF2B5876,        // Fjord Slate Blue
        containerLightHex = 0xFFE6EFF5,
        containerDarkHex = 0xFF172631,
        defaultShareWeight = 0.12f
    ),
    HOUSING(
        id = "housing",
        displayName = "Housing & Utilities",
        shortCode = "HSG",
        accentHex = 0xFF6B5344,        // Raw Umber Clay
        containerLightHex = 0xFFF1ECE8,
        containerDarkHex = 0xFF2B231E,
        defaultShareWeight = 0.25f
    ),
    SHOPPING(
        id = "shopping",
        displayName = "Apparel & Goods",
        shortCode = "SHP",
        accentHex = 0xFFD9822B,        // Warm Ochre Amber
        containerLightHex = 0xFFFBF0E4,
        containerDarkHex = 0xFF352414,
        defaultShareWeight = 0.10f
    ),
    SUBSCRIPTIONS(
        id = "subscriptions",
        displayName = "Recurring & Digital",
        shortCode = "SUB",
        accentHex = 0xFF5A4E7C,        // Muted Iris Slate
        containerLightHex = 0xFFEEEBF5,
        containerDarkHex = 0xFF252033,
        defaultShareWeight = 0.06f
    ),
    WELLNESS(
        id = "wellness",
        displayName = "Health & Pharmacy",
        shortCode = "MED",
        accentHex = 0xFF3D7A68,        // Eucalyptus Sage
        containerLightHex = 0xFFE8F3F0,
        containerDarkHex = 0xFF1B2E28,
        defaultShareWeight = 0.05f
    ),
    LEISURE(
        id = "leisure",
        displayName = "Culture & Books",
        shortCode = "CUL",
        accentHex = 0xFF8C3B4B,        // Oxblood Wine
        containerLightHex = 0xFFF5E8EB,
        containerDarkHex = 0xFF311A20,
        defaultShareWeight = 0.05f
    );

    companion object {
        fun fromId(id: String): SpendingCategory {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DINING
        }
    }
}
