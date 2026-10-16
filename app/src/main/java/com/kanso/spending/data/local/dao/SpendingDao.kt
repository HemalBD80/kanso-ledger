package com.kanso.spending.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpendingDao {

    @Query(
        """
        SELECT * FROM expenses
        WHERE timestamp_epoch_millis >= :startEpochMillis
          AND timestamp_epoch_millis < :endEpochMillis
        ORDER BY timestamp_epoch_millis DESC, id DESC
        """
    )
    fun observeExpensesForRange(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses ORDER BY timestamp_epoch_millis DESC, id DESC")
    fun observeAllExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses ORDER BY timestamp_epoch_millis DESC, id DESC")
    suspend fun getAllExpensesSnapshot(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExpense(expense: ExpenseEntity): Long

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)

    @Query("DELETE FROM expenses WHERE id = :expenseId")
    suspend fun deleteExpenseById(expenseId: Long)

    @Query("SELECT * FROM monthly_budgets WHERE month_key = :monthKey LIMIT 1")
    fun observeMonthlyBudget(monthKey: String): Flow<MonthlyBudgetEntity?>

    @Query("SELECT * FROM monthly_budgets")
    suspend fun getAllMonthlyBudgetsSnapshot(): List<MonthlyBudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonthlyBudget(budget: MonthlyBudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMonthlyBudgets(budgets: List<MonthlyBudgetEntity>)

    @Query("SELECT * FROM category_budgets WHERE month_key = :monthKey")
    fun observeCategoryBudgets(monthKey: String): Flow<List<CategoryBudgetEntity>>

    @Query("SELECT * FROM category_budgets")
    suspend fun getAllCategoryBudgetsSnapshot(): List<CategoryBudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryBudget(categoryBudget: CategoryBudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryBudgets(budgets: List<CategoryBudgetEntity>)

    @Query("DELETE FROM category_budgets WHERE month_key = :monthKey AND category_id = :categoryId")
    suspend fun deleteCategoryBudget(monthKey: String, categoryId: String)

    @Query("DELETE FROM expenses")
    suspend fun clearAllExpenses()

    @Query("DELETE FROM monthly_budgets")
    suspend fun clearAllMonthlyBudgets()

    @Query("DELETE FROM category_budgets")
    suspend fun clearAllCategoryBudgets()

    @Transaction
    suspend fun wipeAllLocalData() {
        clearAllExpenses()
        clearAllMonthlyBudgets()
        clearAllCategoryBudgets()
    }

    @Transaction
    suspend fun replaceAllLocalData(
        expenses: List<ExpenseEntity>,
        monthlyBudgets: List<MonthlyBudgetEntity>,
        categoryBudgets: List<CategoryBudgetEntity>
    ) {
        wipeAllLocalData()
        // Preserve the incoming record ids so a restored ledger round-trips exactly.
        expenses.forEach { upsertExpense(it) }
        insertMonthlyBudgets(monthlyBudgets)
        insertCategoryBudgets(categoryBudgets)
    }
}
