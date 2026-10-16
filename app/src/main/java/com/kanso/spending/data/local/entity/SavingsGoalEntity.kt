package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A savings target. Progress comes from recorded contributions only — never
 * from forecasts. Projections are computed at read time and labelled as
 * estimates by [com.kanso.spending.domain.goals.SavingsGoalEngine].
 */
@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "target_cents")
    val targetCents: Long,

    /** Optional target date; null → rolling 90-day horizon for forecasts. */
    @ColumnInfo(name = "deadline_millis")
    val deadlineMillis: Long? = null,

    @ColumnInfo(name = "linked_account_id", defaultValue = "")
    val linkedAccountId: String = "",

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long
)

/**
 * One recorded movement of money into a savings goal. Contributions are real
 * ledger events (optionally drawn from a specific account) — progress is their
 * confirmed sum, nothing else.
 */
@Entity(
    tableName = "goal_contributions",
    indices = [Index(value = ["goal_id"]), Index(value = ["timestamp_epoch_millis"])]
)
data class GoalContributionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "goal_id")
    val goalId: String,

    @ColumnInfo(name = "amount_cents")
    val amountCents: Long,

    @ColumnInfo(name = "account_id", defaultValue = "")
    val accountId: String = "",

    @ColumnInfo(name = "timestamp_epoch_millis")
    val timestampEpochMillis: Long,

    @ColumnInfo(name = "note", defaultValue = "")
    val note: String = ""
)
