package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Signed manual or reconciliation adjustment applied to one account:
 *     Balance = Opening + Incoming − Outgoing + Adjustments
 * Positive values add money, negative values remove it. Every adjustment is
 * audited by the repository that writes it.
 */
@Entity(
    tableName = "account_adjustments",
    indices = [Index(value = ["account_id"]), Index(value = ["timestamp_epoch_millis"])]
)
data class AccountAdjustmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "account_id")
    val accountId: String,

    /** Signed minor units. */
    @ColumnInfo(name = "delta_cents")
    val deltaCents: Long,

    /** MANUAL | RECONCILE | OPENING. */
    @ColumnInfo(name = "kind")
    val kind: String = "MANUAL",

    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,

    @ColumnInfo(name = "note", defaultValue = "")
    val note: String = ""
)
