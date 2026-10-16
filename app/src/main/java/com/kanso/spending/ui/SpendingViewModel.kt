package com.kanso.spending.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.model.MoneySource
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.repository.LedgerThemeMode
import com.kanso.spending.data.repository.SpendingRepository
import com.kanso.spending.domain.BudgetPacingStatus
import com.kanso.spending.domain.CategoryDisplayInfo
import com.kanso.spending.domain.LedgerDisplayEntry
import com.kanso.spending.domain.LedgerReportBuilder
import com.kanso.spending.domain.LedgerReportPeriod
import com.kanso.spending.domain.LedgerReportRange
import com.kanso.spending.domain.LedgerReportRanges
import com.kanso.spending.domain.LedgerReportCsvWriter
import com.kanso.spending.domain.LedgerReportPdfWriter
import com.kanso.spending.domain.MonthlyLedgerAnalytics
import com.kanso.spending.domain.SpendingAnalyticsEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** History filter: show everything, only money-in, or only expenses. */
enum class LedgerTypeFilter(val label: String) {
    ALL("ALL"), MONEY_IN("MONEY IN"), EXPENSES("EXPENSES");
}

/** Formats in which a report can be exported. */
enum class ReportFormat(val mimeType: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    CSV("text/csv", "csv")
}

/** A fully rendered report waiting for the user to choose a save location. */
data class PendingReport(
    val period: LedgerReportPeriod,
    val range: LedgerReportRange,
    val format: ReportFormat,
    val suggestedFileName: String,
    val bytes: ByteArray
)

/**
 * Single source of truth for the dashboard. Every number in the UI is derived
 * straight from the on-device Room database, so a fresh install always shows a
 * genuinely empty ledger (৳0.00) until the user records their own money-in or
 * spending.
 */
