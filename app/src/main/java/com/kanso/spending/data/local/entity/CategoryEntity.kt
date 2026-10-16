package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A ledger category. Ids are stable strings so renaming a category (changing
 * only [displayName]) never breaks the historical rows in `expenses` that
 * reference the id. Default categories share the ids of the legacy
 * `SpendingCategory` enum; user-created ones use `custom_*` prefixed ids.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** Visual identifier key, e.g. "food", "cart", "home", "heart", "star". */
    @ColumnInfo(name = "icon_key")
    val iconKey: String = ICON_OTHER,

    /** Accent color in ARGB long form (matches the legacy enum accentHex). */
    @ColumnInfo(name = "color_hex")
    val colorHex: Long = 0xFF161614,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 100,

    @ColumnInfo(name = "is_custom")
    val isCustom: Boolean = false,

    /** Hidden categories remain resolvable for history but disappear from pickers. */
    @ColumnInfo(name = "is_hidden")
    val isHidden: Boolean = false
) {
    companion object {
        const val ICON_FOOD = "food"
        const val ICON_CART = "cart"
        const val ICON_TRANSIT = "transit"
        const val ICON_HOME = "home"
        const val ICON_SHOPPING = "shopping"
        const val ICON_HEALTH = "health"
        const val ICON_EDUCATION = "education"
        const val ICON_ENTERTAINMENT = "entertainment"
        const val ICON_BILLS = "bills"
        const val ICON_SUBSCRIPTION = "subscription"
        const val ICON_OTHER = "other"

        val AVAILABLE_ICON_KEYS = listOf(
            ICON_FOOD, ICON_CART, ICON_TRANSIT, ICON_HOME, ICON_SHOPPING,
            ICON_HEALTH, ICON_EDUCATION, ICON_ENTERTAINMENT, ICON_BILLS,
            ICON_SUBSCRIPTION, ICON_OTHER
        )

        /** Archive / uncategorized bucket used when a custom category is deleted. */
        const val UNCATEGORIZED_ID = "other"
    }
}
