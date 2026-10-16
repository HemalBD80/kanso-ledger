package com.kanso.spending.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.kanso.spending.data.local.LocalBackupManager
import com.kanso.spending.data.local.dao.SpendingDao
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity
import com.kanso.spending.data.model.SpendingCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.YearMonth
import java.time.ZoneId

enum class LedgerThemeMode(val id: String, val label: String) {
    PAPER_LIGHT("paper_light", "Editorial Paper"),
    OBSIDIAN_DARK("obsidian_dark", "Obsidian Ink"),
    SYSTEM("system", "System Auto");

    companion object {
        fun fromId(id: String): LedgerThemeMode =
            entries.firstOrNull { it.id == id } ?: PAPER_LIGHT
    }
}

class SpendingRepository(
    private val spendingDao: SpendingDao,
    context: Context
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currencySymbol = MutableStateFlow(
        prefs.getString(KEY_CURRENCY_SYMBOL, "$") ?: "$"
    )
    val currencySymbol: StateFlow<String> = _currencySymbol.asStateFlow()

    private val _themeMode = MutableStateFlow(
        LedgerThemeMode.fromId(prefs.getString(KEY_THEME_MODE, LedgerThemeMode.PAPER_LIGHT.id) ?: "")
    )
    val themeMode: StateFlow<LedgerThemeMode> = _themeMode.asStateFlow()

    fun observeExpensesForMonth(yearMonth: YearMonth): Flow<List<ExpenseEntity>> {
        val zone = ZoneId.systemDefault()
        val startMillis = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return spendingDao.observeExpensesForRange(startMillis, endMillis)
    }

    fun observeAllExpenses(): Flow<List<ExpenseEntity>> = spendingDao.observeAllExpenses()

    fun observeMonthlyBudget(yearMonth: YearMonth): Flow<MonthlyBudgetEntity?> =
        spendingDao.observeMonthlyBudget(yearMonth.toString())

    fun observeCategoryBudgets(yearMonth: YearMonth): Flow<List<CategoryBudgetEntity>> =
        spendingDao.observeCategoryBudgets(yearMonth.toString())

    suspend fun saveExpense(expense: ExpenseEntity): Long =
        spendingDao.upsertExpense(expense)

    suspend fun deleteExpense(expenseId: Long) =
        spendingDao.deleteExpenseById(expenseId)

    suspend fun saveMonthlyBudget(yearMonth: YearMonth, limitCents: Long) {
        spendingDao.upsertMonthlyBudget(
            MonthlyBudgetEntity(
                monthKey = yearMonth.toString(),
                overallLimitCents = limitCents
            )
        )
    }

    suspend fun saveCategoryBudget(yearMonth: YearMonth, category: SpendingCategory, limitCents: Long) {
        if (limitCents <= 0L) {
            spendingDao.deleteCategoryBudget(yearMonth.toString(), category.id)
        } else {
            spendingDao.upsertCategoryBudget(
                CategoryBudgetEntity(
                    monthKey = yearMonth.toString(),
                    categoryId = category.id,
                    limitCents = limitCents
                )
            )
        }
    }

    fun setCurrencySymbol(symbol: String) {
        val clean = symbol.trim().ifEmpty { "$" }
        prefs.edit().putString(KEY_CURRENCY_SYMBOL, clean).apply()
        _currencySymbol.value = clean
    }

    fun setThemeMode(mode: LedgerThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.id).apply()
        _themeMode.value = mode
    }

    suspend fun exportLocalBackupJson(): String {
        val snapshot = LocalBackupManager.LedgerSnapshot(
            expenses = spendingDao.getAllExpensesSnapshot(),
            monthlyBudgets = spendingDao.getAllMonthlyBudgetsSnapshot(),
            categoryBudgets = spendingDao.getAllCategoryBudgetsSnapshot(),
            currencySymbol = _currencySymbol.value
        )
        return LocalBackupManager.exportToJsonString(snapshot)
    }

    suspend fun importLocalBackupJson(json: String): Result<Int> {
        return LocalBackupManager.importFromJsonString(json).map { snapshot ->
            spendingDao.replaceAllLocalData(
                expenses = snapshot.expenses,
                monthlyBudgets = snapshot.monthlyBudgets,
                categoryBudgets = snapshot.categoryBudgets
            )
            setCurrencySymbol(snapshot.currencySymbol)
            snapshot.expenses.size
        }
    }

    suspend fun wipeAllData() {
        spendingDao.wipeAllLocalData()
    }

    suspend fun ensureInitialSeedIfNeeded() {
        val alreadySeeded = prefs.getBoolean(KEY_INITIAL_SEED_DONE, false)
        if (alreadySeeded) return
        prefs.edit().putBoolean(KEY_INITIAL_SEED_DONE, true).apply()
        if (spendingDao.getExpenseCount() == 0) {
            seedSampleMonthData(YearMonth.now())
        }
    }

    suspend fun seedSampleMonthData(targetMonth: YearMonth = YearMonth.now()) {
        spendingDao.insertExpenses(SampleLedgerSeeder.buildSampleExpenses(targetMonth))
        spendingDao.upsertMonthlyBudget(
            MonthlyBudgetEntity(
                monthKey = targetMonth.toString(),
                overallLimitCents = 180_000L
            )
        )
        spendingDao.insertCategoryBudgets(SampleLedgerSeeder.buildDefaultCategoryBudgets(targetMonth))
    }

    companion object {
        private const val PREFS_NAME = "kanso_local_prefs"
        private const val KEY_CURRENCY_SYMBOL = "currency_symbol"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_INITIAL_SEED_DONE = "initial_seed_done"
    }
}
