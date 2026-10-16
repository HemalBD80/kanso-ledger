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

    /** Optional wallet / provider name, e.g. "bKash" ("Nagad" / "Rocket" etc.). */
    @ColumnInfo(name = "payment_provider", defaultValue = "")
    val paymentProvider: String = "",

    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,

    @ColumnInfo(name = "note")
    val note: String = "",

    @ColumnInfo(name = "is_recurring")
    val isRecurring: Boolean = false,

    /** Owning account id ("" → resolved from [paymentMethod] for legacy rows). */
    @ColumnInfo(name = "account_id", defaultValue = "")
    val accountId: String = "",

    /**
     * Provider fee charged alongside this expense, minor units. Total outflow
     * = amountCents + feeCents. A standalone fee row has amountCents = 0.
     */
    @ColumnInfo(name = "fee_cents", defaultValue = "0")
    val feeCents: Long = 0L,

    /** MANUAL | SMS — shown in reports so imports are traceable. */
    @ColumnInfo(name = "source", defaultValue = "MANUAL")
    val source: String = "MANUAL",

    /** Fingerprint of the SMS detection that produced this row, if any. */
    @ColumnInfo(name = "sms_fingerprint", defaultValue = "")
    val smsFingerprint: String = ""
) {
    init {
        require(amountCents >= 0L) { "Expense amount must be a non-negative minor-unit value" }
        require(feeCents >= 0L) { "Fee must be a non-negative minor-unit value" }
    }
}
