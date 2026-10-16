package com.kanso.spending.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.kanso.spending.data.local.LocalBackupManager
import com.kanso.spending.data.local.dao.SpendingDao
import com.kanso.spending.data.local.entity.AuditLogEntity
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity
import com.kanso.spending.data.model.MoneySource
import com.kanso.spending.data.model.PaymentMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

enum class LedgerThemeMode(val id: String, val label: String) {
    PAPER_LIGHT("paper_light", "Editorial Paper"),
    OBSIDIAN_DARK("obsidian_dark", "Obsidian Ink"),
    SYSTEM("system", "System Auto");

    companion object {
        fun fromId(id: String): LedgerThemeMode =
            entries.firstOrNull { it.id == id } ?: PAPER_LIGHT
    }
}

/**
 * Local vault repository. Every write goes through here so the audit trail is
 * filled consistently and the balance equation stays intact:
 *
 *     Available Balance = Opening Balance + Money Added − Expenses
 */
class SpendingRepository(
    private val spendingDao: SpendingDao,
    context: Context
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currencySymbol = MutableStateFlow(
        prefs.getString(KEY_CURRENCY_SYMBOL, DEFAULT_CURRENCY_SYMBOL) ?: DEFAULT_CURRENCY_SYMBOL
    )
    val currencySymbol: StateFlow<String> = _currencySymbol.asStateFlow()

    private val _themeMode = MutableStateFlow(
        LedgerThemeMode.fromId(prefs.getString(KEY_THEME_MODE, LedgerThemeMode.PAPER_LIGHT.id) ?: "")
    )
    val themeMode: StateFlow<LedgerThemeMode> = _themeMode.asStateFlow()

    private val auditCounter = AtomicInteger(0)

    // ------------------------------------------------------------- reads

    fun observeExpensesForMonth(yearMonth: YearMonth): Flow<List<ExpenseEntity>> =
        spendingDao.observeExpensesForRange(monthStartMillis(yearMonth), monthEndMillisExclusive(yearMonth))

    fun observeAllExpenses(): Flow<List<ExpenseEntity>> = spendingDao.observeAllExpenses()

    fun observeIncomeForMonth(yearMonth: YearMonth): Flow<List<IncomeEntity>> =
        spendingDao.observeIncomeForRange(monthStartMillis(yearMonth), monthEndMillisExclusive(yearMonth))

    fun observeAllIncome(): Flow<List<IncomeEntity>> = spendingDao.observeAllIncome()

    fun observeMonthlyBudget(yearMonth: YearMonth): Flow<MonthlyBudgetEntity?> =
        spendingDao.observeMonthlyBudget(yearMonth.toString())

    fun observeCategoryBudgets(yearMonth: YearMonth): Flow<List<CategoryBudgetEntity>> =
        spendingDao.observeCategoryBudgets(yearMonth.toString())

    fun observeCategories(): Flow<List<CategoryEntity>> = spendingDao.observeCategories()

    fun observeAuditTrail(): Flow<List<AuditLogEntity>> = spendingDao.observeRecentAuditEntries()

    /** One-shot snapshots for report generation (no Flow). */
    suspend fun observeAllExpensesOnce(): List<ExpenseEntity> = spendingDao.getAllExpensesSnapshot()
    suspend fun observeAllIncomeOnce(): List<IncomeEntity> = spendingDao.getAllIncomeSnapshot()
    suspend fun observeCategoriesOnce(): List<CategoryEntity> = spendingDao.getAllCategoriesSnapshot()

    /** True when the categories table has not been seeded yet (fresh install / restore gap). */
    suspend fun isCategoryTableEmpty(): Boolean = spendingDao.getAllCategoriesSnapshot().isEmpty()

    fun monthStartMillis(yearMonth: YearMonth): Long {
        val zone = ZoneId.systemDefault()
        return yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun monthEndMillisExclusive(yearMonth: YearMonth): Long {
        val zone = ZoneId.systemDefault()
        return yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    // ------------------------------------------------------------- writes

    suspend fun saveExpense(expense: ExpenseEntity): Long {
        val isInsert = expense.id == 0L
        val saved = spendingDao.upsertExpense(expense)
        logAudit(
            entityType = "expense",
            entityId = (if (expense.id != 0L) expense.id else saved).toString(),
            action = if (isInsert) "added" else "edited",
            details = "Spending ${expense.title} — ${expense.amountCents} minor units; " +
                "balance recalculated automatically"
        )
        return saved
    }

    suspend fun deleteExpense(expense: ExpenseEntity) {
        spendingDao.deleteExpenseById(expense.id)
        logAudit(
            entityType = "expense",
            entityId = expense.id.toString(),
            action = "deleted",
            details = "Removed spending ${expense.title} (${expense.amountCents} minor units); " +
                "balance increased accordingly"
        )
    }

    suspend fun saveIncome(income: IncomeEntity): Long {
        val isInsert = income.id == 0L
        val saved = spendingDao.upsertIncome(income)
        logAudit(
            entityType = "income",
            entityId = (if (income.id != 0L) income.id else saved).toString(),
            action = if (isInsert) "added" else "edited",
            details = "Money-in ${income.title} — ${income.amountCents} minor units " +
                "(${MoneySource.fromId(income.sourceId).label}); balance recalculated automatically"
        )
        return saved
    }

    suspend fun deleteIncome(income: IncomeEntity) {
        spendingDao.deleteIncomeById(income.id)
        logAudit(
            entityType = "income",
            entityId = income.id.toString(),
            action = "deleted",
            details = "Removed money-in ${income.title} (${income.amountCents} minor units); " +
                "balance decreased accordingly"
        )
    }

    // --------------------------------------------------------- categories

    /** Ids the DAO may never delete: they are the structural defaults. */
    fun defaultCategorySeed(): List<CategoryEntity> =
        DEFAULT_CATEGORIES.toList()

    /** Creates default categories the first time the app runs. */
    suspend fun seedDefaultCategoriesIfMissing() {
        val existing = spendingDao.getAllCategoriesSnapshot()
        val missing = DEFAULT_CATEGORIES.filter { def -> existing.none { it.id == def.id } }
        if (missing.isNotEmpty()) {
            spendingDao.insertCategories(missing)
        }
    }

    suspend fun createCategory(displayName: String, iconKey: String, colorHex: Long): String {
        val trimmed = displayName.trim()
        require(trimmed.isNotEmpty()) { "Category name cannot be empty" }
        val existing = spendingDao.getAllCategoriesSnapshot()
        require(existing.none { it.displayName.equals(trimmed, ignoreCase = true) }) {
            "A category with this name already exists"
        }
        val id = "custom_${System.currentTimeMillis()}_${auditCounter.incrementAndGet()}"
        val sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 10
        val entity = CategoryEntity(
            id = id,
            displayName = trimmed,
            iconKey = if (iconKey.isNotBlank()) iconKey else CategoryEntity.ICON_OTHER,
            colorHex = colorHex,
            sortOrder = sortOrder,
            isCustom = true
        )
        spendingDao.upsertCategory(entity)
        logAudit("category", id, "added", "Created custom category ${entity.displayName}")
        return id
    }

    /**
     * Rename keeps the id (and therefore all historical rows) intact.
     */
    suspend fun renameCategory(categoryId: String, newDisplayName: String) {
        val trimmed = newDisplayName.trim()
        require(trimmed.isNotEmpty()) { "Category name cannot be empty" }
        val category = spendingDao.findCategoryById(categoryId)
            ?: error("Unknown category $categoryId")
        spendingDao.upsertCategory(category.copy(displayName = trimmed))
        logAudit("category", categoryId, "edited", "Renamed ${category.displayName} → $trimmed; " +
            "historical transactions keep their link to this category")
    }

    suspend fun setCategoryIcon(categoryId: String, iconKey: String, colorHex: Long? = null) {
        val category = spendingDao.findCategoryById(categoryId)
            ?: error("Unknown category $categoryId")
        spendingDao.upsertCategory(
            category.copy(
                iconKey = if (iconKey.isNotBlank()) iconKey else category.iconKey,
                colorHex = colorHex ?: category.colorHex
            )
        )
        logAudit("category", categoryId, "edited", "Changed visual for ${category.displayName}")
    }

    suspend fun setCategoryHidden(categoryId: String, hidden: Boolean) {
        val category = spendingDao.findCategoryById(categoryId)
            ?: error("Unknown category $categoryId")
        spendingDao.upsertCategory(category.copy(isHidden = hidden))
    }

    /**
     * Delete a custom category. Historical rows are preserved either by
     * reassigning them to [reassignToId], or by parking them under the
     * default "Other" (uncategorized) bucket when [reassignToId] is null.
     * Default structural categories cannot be deleted.
     */
    suspend fun deleteCategory(categoryId: String, reassignToId: String?) {
        val category = spendingDao.findCategoryById(categoryId)
            ?: error("Unknown category $categoryId")
        require(category.isCustom) {
            "Built-in default categories cannot be deleted; hide or rename them instead."
        }
        val target = reassignToId.takeUnless { it.isNullOrBlank() }
            ?: CategoryEntity.UNCATEGORIZED_ID
        val moved = spendingDao.countExpensesInCategory(categoryId)
        if (moved > 0) {
            spendingDao.reassignCategoryInExpenses(categoryId, target)
        }
        spendingDao.deleteCategory(categoryId)
        logAudit(
            "category",
            categoryId,
            "deleted",
            "Deleted custom category ${category.displayName}; " +
                "$moved transaction(s) reassigned to $target so no history was lost"
        )
    }

    suspend fun countAndSumExpensesInCategory(categoryId: String): Pair<Int, Long> =
        spendingDao.countExpensesInCategory(categoryId) to spendingDao.sumExpensesInCategory(categoryId)

    // ------------------------------------------------------------- budget

    suspend fun saveMonthlyBudget(yearMonth: YearMonth, limitCents: Long) {
        spendingDao.upsertMonthlyBudget(
            MonthlyBudgetEntity(
                monthKey = yearMonth.toString(),
                overallLimitCents = limitCents
            )
        )
    }

    suspend fun saveCategoryBudget(yearMonth: YearMonth, categoryId: String, limitCents: Long) {
        if (limitCents <= 0L) {
            spendingDao.deleteCategoryBudget(yearMonth.toString(), categoryId)
        } else {
            spendingDao.upsertCategoryBudget(
                CategoryBudgetEntity(
                    monthKey = yearMonth.toString(),
                    categoryId = categoryId,
                    limitCents = limitCents
                )
            )
        }
    }

    // ----------------------------------------------------- prefs / system

    fun setCurrencySymbol(symbol: String) {
        val clean = symbol.trim().ifEmpty { DEFAULT_CURRENCY_SYMBOL }
        prefs.edit().putString(KEY_CURRENCY_SYMBOL, clean).apply()
        _currencySymbol.value = clean
    }

    fun setThemeMode(mode: LedgerThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.id).apply()
        _themeMode.value = mode
    }

    suspend fun logAudit(entityType: String, entityId: String, action: String, details: String) {
        val stamp = System.currentTimeMillis() + auditCounter.score()
        // Timestamp only orders the trail; a counter keeps distinct stamps for burst writes.
        spendingDao.insertAuditEntry(
            AuditLogEntity(
                timestampEpochMillis = stamp,
                entityType = entityType,
                entityId = entityId,
                action = action,
                details = details
            )
        )
    }

    private fun AtomicInteger.score(): Long = this.andIncrement.toLong() % 1000L

    // ------------------------------------------------------------- backup

    suspend fun exportLocalBackupJson(): String {
        val snapshot = LocalBackupManager.LedgerSnapshot(
            expenses = spendingDao.getAllExpensesSnapshot(),
            monthlyBudgets = spendingDao.getAllMonthlyBudgetsSnapshot(),
            categoryBudgets = spendingDao.getAllCategoryBudgetsSnapshot(),
            incomes = spendingDao.getAllIncomeSnapshot(),
            categories = spendingDao.getAllCategoriesSnapshot(),
            currencySymbol = _currencySymbol.value
        )
        return LocalBackupManager.exportToJsonString(snapshot)
    }

    suspend fun importLocalBackupJson(json: String): Result<Int> {
        return LocalBackupManager.importFromJsonString(json).mapCatching { snapshot ->
            // Validate: never trust a corrupted snapshot.
            require(snapshot.currencySymbol.isNotBlank()) { "Backup has a blank currency symbol" }
            snapshot.expenses.forEach { require(it.amountCents >= 0L) { "Backup contains a negative expense" } }
            snapshot.incomes.forEach { require(it.amountCents >= 0L) { "Backup contains a negative money-in record" } }

            // Unknown or missing category ids resolve to "Other" so links never dangle.
            val knownIds = snapshot.categories.map { it.id }.toSet()
            val sanitizedExpenses = snapshot.expenses.map { expense ->
                if (expense.categoryId !in knownIds) {
                    expense.copy(categoryId = CategoryEntity.UNCATEGORIZED_ID)
                } else expense
            }
            val categoriesToInsert = snapshot.categories.ifEmpty { defaultCategorySeed() } +
                defaultCategorySeed().filter { def -> snapshot.categories.none { it.id == def.id } }

            spendingDao.replaceAllLocalData(
                expenses = sanitizedExpenses,
                monthlyBudgets = snapshot.monthlyBudgets,
                categoryBudgets = snapshot.categoryBudgets,
                incomes = snapshot.incomes,
                categories = categoriesToInsert
            )
            setCurrencySymbol(snapshot.currencySymbol)
            logAudit("vault", "restore", "restored", "Vault restored from local snapshot: " +
                "${sanitizedExpenses.size} expenses, ${snapshot.incomes.size} money-in records")
            (sanitizedExpenses.size + snapshot.incomes.size)
        }
    }

    suspend fun wipeAllData() {
        spendingDao.wipeAllLocalData()
        seedDefaultCategoriesIfMissing()
        logAudit("vault", "wipe", "wiped", "Local vault wiped clean and defaults re-seeded")
    }

    companion object {
        /** Testing starts from zero — no default budget cap. */
        const val DEFAULT_MONTHLY_BUDGET_CENTS: Long = 0L
        /** Bangladeshi Taka is the default ledger currency. */
        const val DEFAULT_CURRENCY_SYMBOL: String = "৳"
        const val UNCATEGORIZED_ID: String = CategoryEntity.UNCATEGORIZED_ID

        val DEFAULT_CATEGORIES: List<CategoryEntity> = listOf(
            CategoryEntity("dining", "Food & Dining", CategoryEntity.ICON_FOOD, 0xFFC84B31, 10, false),
            CategoryEntity("groceries", "Groceries", CategoryEntity.ICON_CART, 0xFF2D6A4F, 20, false),
            CategoryEntity("transit", "Transportation", CategoryEntity.ICON_TRANSIT, 0xFF2B5876, 30, false),
            CategoryEntity("housing", "Housing & Rent", CategoryEntity.ICON_HOME, 0xFF6B5344, 40, false),
            CategoryEntity("shopping", "Shopping", CategoryEntity.ICON_SHOPPING, 0xFFD9822B, 50, false),
            CategoryEntity("wellness", "Health", CategoryEntity.ICON_HEALTH, 0xFF3D7A68, 60, false),
            CategoryEntity("education", "Education", CategoryEntity.ICON_EDUCATION, 0xFF2B5876, 70, false),
            CategoryEntity("leisure", "Entertainment", CategoryEntity.ICON_ENTERTAINMENT, 0xFF8C3B4B, 80, false),
            CategoryEntity("bills", "Bills & Utilities", CategoryEntity.ICON_BILLS, 0xFF6B5344, 90, false),
            CategoryEntity("subscriptions", "Subscriptions", CategoryEntity.ICON_SUBSCRIPTION, 0xFF5A4E7C, 95, false),
            CategoryEntity("other", "Other", CategoryEntity.ICON_OTHER, 0xFF161614, 100, false)
        )

        private const val PREFS_NAME = "kanso_local_prefs"
        private const val KEY_CURRENCY_SYMBOL = "currency_symbol"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
