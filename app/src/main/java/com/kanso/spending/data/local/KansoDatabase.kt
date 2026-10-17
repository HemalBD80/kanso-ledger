package com.kanso.spending.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kanso.spending.data.local.dao.SpendingDao
import com.kanso.spending.data.local.entity.AccountAdjustmentEntity
import com.kanso.spending.data.local.entity.AccountEntity
import com.kanso.spending.data.local.entity.AuditLogEntity
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.CategoryRuleEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.GoalContributionEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity
import com.kanso.spending.data.local.entity.RecurringRuleEntity
import com.kanso.spending.data.local.entity.SavingsGoalEntity
import com.kanso.spending.data.local.entity.SmsDetectionEntity
import com.kanso.spending.data.local.entity.SmsMetaEntity
import com.kanso.spending.data.local.entity.TransferEntity

@Database(
    entities = [
        ExpenseEntity::class,
        MonthlyBudgetEntity::class,
        CategoryBudgetEntity::class,
        CategoryEntity::class,
        IncomeEntity::class,
        AuditLogEntity::class,
        AccountEntity::class,
        TransferEntity::class,
        AccountAdjustmentEntity::class,
        SmsMetaEntity::class,
        SmsDetectionEntity::class,
        RecurringRuleEntity::class,
        SavingsGoalEntity::class,
        GoalContributionEntity::class,
        CategoryRuleEntity::class
    ],
    version = 4,
    // Schema *export* is off by design: nothing in this repo (no migration
    // test, no schema consumer) reads the generated JSON, and exporting it made
    // the two variant KSP tasks write a shared `app/schemas` output directory,
    // which on CI intermittently left a zero-byte JSON that aborted the whole
    // build with Room's "Empty schema file". The runtime migrations below are
    // unaffected by this flag. Flip to `true` (and give each variant its own
    // location) only if a future migration test actually consumes the schemas.
    exportSchema = false
)
abstract class KansoDatabase : RoomDatabase() {

    abstract fun spendingDao(): SpendingDao

