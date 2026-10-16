package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Stores optional per-category monthly spending caps in local SQLite storage.
 */
@Entity(
    tableName = "category_budgets",
    primaryKeys = ["month_key", "category_id"]
)
data class CategoryBudgetEntity(
    @ColumnInfo(name = "month_key")
    val monthKey: String,

    @ColumnInfo(name = "category_id")
    val categoryId: String,

    @ColumnInfo(name = "limit_cents")
    val limitCents: Long
)
