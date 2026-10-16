package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lightweight on-device audit trail: whenever a financial record is added,
 * edited, or deleted, a descriptive line is stored so users can understand why
 * their balance changed. Purely local; exported nowhere.
 */
@Entity(
    tableName = "audit_log",
    indices = [Index(value = ["timestamp_epoch_millis"])]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,

    /** "expense" | "income" | "category" | "vault" */
    @ColumnInfo(name = "entity_type")
    val entityType: String,

    /** Stable id of the affected record, if any. */
    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "action")
    val action: String,

    /** Human-readable detail line rendered in the local vault sheet. */
    @ColumnInfo(name = "details")
    val details: String
)
