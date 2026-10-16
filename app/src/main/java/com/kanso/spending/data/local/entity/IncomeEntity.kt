package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 *Money-in records: every addition of money to the user's available funds.
 * Each addition is its own row — an earlier balance or money entry is never
 * overwritten. Amounts are stored in minor currency units (poisha) as Long.
 */
@Entity(
    tableName = "income_entries",
    indices = [
        Index(value = ["timestamp_epoch_millis"]),
        Index(value = ["source_id"]),
        Index(value = ["payment_method"])
    ]
)
data class IncomeEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** Short description of where the money came from, e.g. "October Salary". */
    @ColumnInfo(name = "title")
    val title: String,

    /** Always a positive amount in minor currency units. */
    @ColumnInfo(name = "amount_cents")
    val amountCents: Long,

    /** [MoneySource] id: initial_balance, salary, freelance, gift, refund, transfer_in, other. */
    @ColumnInfo(name = "source_id")
    val sourceId: String,

    @ColumnInfo(name = "payment_method")
    val paymentMethod: String,

    /** Optional wallet / provider name, e.g. "bKash" or "Rocket". */
    @ColumnInfo(name = "payment_provider")
    val paymentProvider: String = "",

    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,

    @ColumnInfo(name = "note")
    val note: String = ""
) {
    init {
        require(amountCents >= 0L) { "Income amount must be stored as a non-negative minor-unit amount" }
    }
}
