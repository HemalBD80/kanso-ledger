package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Movement of money between the user's own accounts. A transfer decreases the
 * source and increases the destination and is never counted as income or
 * expense. Amounts are minor units (poisha) and always positive.
 */
@Entity(
    tableName = "transfers",
    indices = [Index(value = ["timestamp_epoch_millis"])]
)
data class TransferEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "from_account_id")
    val fromAccountId: String,

    @ColumnInfo(name = "to_account_id")
    val toAccountId: String,

    @ColumnInfo(name = "amount_cents")
    val amountCents: Long,

    @ColumnInfo(name = "fee_cents", defaultValue = "0")
    val feeCents: Long = 0L,

    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,

    @ColumnInfo(name = "note", defaultValue = "")
    val note: String = "",

    /** MANUAL | SMS. */
    @ColumnInfo(name = "origin", defaultValue = "MANUAL")
    val origin: String = "MANUAL",

    /** Provider reference id when the transfer arrived via SMS. */
    @ColumnInfo(name = "ref_id", defaultValue = "")
    val refId: String = "",

    /** CONFIRMED | PENDING. Pending rows never affect confirmed balances. */
    @ColumnInfo(name = "status", defaultValue = "CONFIRMED")
    val status: String = "CONFIRMED",

    /** Link to the detection that produced this row, if any. */
    @ColumnInfo(name = "sms_fingerprint", defaultValue = "")
    val smsFingerprint: String = ""
) {
    init {
        require(amountCents > 0L) { "Transfer amount must be positive" }
        require(fromAccountId != toAccountId) { "Transfer endpoints must differ" }
        require(feeCents >= 0L) { "Fee cannot be negative" }
    }
}
