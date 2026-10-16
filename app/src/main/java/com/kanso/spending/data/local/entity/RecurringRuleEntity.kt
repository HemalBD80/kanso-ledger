package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A confirmed recurring payment definition (rent, subscription, salary day…).
 * Forecasts derived from rules are always labelled estimates and are never
 * written into the ledger as actual transactions.
 */
@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** Expected amount, minor units (may vary month to month). */
    @ColumnInfo(name = "amount_cents")
    val amountCents: Long,

    @ColumnInfo(name = "category_id")
    val categoryId: String,

    @ColumnInfo(name = "account_id", defaultValue = "")
    val accountId: String = "",

    /** 7 / 14 / 28 / 30 / 31 … cadence in days. */
    @ColumnInfo(name = "interval_days")
    val intervalDays: Int,

    @ColumnInfo(name = "next_due_millis")
    val nextDueMillis: Long,

    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,

    /** MANUAL | DETECTED (detector-suggested then user-confirmed). */
    @ColumnInfo(name = "source", defaultValue = "MANUAL")
    val source: String = "MANUAL",

    @ColumnInfo(name = "last_paid_millis")
    val lastPaidMillis: Long? = null,

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long
)