    companion object {
        private const val DATABASE_NAME = "kanso_local_ledger.db"

        /**
         * v1 -> v2 (non-destructive):
         *  - new `categories` table seeded with all default categories (the ids
         *    match the legacy SpendingCategory enum so existing expense rows
         *    resolve correctly) plus an "other" uncategorized bucket.
         *  - new `income_entries` table for money-in records.
         *  - new `audit_log` table for the correction trail.
         *  - `expenses` gains the optional `payment_provider` column.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `categories` (
                        `id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `icon_key` TEXT NOT NULL,
                        `color_hex` INTEGER NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `is_custom` INTEGER NOT NULL,
                        `is_hidden` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                val defaults = listOf(
                    arrayOf("dining", "Food & Dining", "food", 0xFFC84B31, 10, 0),
                    arrayOf("groceries", "Groceries", "cart", 0xFF2D6A4F, 20, 0),
                    arrayOf("transit", "Transportation", "transit", 0xFF2B5876, 30, 0),
                    arrayOf("housing", "Housing & Rent", "home", 0xFF6B5344, 40, 0),
                    arrayOf("shopping", "Shopping", "shopping", 0xFFD9822B, 50, 0),
                    arrayOf("wellness", "Health", "health", 0xFF3D7A68, 60, 0),
                    arrayOf("leisure", "Entertainment", "entertainment", 0xFF8C3B4B, 80, 0),
                    arrayOf("education", "Education", "education", 0xFF2B5876, 70, 0),
                    arrayOf("bills", "Bills & Utilities", "bills", 0xFF6B5344, 90, 0),
                    arrayOf("subscriptions", "Subscriptions", "subscription", 0xFF5A4E7C, 95, 0),
                    arrayOf("other", "Other", "other", 0xFF161614, 100, 0)
                )
                defaults.forEach { row ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `categories` (`id`,`display_name`,`icon_key`,`color_hex`,`sort_order`,`is_custom`,`is_hidden`) VALUES (?,?,?,?,?,?,0)",
                        row
                    )
                }
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `income_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `amount_cents` INTEGER NOT NULL,
                        `source_id` TEXT NOT NULL,
                        `payment_method` TEXT NOT NULL,
                        `payment_provider` TEXT NOT NULL,
                        `timestamp_epoch_millis` INTEGER NOT NULL,
                        `note` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_income_entries_timestamp_epoch_millis` ON `income_entries` (`timestamp_epoch_millis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_income_entries_source_id` ON `income_entries` (`source_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_income_entries_payment_method` ON `income_entries` (`payment_method`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audit_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp_epoch_millis` INTEGER NOT NULL,
                        `entity_type` TEXT NOT NULL,
                        `entity_id` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `details` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_timestamp_epoch_millis` ON `audit_log` (`timestamp_epoch_millis`)")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `payment_provider` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2 -> v3 (non-destructive): Smart-SMS intelligence + multi-account
         * ledger + planning tables. Existing expenses, income, categories and
         * budgets are preserved untouched; new columns carry safe defaults and
         * legacy rows are backfilled onto the seeded default accounts so every
         * historical transaction keeps counting exactly once.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- new ledger tables ------------------------------------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `accounts` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `opening_balance_cents` INTEGER NOT NULL,
                        `is_archived` INTEGER NOT NULL DEFAULT 0,
                        `sort_order` INTEGER NOT NULL,
                        `last_reported_balance_cents` INTEGER,
                        `last_reported_at_millis` INTEGER,
                        `note` TEXT NOT NULL DEFAULT '',
                        `created_at_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_name` ON `accounts` (`name`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfers` (
                        `id` TEXT NOT NULL,
                        `from_account_id` TEXT NOT NULL,
                        `to_account_id` TEXT NOT NULL,
                        `amount_cents` INTEGER NOT NULL,
                        `fee_cents` INTEGER NOT NULL DEFAULT 0,
                        `timestamp_epoch_millis` INTEGER NOT NULL,
                        `note` TEXT NOT NULL DEFAULT '',
                        `origin` TEXT NOT NULL DEFAULT 'MANUAL',
                        `ref_id` TEXT NOT NULL DEFAULT '',
                        `status` TEXT NOT NULL DEFAULT 'CONFIRMED',
                        `sms_fingerprint` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_timestamp_epoch_millis` ON `transfers` (`timestamp_epoch_millis`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `account_adjustments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `account_id` TEXT NOT NULL,
                        `delta_cents` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `timestamp_epoch_millis` INTEGER NOT NULL,
                        `note` TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_adjustments_account_id` ON `account_adjustments` (`account_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_adjustments_timestamp_epoch_millis` ON `account_adjustments` (`timestamp_epoch_millis`)")

                // ---- SMS processing (no message bodies ever stored) --------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sms_meta` (
                        `fingerprint` TEXT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `received_at_millis` INTEGER NOT NULL,
                        `processed_at_millis` INTEGER NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `tx_type` TEXT NOT NULL,
                        PRIMARY KEY(`fingerprint`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_meta_received_at_millis` ON `sms_meta` (`received_at_millis`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sms_detections` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `tx_type` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `amount_cents` INTEGER,
                        `fee_cents` INTEGER NOT NULL DEFAULT 0,
                        `timestamp_epoch_millis` INTEGER NOT NULL,
                        `merchant` TEXT NOT NULL DEFAULT '',
                        `ref_id` TEXT NOT NULL DEFAULT '',
                        `account_hint` TEXT NOT NULL DEFAULT '',
                        `reported_balance_cents` INTEGER,
                        `confidence` INTEGER NOT NULL,
                        `review_reason` TEXT NOT NULL DEFAULT '',
                        `review_status` TEXT NOT NULL,
                        `category_id` TEXT NOT NULL DEFAULT '',
                        `account_id` TEXT NOT NULL DEFAULT '',
                        `linked_record_type` TEXT NOT NULL DEFAULT '',
                        `linked_record_id` TEXT NOT NULL DEFAULT '',
                        `batch_id` TEXT NOT NULL DEFAULT '',
                        `created_at_millis` INTEGER NOT NULL,
                        `updated_at_millis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_detections_fingerprint` ON `sms_detections` (`fingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_detections_review_status` ON `sms_detections` (`review_status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_detections_batch_id` ON `sms_detections` (`batch_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_detections_timestamp_epoch_millis` ON `sms_detections` (`timestamp_epoch_millis`)")

                // ---- planning tables -------------------------------------
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recurring_rules` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `amount_cents` INTEGER NOT NULL,
                        `category_id` TEXT NOT NULL,
                        `account_id` TEXT NOT NULL DEFAULT '',
                        `interval_days` INTEGER NOT NULL,
                        `next_due_millis` INTEGER NOT NULL,
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `source` TEXT NOT NULL DEFAULT 'MANUAL',
                        `last_paid_millis` INTEGER,
                        `created_at_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `savings_goals` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `target_cents` INTEGER NOT NULL,
                        `deadline_millis` INTEGER,
                        `linked_account_id` TEXT NOT NULL DEFAULT '',
                        `created_at_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `goal_contributions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `goal_id` TEXT NOT NULL,
                        `amount_cents` INTEGER NOT NULL,
                        `account_id` TEXT NOT NULL DEFAULT '',
                        `timestamp_epoch_millis` INTEGER NOT NULL,
                        `note` TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_contributions_goal_id` ON `goal_contributions` (`goal_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_contributions_timestamp_epoch_millis` ON `goal_contributions` (`timestamp_epoch_millis`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `category_rules` (
                        `id` TEXT NOT NULL,
                        `keyword` TEXT NOT NULL,
                        `category_id` TEXT NOT NULL,
                        `scope` TEXT NOT NULL DEFAULT 'GLOBAL',
                        `created_at_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                // ---- new columns on existing tables ----------------------
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `account_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `fee_cents` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `sms_fingerprint` TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE `income_entries` ADD COLUMN `account_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `income_entries` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE `income_entries` ADD COLUMN `sms_fingerprint` TEXT NOT NULL DEFAULT ''")

                // ---- seed the four default accounts (all ৳0.00) ----------
                val now = System.currentTimeMillis()
                val seeds = listOf(
                    arrayOf("acc_cash", "Cash", "CASH", 0, 10, now),
                    arrayOf("acc_bank", "Bank Account", "BANK", 0, 20, now),
                    arrayOf("acc_wallet", "Mobile Wallet", "WALLET", 0, 30, now),
                    arrayOf("acc_card", "Card", "CARD", 0, 40, now)
                )
                seeds.forEach { row ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `accounts` (`id`,`name`,`type`,`opening_balance_cents`,`sort_order`,`created_at_millis`) VALUES (?,?,?,?,?,?)",
                        row
                    )
                }

                // ---- backfill legacy rows onto matching accounts ----------
                // payment_method ids: cash, wallet, debit, credit, transfer,
                // other (+ legacy "card" which resolves to debit).
                db.execSQL(
                    "UPDATE `expenses` SET `account_id` = CASE `payment_method` " +
                        "WHEN 'wallet' THEN 'acc_wallet' " +
                        "WHEN 'debit' THEN 'acc_card' " +
                        "WHEN 'credit' THEN 'acc_card' " +
                        "WHEN 'card' THEN 'acc_card' " +
                        "WHEN 'transfer' THEN 'acc_bank' " +
                        "ELSE 'acc_cash' END " +
                        "WHERE `account_id` = ''"
                )
                db.execSQL(
                    "UPDATE `income_entries` SET `account_id` = CASE `payment_method` " +
                        "WHEN 'wallet' THEN 'acc_wallet' " +
                        "WHEN 'debit' THEN 'acc_card' " +
                        "WHEN 'credit' THEN 'acc_card' " +
                        "WHEN 'card' THEN 'acc_card' " +
                        "WHEN 'transfer' THEN 'acc_bank' " +
                        "ELSE 'acc_cash' END " +
                        "WHERE `account_id` = ''"
                )
            }
        }

        /**
         * v3 -> v4 (non-destructive): AI-assisted SMS understanding. The
         * `sms_detections` table gains an advisory `payment_method_hint` filled
         * from the model's `payment_method_suggestion`; it is a display hint only
         * and never overrides a known account. Every other table is untouched and
         * existing rows default to "" (no hint).
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sms_detections` ADD COLUMN `payment_method_hint` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        @Volatile
        private var INSTANCE: KansoDatabase? = null

        fun getInstance(context: Context): KansoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KansoDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
