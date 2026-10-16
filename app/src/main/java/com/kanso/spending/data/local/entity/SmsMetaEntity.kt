package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Idempotency log: one row per processed SMS fingerprint. The same message
 * delivered or imported twice matches the same row and is never re-recorded.
 *
 * Privacy: raw message bodies are NEVER stored here — only the SHA-256
 * fingerprint and the minimal routing fields required for de-duplication.
 */
@Entity(
    tableName = "sms_meta",
    indices = [Index(value = ["received_at_millis"])]
)
data class SmsMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,

    @ColumnInfo(name = "sender")
    val sender: String,

    @ColumnInfo(name = "provider")
    val provider: String,

    @ColumnInfo(name = "received_at_millis")
    val receivedAtMillis: Long,

    @ColumnInfo(name = "processed_at_millis")
    val processedAtMillis: Long,

    /** RECORDED | QUEUED | IGNORED | AMBIGUOUS. */
    @ColumnInfo(name = "outcome")
    val outcome: String,

    /** SmsTxType name, informational. */
    @ColumnInfo(name = "tx_type")
    val txType: String
)
