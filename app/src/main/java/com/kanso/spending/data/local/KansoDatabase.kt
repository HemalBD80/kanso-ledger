package com.kanso.spending.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kanso.spending.data.local.dao.SpendingDao
import com.kanso.spending.data.local.entity.AuditLogEntity
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity

@Database(
    entities = [
        ExpenseEntity::class,
        MonthlyBudgetEntity::class,
        CategoryBudgetEntity::class,
        CategoryEntity::class,
        IncomeEntity::class,
        AuditLogEntity::class
    ],
    version = 2,
    exportSchema = true
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

        @Volatile
        private var INSTANCE: KansoDatabase? = null

        fun getInstance(context: Context): KansoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KansoDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
