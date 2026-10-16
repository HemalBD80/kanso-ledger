package com.kanso.spending.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kanso.spending.data.local.dao.SpendingDao
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity

@Database(
    entities = [
        ExpenseEntity::class,
        MonthlyBudgetEntity::class,
        CategoryBudgetEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class KansoDatabase : RoomDatabase() {

    abstract fun spendingDao(): SpendingDao

    companion object {
        private const val DATABASE_NAME = "kanso_local_ledger.db"

        @Volatile
        private var INSTANCE: KansoDatabase? = null

        fun getInstance(context: Context): KansoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KansoDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
