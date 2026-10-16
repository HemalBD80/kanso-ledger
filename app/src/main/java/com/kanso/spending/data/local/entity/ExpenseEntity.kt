package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local SQLite table for individual spending records.
 * Amounts are persisted in minor currency units (cents/paise) as Long to prevent floating-point drift.
 */
@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["timestamp_epoch_millis"]),
        Index(value = ["category_id"]),
        Index(value = ["payment_method"])
    ]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "amount_cents")
    val amountCents: Long,

    @ColumnInfo(name = "category_id")
    val categoryId: String,

    @ColumnInfo(name = "payment_method")
    val paymentMethod: String,

    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,

    @ColumnInfo(name = "note")
    val note: String = "",

    @ColumnInfo(name = "is_recurring")
    val isRecurring: Boolean = false
)