data class SpendingDashboardUiState(
    val selectedMonth: YearMonth = YearMonth.now(),
    val currencySymbol: String = SpendingRepository.DEFAULT_CURRENCY_SYMBOL,
    val selectedCategoryFilter: String? = null,
    val selectedMethodFilter: String? = null,
    val typeFilter: LedgerTypeFilter = LedgerTypeFilter.ALL,
    val searchQuery: String = "",
    val isDarkTheme: Boolean = false,
    /** All-time available balance: opening balance + money added − expenses. */
    val availableBalanceCents: Long = 0L,
    val analytics: MonthlyLedgerAnalytics = emptyAnalytics(),
    /** Every category the user may pick: defaults + custom entries. */
    val categoryList: List<CategoryEntity> = emptyList(),
    /** id -> usage(count, total spent across all history) for Manage Categories. */
    val categoryUsage: Map<String, Pair<Int, Long>> = emptyMap(),
    val auditLines: List<String> = emptyList(),
    val backupJsonPreview: String = "",
    val vaultStatusMessage: String? = null,
    val pendingReport: PendingReport? = null
) {
    companion object {
        fun emptyAnalytics(): MonthlyLedgerAnalytics = MonthlyLedgerAnalytics(
            yearMonth = YearMonth.now(),
            totalSpentCents = 0L,
            moneyInCents = 0L,
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
            methodBreakdown = emptyList(),
            dailyBars = emptyList(),
            daySections = emptyList(),
            merchantSuggestions = emptyList()
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpendingViewModel(private val repository: SpendingRepository) : ViewModel() {

    private val selectedMonth = MutableStateFlow(YearMonth.now())
    private val selectedCategoryId = MutableStateFlow<String?>(null)
    private val selectedMethodId = MutableStateFlow<String?>(null)
    private val typeFilter = MutableStateFlow(LedgerTypeFilter.ALL)
    private val searchQuery = MutableStateFlow("")
    private val isDarkTheme = MutableStateFlow(false)
    private val backupJsonPreview = MutableStateFlow("")
    private val vaultStatusMessage = MutableStateFlow<String?>(null)
    private val pendingReport = MutableStateFlow<PendingReport?>(null)

    init {
        // Fresh installs (and restored-legacy installs) always have the default
        // category directory available; seeding is idempotent.
        viewModelScope.launch { repository.seedDefaultCategoriesIfMissing() }
    }

    val uiState: StateFlow<SpendingDashboardUiState> = combine(
        selectedMonth,
        repository.currencySymbol,
        selectedCategoryId,
        selectedMethodId,
        typeFilter,
        searchQuery,
        isDarkTheme,
        backupJsonPreview,
        vaultStatusMessage,
        selectedMonth.flatMapLatest { repository.observeMonthlyBudget(it) },
        selectedMonth.flatMapLatest { repository.observeCategoryBudgets(it) },
        selectedMonth.flatMapLatest { repository.observeExpensesForMonth(it) },
        selectedMonth.flatMapLatest { repository.observeIncomeForMonth(it) },
        repository.observeAllExpenses(),
        repository.observeAllIncome(),
        repository.observeCategories(),
        repository.observeAuditTrail(),
        pendingReport
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val snap = MonthSnapshot(
            yearMonth = args[0] as YearMonth,
            currencySymbol = args[1] as String,
            catFilter = args[2] as String?,
            methodFilter = args[3] as String?,
            typeFilter = args[4] as LedgerTypeFilter,
            query = args[5] as String,
            dark = args[6] as Boolean,
            json = args[7] as String,
            msg = args[8] as String?,
            monthlyBudgetCents = (args[9] as? com.kanso.spending.data.local.entity.MonthlyBudgetEntity)?.overallLimitCents
                ?: SpendingRepository.DEFAULT_MONTHLY_BUDGET_CENTS,
            catBudgets = args[10] as List<CategoryBudgetEntity>,
            expenses = args[11] as List<ExpenseEntity>,
            incomes = args[12] as List<IncomeEntity>,
            allExpenses = args[13] as List<ExpenseEntity>,
            allIncomes = args[14] as List<IncomeEntity>,
            categories = args[15] as List<CategoryEntity>,
            audit = args[16] as List<com.kanso.spending.data.local.entity.AuditLogEntity>,
            report = args[17] as PendingReport?
        )
        buildUiState(snap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendingDashboardUiState())

    private fun buildUiState(snap: MonthSnapshot): SpendingDashboardUiState {
        val query = snap.query.trim()
        val visibleCategories = snap.categories.ifEmpty { repository.defaultCategorySeed() }
        val displayMap = visibleCategories.associate {
            it.id to CategoryDisplayInfo(it.id, it.displayName, it.colorHex)
        }

        val expenseEntries = snap.expenses
            .filter { expense ->
                val matchesCategory = snap.catFilter == null || expense.categoryId == snap.catFilter
                val matchesMethod = snap.methodFilter == null ||
                    PaymentMethod.resolveId(expense.paymentMethod).id == snap.methodFilter
                val matchesQuery = query.isEmpty() ||
                    expense.title.contains(query, true) ||
                    expense.note.contains(query, true) ||
                    displayMap[expense.categoryId]?.displayName?.contains(query, true) == true
                matchesCategory && matchesMethod && matchesQuery
            }
            .map(LedgerDisplayEntry::fromExpense)

        val incomeEntries = snap.incomes
            .filter { income ->
                // Category filters only constrain spending rows; money-in rows are
                // shown under the chosen type filter regardless of category.
                val matchesMethod = snap.methodFilter == null ||
                    PaymentMethod.resolveId(income.paymentMethod).id == snap.methodFilter
                val matchesQuery = query.isEmpty() ||
                    income.title.contains(query, true) ||
                    income.note.contains(query, true)
                when (snap.typeFilter) {
                    LedgerTypeFilter.EXPENSES -> false
                    else -> matchesMethod && matchesQuery
                }
            }
            .map(LedgerDisplayEntry::fromIncome)

        val filteredEntries = when (snap.typeFilter) {
            LedgerTypeFilter.ALL -> expenseEntries + incomeEntries
            LedgerTypeFilter.MONEY_IN -> incomeEntries
            LedgerTypeFilter.EXPENSES -> expenseEntries
        }

        val analytics = SpendingAnalyticsEngine.computeMonthlyAnalytics(
            yearMonth = snap.yearMonth,
            monthExpenses = snap.expenses,
            monthIncomes = snap.incomes,
            filteredEntries = filteredEntries,
            categoryDisplayMap = displayMap,
            monthlyBudgetCents = snap.monthlyBudgetCents,
            categoryBudgets = snap.catBudgets
        )

        // All-time balance: opening balance (initial_balance records) plus every
        // other money-in minus every expense. Refunds count once — as money in.
        val openingBalance = snap.allIncomes
            .filter { it.sourceId == MoneySource.INITIAL_BALANCE.id }
            .sumOf { it.amountCents }
        val otherIncome = snap.allIncomes
            .filter { it.sourceId != MoneySource.INITIAL_BALANCE.id }
            .sumOf { it.amountCents }
        val allExpensesTotal = snap.allExpenses.sumOf { it.amountCents }
        val availableBalance = openingBalance + otherIncome - allExpensesTotal

        val usage = snap.allExpenses
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.size to list.sumOf { it.amountCents } }

        val auditLines = snap.audit.take(30).map { entry ->            val stamp = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(entry.timestampEpochMillis),
                ZoneId.systemDefault()
            )
            "${stamp.format(DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US))} • " +
                "${entry.action.uppercase()} ${entry.entityType.uppercase()} — ${entry.details}"
        }

        return SpendingDashboardUiState(
            selectedMonth = snap.yearMonth,
            currencySymbol = snap.currencySymbol,
            selectedCategoryFilter = snap.catFilter,
            selectedMethodFilter = snap.methodFilter,
            typeFilter = snap.typeFilter,
            searchQuery = snap.query,
            isDarkTheme = snap.dark,
            availableBalanceCents = availableBalance,
            analytics = analytics,
            categoryList = visibleCategories,
            categoryUsage = usage,
            auditLines = auditLines,
            backupJsonPreview = snap.json,
            vaultStatusMessage = snap.msg,
            pendingReport = snap.report
        )
    }

    // -------------------------------------------------------- navigation etc.

    fun selectPreviousMonth() { selectedMonth.update { it.minusMonths(1) } }
    fun selectNextMonth() { selectedMonth.update { it.plusMonths(1) } }

    fun toggleCategoryFilter(categoryId: String?) {
        selectedCategoryId.update { if (it == categoryId) null else categoryId }
    }

    fun toggleMethodFilter(methodId: String?) {
        selectedMethodId.update { if (it == methodId) null else methodId }
    }

    fun setTypeFilter(filter: LedgerTypeFilter) { typeFilter.value = filter }

    fun updateSearchQuery(query: String) { searchQuery.value = query }

    fun toggleDarkTheme() { isDarkTheme.update { !it } }

    // -------------------------------------------------------- ledger writes

    fun saveExpense(
        existingId: Long?,
        title: String,
        amountCents: Long,
        categoryId: String,
        paymentMethod: PaymentMethod,
        paymentProvider: String,
        timestampMillis: Long,
        note: String,
        isRecurring: Boolean
    ) {
        viewModelScope.launch {
            repository.saveExpense(
                ExpenseEntity(
                    id = existingId ?: 0L,
                    title = title.trim().ifEmpty { "Expense" },
                    amountCents = amountCents,
                    categoryId = categoryId,
                    paymentMethod = paymentMethod.id,
                    paymentProvider = paymentProvider.trim(),
                    timestampEpochMillis = timestampMillis,
                    note = note.trim(),
                    isRecurring = isRecurring
                )
            )
        }
    }

    fun deleteExpense(expense: ExpenseEntity) {
        viewModelScope.launch { repository.deleteExpense(expense) }
    }

    fun saveMoneyIn(
        existingId: Long?,
        title: String,
        amountCents: Long,
        source: MoneySource,
        paymentMethod: PaymentMethod,
        paymentProvider: String,
        timestampMillis: Long,
        note: String
    ) {
        viewModelScope.launch {
            repository.saveIncome(
                IncomeEntity(
                    id = existingId ?: 0L,
                    title = title.trim().ifEmpty { source.label },
                    amountCents = amountCents.coerceAtLeast(0L),
                    sourceId = source.id,
                    paymentMethod = paymentMethod.id,
                    paymentProvider = paymentProvider.trim(),
                    timestampEpochMillis = timestampMillis,
                    note = note.trim()
                )
            )
        }
    }

    fun deleteIncome(income: IncomeEntity) {
        viewModelScope.launch { repository.deleteIncome(income) }
    }

    // -------------------------------------------------------- categories

    fun createCategory(name: String, iconKey: String, colorHex: Long) {
        viewModelScope.launch {
            runCatching { repository.createCategory(name, iconKey, colorHex) }
                .onFailure { e -> vaultStatusMessage.value = "Category error: ${e.message}" }
        }
    }

    fun renameCategory(categoryId: String, newName: String) {
        viewModelScope.launch {
            runCatching { repository.renameCategory(categoryId, newName) }
                .onFailure { e -> vaultStatusMessage.value = "Rename error: ${e.message}" }
        }
    }

    fun deleteCategory(categoryId: String, reassignToId: String?) {
        viewModelScope.launch {
            runCatching { repository.deleteCategory(categoryId, reassignToId) }
                .onFailure { e -> vaultStatusMessage.value = "Delete error: ${e.message}" }
        }
    }

    // -------------------------------------------------------- budget

    fun saveBudgetConfiguration(
        monthlyLimitCents: Long,
        currencySymbol: String,
        categoryLimits: Map<String, Long>
    ) {
        viewModelScope.launch {
            val ym = selectedMonth.value
            repository.setCurrencySymbol(currencySymbol)
            repository.saveMonthlyBudget(ym, monthlyLimitCents)
            categoryLimits.forEach { (catId, cents) ->
                repository.saveCategoryBudget(ym, catId, cents)
            }
            vaultStatusMessage.value = "Budget saved to your on-device ledger."
        }
    }

    // -------------------------------------------------------- reports

    fun prepareReport(
        period: LedgerReportPeriod,
        customStart: LocalDate? = null,
        customEnd: LocalDate? = null,
        format: ReportFormat
    ) {
        viewModelScope.launch {
            runCatching {
                val zone = ZoneId.systemDefault()
                val anchor = LocalDate.now(zone)
                val range = LedgerReportRanges.resolve(period, anchor, customStart, customEnd)

                val allExpenses = repository.observeAllExpensesOnce()
                val allIncomes = repository.observeAllIncomeOnce()
                val categories = repository.observeCategoriesOnce()

                val categoryLabel: (String) -> String =
                    { id -> categories.firstOrNull { it.id == id }?.displayName
                        ?: SpendingRepository.DEFAULT_CATEGORIES.firstOrNull { it.id == id }?.displayName
                        ?: id }
                val report = LedgerReportBuilder.build(
                    range = range,
                    allExpenses = allExpenses,
                    allIncomes = allIncomes,
                    zone = zone,
                    categoryLabel = categoryLabel
                )

                val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
                val stamp = stampFmt.format(range.start) + "-" + stampFmt.format(range.endInclusive)
                val fileName = "KansoLedger-report-$stamp.${format.extension}"
                val bytes = when (format) {
                    ReportFormat.CSV ->
                        LedgerReportCsvWriter.toCsv(report, zone).toByteArray(Charsets.UTF_8)
                    ReportFormat.PDF ->
                        LedgerReportPdfWriter.toPdfBytes(
                            report,
                            repository.currencySymbol.value,
                            formatRangeLabel(range)
                        )
                }
                PendingReport(period, range, format, fileName, bytes)
            }.onSuccess { report ->
                pendingReport.value = report
                vaultStatusMessage.value = null
            }.onFailure { e ->
                vaultStatusMessage.value = "Report error: ${e.message}"
            }
        }
    }

    private fun formatRangeLabel(range: LedgerReportRange): String {
        val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
        return if (range.start == range.endInclusive) {
            "Single day: ${range.start.format(fmt)}"
        } else "${range.start.format(fmt)} — ${range.endInclusive.format(fmt)}"
    }

    fun clearPendingReport() { pendingReport.value = null }

    // ------------------------------------------- backup / restore / vault

    fun generateLocalBackupJson() {
        viewModelScope.launch {
            val json = repository.exportLocalBackupJson()
            backupJsonPreview.value = json
            vaultStatusMessage.value = "Snapshot ready — ${json.length} characters."
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

    // -------------------------------------------------------- internals

    private data class MonthSnapshot(
        val yearMonth: YearMonth,
        val currencySymbol: String,
        val catFilter: String?,
        val methodFilter: String?,
        val typeFilter: LedgerTypeFilter,
        val query: String,
        val dark: Boolean,
        val json: String,
        val msg: String?,
        val monthlyBudgetCents: Long,
        val catBudgets: List<CategoryBudgetEntity>,
        val expenses: List<ExpenseEntity>,
        val incomes: List<IncomeEntity>,
        val allExpenses: List<ExpenseEntity>,
        val allIncomes: List<IncomeEntity>,
        val categories: List<CategoryEntity>,
        val audit: List<com.kanso.spending.data.local.entity.AuditLogEntity>,
        val report: PendingReport?
    )

    class Factory(private val repository: SpendingRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SpendingViewModel(repository) as T
    }
}
