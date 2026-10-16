package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores monthly overall budget target per YYYY-MM key in local SQLite storage.
 */
@Entity(tableName = "monthly_budgets")
data class MonthlyBudgetEntity(
    @PrimaryKey
    @ColumnInfo(name = "month_key")
    val monthKey: String, // Format: "YYYY-MM" e.g. "2026-10"

    @ColumnInfo(name = "overall_limit_cents")
    val overallLimitCents: Long,

    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = System.currentTimeMillis()
)
