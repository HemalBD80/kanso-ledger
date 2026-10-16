package com.kanso.spending.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kanso.spending.data.local.entity.AuditLogEntity
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
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

    // --------------------------------------------------------------- income

    @Query(
        """
        SELECT * FROM income_entries
        WHERE timestamp_epoch_millis >= :startEpochMillis
          AND timestamp_epoch_millis < :endEpochMillis
        ORDER BY timestamp_epoch_millis DESC, id DESC
        """
    )
    fun observeIncomeForRange(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM income_entries ORDER BY timestamp_epoch_millis DESC, id DESC")
    fun observeAllIncome(): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM income_entries ORDER BY timestamp_epoch_millis DESC, id DESC")
    suspend fun getAllIncomeSnapshot(): List<IncomeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIncome(income: IncomeEntity): Long

    @Update
    suspend fun updateIncome(income: IncomeEntity)

    @Query("DELETE FROM income_entries WHERE id = :incomeId")
    suspend fun deleteIncomeById(incomeId: Long)

    // ------------------------------------------------------------ categories

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, display_name ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, display_name ASC")
    suspend fun getAllCategoriesSnapshot(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :categoryId LIMIT 1")
    suspend fun findCategoryById(categoryId: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: String)

    @Query("SELECT COUNT(*) FROM expenses WHERE category_id = :categoryId")
    suspend fun countExpensesInCategory(categoryId: String): Int

    @Query("SELECT COALESCE(SUM(amount_cents), 0) FROM expenses WHERE category_id = :categoryId")
    suspend fun sumExpensesInCategory(categoryId: String): Long

    @Query("UPDATE expenses SET category_id = :targetId WHERE category_id = :fromId")
    suspend fun reassignCategoryInExpenses(fromId: String, targetId: String)

    // ------------------------------------------------------------ audit trail

    @Insert
    suspend fun insertAuditEntry(entry: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY timestamp_epoch_millis DESC, id DESC LIMIT 100")
    fun observeRecentAuditEntries(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log ORDER BY timestamp_epoch_millis DESC, id DESC LIMIT 100")
    suspend fun getRecentAuditEntries(): List<AuditLogEntity>

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

    @Query("DELETE FROM income_entries")
    suspend fun clearAllIncome()

    @Query("DELETE FROM categories")
    suspend fun clearAllCategories()

    @Query("DELETE FROM audit_log")
    suspend fun clearAllAuditLog()

    @Transaction
    suspend fun wipeAllLocalData() {
        clearAllExpenses()
        clearAllMonthlyBudgets()
        clearAllCategoryBudgets()
        clearAllIncome()
        clearAllAuditLog()
        // Categories are wiped with everything else; the repository immediately
        // re-seeds the default set so the picker stays usable after a wipe.
        clearAllCategories()
    }

    @Transaction
    suspend fun replaceAllLocalData(
        expenses: List<ExpenseEntity>,
        monthlyBudgets: List<MonthlyBudgetEntity>,
        categoryBudgets: List<CategoryBudgetEntity>,
        incomes: List<IncomeEntity>,
        categories: List<CategoryEntity>
    ) {
        wipeAllLocalData()
        // Preserve the incoming record ids so a restored ledger round-trips exactly.
        expenses.forEach { upsertExpense(it) }
        insertMonthlyBudgets(monthlyBudgets)
        insertCategoryBudgets(categoryBudgets)
        incomes.forEach { upsertIncome(it) }
        insertCategories(categories)
    }
}
