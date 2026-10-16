package com.kanso.spending.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import com.kanso.spending.data.repository.LedgerThemeMode
import com.kanso.spending.data.repository.SpendingRepository
import com.kanso.spending.domain.BudgetPacingStatus
import com.kanso.spending.domain.MonthlyLedgerAnalytics
import com.kanso.spending.domain.SpendingAnalyticsEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * Single source of truth for the dashboard. Every number in the UI is derived
 * straight from the on-device Room database, so a fresh install always shows a
 * genuinely empty ledger until the user records their own spending.
 */
data class SpendingDashboardUiState(
    val selectedMonth: YearMonth = YearMonth.now(),
    val currencySymbol: String = "$",
    val selectedCategoryFilter: SpendingCategory? = null,
    val searchQuery: String = "",
    val isDarkTheme: Boolean = false,
    val analytics: MonthlyLedgerAnalytics = MonthlyLedgerAnalytics(
        yearMonth = YearMonth.now(),
        totalSpentCents = 0L,
        monthlyBudgetCents = SpendingRepository.DEFAULT_MONTHLY_BUDGET_CENTS,
        remainingBudgetCents = SpendingRepository.DEFAULT_MONTHLY_BUDGET_CENTS,
        dailySafeToSpendCents = 0L,
        averageDailyBurnCents = 0L,
        dailyTargetCents = 0L,
        budgetProgressFraction = 0f,
        monthElapsedFraction = 0f,
        pacingStatus = BudgetPacingStatus.ON_TRACK,
        pacingDeltaPercent = 0,
        recurringSpentCents = 0L,
        categoryBreakdown = emptyList(),
        dailyBars = emptyList(),
        daySections = emptyList(),
        merchantSuggestions = emptyList()
    ),
    val backupJsonPreview: String = "",
    val vaultStatusMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class SpendingViewModel(private val repository: SpendingRepository) : ViewModel() {

    private val selectedMonth = MutableStateFlow(YearMonth.now())
    private val selectedCategory = MutableStateFlow<SpendingCategory?>(null)
    private val searchQuery = MutableStateFlow("")
    private val isDarkTheme = MutableStateFlow(false)
    private val backupJsonPreview = MutableStateFlow("")
    private val vaultStatusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SpendingDashboardUiState> = combine(
        selectedMonth,
        repository.currencySymbol,
        selectedCategory,
        searchQuery,
        isDarkTheme,
        backupJsonPreview,
        vaultStatusMessage,
        selectedMonth.flatMapLatest { month ->
            repository.observeMonthlyBudget(month)
        },
        selectedMonth.flatMapLatest { month ->
            repository.observeCategoryBudgets(month)
        },
        selectedMonth.flatMapLatest { month ->
            repository.observeExpensesForMonth(month)
        }
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val snap = MonthSnapshot(
            yearMonth = args[0] as YearMonth,
            currencySymbol = args[1] as String,
            catFilter = args[2] as SpendingCategory?,
            query = args[3] as String,
            dark = args[4] as Boolean,
            json = args[5] as String,
            msg = args[6] as String?,
            monthlyBudgetCents = (args[7] as? MonthlyBudgetEntity)?.overallLimitCents
                ?: SpendingRepository.DEFAULT_MONTHLY_BUDGET_CENTS,
            catBudgets = args[8] as List<CategoryBudgetEntity>,
            expenses = args[9] as List<ExpenseEntity>
        )

        val query = snap.query.trim()
        val filtered = snap.expenses.filter { expense ->
            val matchesCategory = snap.catFilter == null ||
                SpendingCategory.fromId(expense.categoryId) == snap.catFilter
            val matchesQuery = query.isEmpty() ||
                expense.title.contains(query, ignoreCase = true) ||
                expense.note.contains(query, ignoreCase = true) ||
                SpendingCategory.fromId(expense.categoryId).displayName.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = snap.yearMonth,
            monthExpenses = snap.expenses,
            filteredExpenses = filtered,
            monthlyBudgetCents = snap.monthlyBudgetCents,
            categoryBudgets = snap.catBudgets
        )

        SpendingDashboardUiState(
            selectedMonth = snap.yearMonth,
            currencySymbol = snap.currencySymbol,
            selectedCategoryFilter = snap.catFilter,
            searchQuery = snap.query,
            isDarkTheme = snap.dark,
            analytics = analytics,
            backupJsonPreview = snap.json,
            vaultStatusMessage = snap.msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendingDashboardUiState())

    fun selectPreviousMonth() { selectedMonth.update { it.minusMonths(1) } }
    fun selectNextMonth() { selectedMonth.update { it.plusMonths(1) } }

    fun toggleCategoryFilter(category: SpendingCategory?) {
        selectedCategory.update { if (it == category) null else category }
    }

    fun updateSearchQuery(query: String) { searchQuery.value = query }

    fun toggleDarkTheme() { isDarkTheme.update { !it } }

    fun saveExpense(
        existingId: Long?,
        title: String,
        amountCents: Long,
        category: SpendingCategory,
        paymentMethod: PaymentMethod,
        timestampMillis: Long,
        note: String,
        isRecurring: Boolean
    ) {
        viewModelScope.launch {
            repository.saveExpense(
                ExpenseEntity(
                    id = existingId ?: 0L,
                    title = title.trim().ifEmpty { category.displayName },
                    amountCents = amountCents,
                    categoryId = category.id,
                    paymentMethod = paymentMethod.id,
                    timestampEpochMillis = timestampMillis,
                    note = note.trim(),
                    isRecurring = isRecurring
                )
            )
        }
    }

    fun deleteExpense(expense: ExpenseEntity) {
        viewModelScope.launch { repository.deleteExpense(expense.id) }
    }

    fun saveBudgetConfiguration(
        monthlyLimitCents: Long,
        currencySymbol: String,
        categoryLimits: Map<SpendingCategory, Long>
    ) {
        viewModelScope.launch {
            val ym = selectedMonth.value
            repository.setCurrencySymbol(currencySymbol)
            repository.saveMonthlyBudget(ym, monthlyLimitCents)
            categoryLimits.forEach { (cat, cents) ->
                repository.saveCategoryBudget(ym, cat, cents)
            }
            vaultStatusMessage.value = "Budget saved to your on-device ledger."
        }
    }

    fun generateLocalBackupJson() {
        viewModelScope.launch {
            val json = repository.exportLocalBackupJson()
            backupJsonPreview.value = json
            vaultStatusMessage.value = "Snapshot ready \u2014 ${json.length} characters."
        }
    }

    fun importLocalBackupJson(rawJson: String) {
        viewModelScope.launch {
            repository.importLocalBackupJson(rawJson)
                .onSuccess { count ->
                    backupJsonPreview.value = ""
                    vaultStatusMessage.value = "Restored $count records into the local vault."
                }
                .onFailure { e -> vaultStatusMessage.value = "Restore error: ${e.message}" }
        }
    }

    fun wipeAllLocalData() {
        viewModelScope.launch {
            repository.wipeAllData()
            backupJsonPreview.value = ""
            vaultStatusMessage.value = "Local vault wiped clean."
        }
    }

    fun clearVaultStatusMessage() { vaultStatusMessage.value = null }

    private data class MonthSnapshot(
        val yearMonth: YearMonth,
        val currencySymbol: String,
        val catFilter: SpendingCategory?,
        val query: String,
        val dark: Boolean,
        val json: String,
        val msg: String?,
        val monthlyBudgetCents: Long,
        val catBudgets: List<CategoryBudgetEntity>,
        val expenses: List<ExpenseEntity>
    )

    class Factory(private val repository: SpendingRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SpendingViewModel(repository) as T
    }
}
