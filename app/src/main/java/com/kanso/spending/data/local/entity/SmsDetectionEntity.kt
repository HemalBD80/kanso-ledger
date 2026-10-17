package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ReviewStatus { PENDING, APPROVED, REJECTED }

/**
 * A transaction recognised from an SMS (or manual text import), waiting for —
 * or having completed — user review. Detections are kept outside the financial
 * ledger tables: a PENDING detection never affects confirmed balances, and a
 * REJECTED one never becomes a ledger row. Approving materialises a real
 * expense / income / transfer row and records the link here.
 *
 * Privacy: extracted structured fields only. No raw message body is persisted.
 */
@Entity(
    tableName = "sms_detections",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["review_status"]),
        Index(value = ["batch_id"]),
        Index(value = ["timestamp_epoch_millis"])
    ]
)
data class SmsDetectionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,

    @ColumnInfo(name = "sender")
    val sender: String,

    @ColumnInfo(name = "provider")
    val provider: String,

    /** SmsTxType name. */
    @ColumnInfo(name = "tx_type")
    val txType: String,

    /** LedgerAction name: RECORD_EXPENSE / RECORD_INCOME / RECORD_TRANSFER / RECORD_FEE / REVIEW_ONLY / IGNORE. */
    @ColumnInfo(name = "action")
    val action: String,

    @ColumnInfo(name = "amount_cents")
    val amountCents: Long?,

    @ColumnInfo(name = "fee_cents", defaultValue = "0")
    val feeCents: Long = 0L,

    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,

    @ColumnInfo(name = "merchant", defaultValue = "")
    val merchant: String = "",

    @ColumnInfo(name = "ref_id", defaultValue = "")
    val refId: String = "",

    @ColumnInfo(name = "account_hint", defaultValue = "")
    val accountHint: String = "",

    @ColumnInfo(name = "reported_balance_cents")
    val reportedBalanceCents: Long? = null,

    /** 0..100 parsing confidence. */
    @ColumnInfo(name = "confidence")
    val confidence: Int,

    @ColumnInfo(name = "review_reason", defaultValue = "")
    val reviewReason: String = "",

    /** PENDING | APPROVED | REJECTED. */
    @ColumnInfo(name = "review_status")
    val reviewStatus: String,

    /** Category chosen/suggested at approval time. */
    @ColumnInfo(name = "category_id", defaultValue = "")
    val categoryId: String = "",

    /** AI payment-method hint (free-form), used only when the account is unknown. */
    @ColumnInfo(name = "payment_method_hint", defaultValue = "")
    val paymentMethodHint: String = "",

    /** Account chosen at approval time (empty → resolved from payment method). */
    @ColumnInfo(name = "account_id", defaultValue = "")
    val accountId: String = "",

    /** EXPENSE | INCOME | TRANSFER — the ledger table that received the row. */
    @ColumnInfo(name = "linked_record_type", defaultValue = "")
    val linkedRecordType: String = "",

    /** Row id in that table (stringified for transfer uuids). */
    @ColumnInfo(name = "linked_record_id", defaultValue = "")
    val linkedRecordId: String = "",

    @ColumnInfo(name = "batch_id", defaultValue = "")
    val batchId: String = "",

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,

    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long
)
