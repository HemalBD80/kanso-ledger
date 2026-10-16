package com.kanso.spending.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import com.kanso.spending.data.repository.SpendingRepository
import com.kanso.spending.domain.BudgetPacingStatus
import com.kanso.spending.domain.MonthlyLedgerAnalytics
import com.kanso.spending.domain.SpendingAnalyticsEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

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
        dailySafeToSpendCents = 6000L,
        averageDailyBurnCents = 0L,
        dailyTargetCents = 6000L,
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

    init {
        viewModelScope.launch { repository.ensureInitialSeedIfNeeded() }
    }

    private val monthDataFlow = selectedMonth.flatMapLatest { ym ->
        combine(
            repository.observeExpensesForMonth(ym),
            repository.observeMonthlyBudget(ym),
            repository.observeCategoryBudgets(ym),
            repository.currencySymbol
        ) { expenses, monthlyBudget, catBudgets, currency ->
            MonthSnapshot(
                yearMonth = ym,
                expenses = expenses,
                monthlyLimitCents = monthlyBudget?.overallLimitCents ?: SpendingRepository.DEFAULT_MONTHLY_BUDGET_CENTS,
                currencySymbol = currency,
                catBudgets = catBudgets
            )
        }
    }

    val uiState: StateFlow<SpendingDashboardUiState> = combine(
        monthDataFlow,
        selectedCategory,
        searchQuery,
        isDarkTheme,
        combine(backupJsonPreview, vaultStatusMessage) { json, msg -> json to msg }
    ) { snap, catFilter, query, dark, (json, msg) ->
        val trimmedQuery = query.trim().lowercase()
        val filtered = snap.expenses.filter { exp ->
            val matchesCat = catFilter == null || exp.categoryId.equals(catFilter.id, ignoreCase = true)
            val matchesQuery = trimmedQuery.isEmpty() ||
                exp.title.lowercase().contains(trimmedQuery) ||
                exp.note.lowercase().contains(trimmedQuery) ||
                exp.categoryId.lowercase().contains(trimmedQuery)
            matchesCat && matchesQuery
        }
        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = snap.yearMonth,
            monthExpenses = snap.expenses,
            filteredExpenses = filtered,
            monthlyBudgetCents = snap.monthlyLimitCents,
            categoryBudgets = snap.catBudgets,
            today = LocalDate.now()
        )
        SpendingDashboardUiState(
            selectedMonth = snap.yearMonth,
            currencySymbol = snap.currencySymbol,
            selectedCategoryFilter = catFilter,
            searchQuery = query,
            isDarkTheme = dark,
            analytics = analytics,
            backupJsonPreview = json,
            vaultStatusMessage = msg
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
        }
    }

    fun generateLocalBackupJson() {
        viewModelScope.launch {
            backupJsonPreview.value = repository.exportLocalBackupJson()
            vaultStatusMessage.value = "Local JSON snapshot ready (${backupJsonPreview.value.length} bytes)."
        }
    }

    fun importLocalBackupJson(rawJson: String) {
        viewModelScope.launch {
            repository.importLocalBackupJson(rawJson)
                .onSuccess { count -> vaultStatusMessage.value = "Restored $count records into local SQLite." }
                .onFailure { e -> vaultStatusMessage.value = "Restore error: ${e.message}" }
        }
    }

    fun reseedSampleLedger() {
        viewModelScope.launch {
            repository.wipeAllData()
            repository.seedSampleMonthData(selectedMonth.value)
            vaultStatusMessage.value = "Sample editorial ledger loaded into local SQLite."
        }
    }

    fun wipeAllLocalData() {
        viewModelScope.launch {
            repository.wipeAllData()
            vaultStatusMessage.value = "All local SQLite tables wiped clean."
        }
    }

    fun clearVaultStatusMessage() { vaultStatusMessage.value = null }

    private data class MonthSnapshot(
        val yearMonth: YearMonth,
        val expenses: List<ExpenseEntity>,
        val monthlyLimitCents: Long,
        val currencySymbol: String,
        val catBudgets: List<CategoryBudgetEntity>
    )

    class Factory(private val repository: SpendingRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SpendingViewModel(repository) as T
    }
}
