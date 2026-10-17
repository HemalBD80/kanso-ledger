package com.kanso.spending.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kanso.spending.data.local.entity.AccountEntity
import com.kanso.spending.data.local.entity.AccountKind
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.CategoryRuleEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.GoalContributionEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.RecurringRuleEntity
import com.kanso.spending.data.local.entity.SavingsGoalEntity
import com.kanso.spending.data.local.entity.SmsDetectionEntity
import com.kanso.spending.data.local.entity.TransferEntity
import com.kanso.spending.data.model.MoneySource
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.repository.DetectionApproval
import com.kanso.spending.data.repository.LedgerThemeMode
import com.kanso.spending.data.repository.SmartSettings
import com.kanso.spending.data.repository.SmsImportPreview
import com.kanso.spending.data.repository.SmsIngestSummary
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
import com.kanso.spending.domain.accounts.AccountBalanceEngine
import com.kanso.spending.domain.accounts.AccountState
import com.kanso.spending.domain.ai.AiConnectionProbe
import com.kanso.spending.domain.calendar.CalendarEngine
import com.kanso.spending.domain.calendar.CalendarSummary
import com.kanso.spending.domain.goals.GoalContribution
import com.kanso.spending.domain.goals.GoalForecast
import com.kanso.spending.domain.goals.SavingsGoal
import com.kanso.spending.domain.goals.SavingsGoalEngine
import com.kanso.spending.domain.insights.Insight
import com.kanso.spending.domain.insights.InsightInput
import com.kanso.spending.domain.insights.InsightsEngine
import com.kanso.spending.domain.recurring.ExpenseRecord
import com.kanso.spending.domain.recurring.ForecastItem
import com.kanso.spending.domain.recurring.ForecastRule
import com.kanso.spending.domain.recurring.RecurringCandidate
import com.kanso.spending.domain.recurring.RecurringDetector
import com.kanso.spending.domain.sms.RawInboundSms
import com.kanso.spending.domain.sms.SmsImportMode
import com.kanso.spending.sms.SmsHistoryReader
import kotlinx.coroutines.Dispatchers
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

/**
 * Second state channel for the v2.0 intelligence features. Kept separate from
 * [SpendingDashboardUiState] deliberately: the core dashboard combine is
 * already large, and these flows change for different reasons (review queue,
 * accounts, plans) than month browsing does.
 */
data class SmartLedgerUiState(
    /** Per-account balances from the ledger itself (never provider-overwritten). */
    val accountStates: List<AccountState> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val transfers: List<TransferEntity> = emptyList(),
    /** Net funds across non-archived accounts (card debt included as negative). */
    val netWorthCents: Long = 0L,
    /** Accounts whose provider-reported balance differs from the ledger. */
    val reconciliationWarningCount: Int = 0,
    val pendingDetections: List<SmsDetectionEntity> = emptyList(),
    val recentDetections: List<SmsDetectionEntity> = emptyList(),
    val smartSettings: SmartSettings = SmartSettings(),
    val lastIngestSummary: SmsIngestSummary? = null,
    val lastBatchId: String? = null,
    val recurringRules: List<RecurringRuleEntity> = emptyList(),
    val recurringCandidates: List<RecurringCandidate> = emptyList(),
    /** Next 30 days of confirmed recurring rules — every item is an estimate. */
    val forecastItems: List<ForecastItem> = emptyList(),
    val savingsGoals: List<SavingsGoal> = emptyList(),
    val goalForecasts: List<GoalForecast> = emptyList(),
    /** Raw contribution records (each has an id for per-row undo/delete). */
    val contributions: List<GoalContributionEntity> = emptyList(),
    val calendarSummary: CalendarSummary? = null,
    val insights: List<Insight> = emptyList(),
    val categoryRules: List<CategoryRuleEntity> = emptyList(),
    val statusMessage: String? = null
)

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
        // category directory available; seeding is idempotent. Accounts: same.
        viewModelScope.launch {
            repository.seedDefaultCategoriesIfMissing()
            repository.seedDefaultAccountsIfMissing()
        }
    }

    private val smartStatusMessage = MutableStateFlow<String?>(null)
    private val lastIngestSummary = MutableStateFlow<SmsIngestSummary?>(null)
    private val lastBatchId = MutableStateFlow<String?>(null)

    val coreUiState: StateFlow<SpendingDashboardUiState> = combine(
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

    /**
     * Intelligence state: accounts, review queue, plans and observations.
     * Derived entirely from Room flows — nothing here is fetched or inferred
     * from anywhere but this device.
     */
    val smartState: StateFlow<SmartLedgerUiState> = combine(
        repository.observeAccountStates(),
        repository.observeAccounts(),
        repository.observeTransfers(),
        repository.observePendingDetections(),
        repository.observeDetections(),
        repository.smartSettings,
        repository.observeRecurringRules(),
        repository.observeSavingsGoals(),
        repository.observeGoalContributions(),
        repository.observeCategoryRules(),
        repository.observeAllExpenses(),
        repository.observeAllIncome(),
        repository.observeCategoryBudgets(YearMonth.now()),
        selectedMonth,
        smartStatusMessage,
        lastIngestSummary,
        lastBatchId
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val accountStates = args[0] as List<AccountState>
        val accounts = args[1] as List<AccountEntity>
        val transfers = args[2] as List<TransferEntity>
        val pending = args[3] as List<SmsDetectionEntity>
        val allDetections = args[4] as List<SmsDetectionEntity>
        val settings = args[5] as SmartSettings
        val rules = args[6] as List<RecurringRuleEntity>
        val goalEntities = args[7] as List<SavingsGoalEntity>
        val contributionEntities = args[8] as List<GoalContributionEntity>
        val categoryRules = args[9] as List<CategoryRuleEntity>
        val allExpenses = args[10] as List<ExpenseEntity>
        val allIncomes = args[11] as List<IncomeEntity>
        val currentBudgets = args[12] as List<CategoryBudgetEntity>
        val month = args[13] as YearMonth
        val status = args[14] as String?
        val summary = args[15] as SmsIngestSummary?
        val batch = args[16] as String?

        buildSmartState(
            accountStates = accountStates,
            accounts = accounts,
            transfers = transfers,
            pending = pending,
            allDetections = allDetections,
            settings = settings,
            rules = rules,
            goalEntities = goalEntities,
            contributionEntities = contributionEntities,
            categoryRules = categoryRules,
            allExpenses = allExpenses,
            allIncomes = allIncomes,
            currentBudgets = currentBudgets,
            month = month,
            status = status,
            summary = summary,
            batch = batch
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartLedgerUiState())

    /**
     * Public dashboard state: the core month view, with the headline balance
     * taken from the per-account engine whenever accounts exist (the legacy
     * opening+in−out formula remains the fallback for empty states).
     */
    val uiState: StateFlow<SpendingDashboardUiState> =
        combine(coreUiState, smartState) { core, smart ->
            val headline = if (smart.accountStates.isNotEmpty()) {
                AccountBalanceEngine.netWorth(smart.accountStates)
            } else {
                core.availableBalanceCents
            }
            core.copy(availableBalanceCents = headline)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendingDashboardUiState())

    private fun buildSmartState(
        accountStates: List<AccountState>,
        accounts: List<AccountEntity>,
        transfers: List<TransferEntity>,
        pending: List<SmsDetectionEntity>,
        allDetections: List<SmsDetectionEntity>,
        settings: SmartSettings,
        rules: List<RecurringRuleEntity>,
        goalEntities: List<SavingsGoalEntity>,
        contributionEntities: List<GoalContributionEntity>,
        categoryRules: List<CategoryRuleEntity>,
        allExpenses: List<ExpenseEntity>,
        allIncomes: List<IncomeEntity>,
        currentBudgets: List<CategoryBudgetEntity>,
        month: YearMonth,
        status: String?,
        summary: SmsIngestSummary?,
        batch: String?
    ): SmartLedgerUiState {
        val now = System.currentTimeMillis()

        // ---- recurring: confirmed history only, proposals never auto-save
        val candidateRecords = allExpenses.map { expense ->
            ExpenseRecord(
                id = expense.id.toString(),
                merchant = expense.title,
                amountCents = expense.amountCents,
                timestampMillis = expense.timestampEpochMillis,
                categoryId = expense.categoryId,
                accountHint = expense.accountId.takeIf { it.isNotBlank() }
            )
        }
        val confirmedTitles = rules.filter { it.isActive }.map { it.title.trim().lowercase() }.toSet()
        val candidates = RecurringDetector.detect(candidateRecords, now)
            .filter { it.merchant.trim().lowercase() !in confirmedTitles }
        val forecastRules = rules.map { rule ->
            ForecastRule(
                id = rule.id,
                title = rule.title,
                amountCents = rule.amountCents,
                intervalDays = rule.intervalDays,
                expectedNextMillis = rule.nextDueMillis,
                isActive = rule.isActive,
                categoryId = rule.categoryId,
                accountHint = rule.accountId.takeIf { it.isNotBlank() }
            )
        }
        val todayStart = now - now % 86_400_000L
        val forecastItems = RecurringDetector.forecast(
            rules = forecastRules,
            fromMillis = todayStart,
            days = 30,
            nowMillis = now
        )

        // ---- goals: confirmed contributions only, forecasts labelled
        val goalContributions = contributionEntities.map {
            GoalContribution(it.goalId, it.amountCents, it.timestampEpochMillis, it.accountId)
        }
        val savingsGoals = goalEntities.map { entity ->
            SavingsGoal(
                id = entity.id,
                title = entity.title,
                targetCents = entity.targetCents,
                savedCents = goalContributions.filter { it.goalId == entity.id }
                    .sumOf { it.amountCents },
                deadlineMillis = entity.deadlineMillis,
                linkedAccountId = entity.linkedAccountId.takeIf { it.isNotBlank() },
                createdAtMillis = entity.createdAtMillis
            )
        }
        val goalForecasts = savingsGoals.map {
            SavingsGoalEngine.forecast(it, goalContributions, now)
        }

        // ---- calendar for the browsed month
        val calendarSummary = CalendarEngine.build(
            incomingRows = allIncomes.map { it.timestampEpochMillis to it.amountCents },
            outgoingRows = allExpenses.map {
                it.timestampEpochMillis to (it.amountCents + it.feeCents)
            },
            pendingTimestamps = pending.map { it.timestampEpochMillis },
            monthYear = month.year,
            month = month.monthValue
        )

        // ---- observations for the browsed month (selected month vs previous)
        val zone = ZoneId.systemDefault()
        val monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEndExclusive = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val previous = month.minusMonths(1)
        val prevStart = previous.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthExpenses = allExpenses.filter {
            it.timestampEpochMillis in monthStart until monthEndExclusive
        }
        val prevExpenses = allExpenses.filter {
            it.timestampEpochMillis in prevStart until monthStart
        }
        val monthIncomes = allIncomes.filter {
            it.timestampEpochMillis in monthStart until monthEndExclusive
        }
        val nowMonth = YearMonth.now()
        val isCurrentMonth = month == nowMonth
        val dayCount = month.lengthOfMonth()
        val daysElapsed = when {
            isCurrentMonth -> LocalDate.now(zone).dayOfMonth.coerceAtMost(dayCount)
            month.isBefore(nowMonth) -> dayCount            // past month: fully elapsed
            else -> 1                                        // future month
        }

        val dayTotals = monthExpenses
            .groupBy { it.timestampEpochMillis / 86_400_000L }
            .mapValues { (_, rows) -> rows.sumOf { it.amountCents + it.feeCents } }
        val averageDay = if (dayCount > 0) {
            (monthExpenses.sumOf { it.amountCents + it.feeCents } / dayCount)
        } else 0L
        val unusualDay = dayTotals.entries
            .filter { it.value > averageDay * 2 && it.value >= 10_000L }
            .maxByOrNull { it.value }

        val reconciliationWarnings = accountStates.count { state ->
            state.reconciliation?.isInAgreement == false
        }
        val largest = monthExpenses.maxByOrNull { it.amountCents }
        val insights = InsightsEngine.compute(
            InsightInput(
                currentMonthIncomeCents = monthIncomes.sumOf { it.amountCents },
                currentMonthExpenseCents = monthExpenses.sumOf { it.amountCents },
                previousMonthExpenseCents = prevExpenses.sumOf { it.amountCents },
                currentMonthFeesCents = monthExpenses.sumOf { it.feeCents },
                perCategoryCurrentCents = monthExpenses.groupBy { it.categoryId }
                    .mapValues { (_, rows) -> rows.sumOf { it.amountCents } },
                perCategoryPreviousCents = prevExpenses.groupBy { it.categoryId }
                    .mapValues { (_, rows) -> rows.sumOf { it.amountCents } },
                budgets = currentBudgets.associate { it.categoryId to it.limitCents },
                largestExpenseCents = largest?.amountCents ?: 0L,
                largestExpenseMerchant = largest?.title,
                dayCountInMonth = dayCount,
                daysElapsedInMonth = daysElapsed,
                accountReconciliationWarnings = reconciliationWarnings,
                pendingReviewCount = pending.size,
                unusualDayCents = unusualDay?.value,
                unusualDayLabel = unusualDay?.key?.let {
                    LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
                }
            )
        )

        return SmartLedgerUiState(
            accountStates = accountStates,
            accounts = accounts,
            transfers = transfers,
            netWorthCents = AccountBalanceEngine.netWorth(accountStates),
            reconciliationWarningCount = reconciliationWarnings,
            pendingDetections = pending,
            recentDetections = allDetections.take(20),
            smartSettings = settings,
            lastIngestSummary = summary,
            lastBatchId = allDetections.maxByOrNull { it.createdAtMillis }?.batchId ?: batch,
            recurringRules = rules,
            recurringCandidates = candidates,
            forecastItems = forecastItems,
            savingsGoals = savingsGoals,
            goalForecasts = goalForecasts,
            contributions = contributionEntities,
            calendarSummary = calendarSummary,
            insights = insights,
            categoryRules = categoryRules,
            statusMessage = status
        )
    }

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
        // Fees are real debits too — subtracting them here keeps this fallback
        // identical to the per-account engine's net worth.
        val allExpensesTotal = snap.allExpenses.sumOf { it.amountCents }
        val feesTotal = snap.allExpenses.sumOf { it.feeCents }
        val availableBalance = openingBalance + otherIncome - allExpensesTotal - feesTotal

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

    // ------------------------------------------------- intelligence actions

    fun clearSmartStatusMessage() {
        smartStatusMessage.tryEmit(null)
    }

    private fun smartStatus(message: String) {
        smartStatusMessage.tryEmit(message)
    }

    // ---- accounts ----------------------------------------------------------

    fun createAccount(name: String, kind: AccountKind, openingBalanceCents: Long, note: String = "") {
        viewModelScope.launch {
            runCatching { repository.createAccount(name, kind, openingBalanceCents, note) }
                .onSuccess { smartStatus("Account '$name' created.") }
                .onFailure { smartStatus(it.message ?: "Could not create the account.") }
        }
    }

    fun renameAccount(accountId: String, newName: String) {
        viewModelScope.launch {
            runCatching { repository.renameAccount(accountId, newName) }
                .onSuccess { smartStatus("Account renamed to '$newName'.") }
                .onFailure { smartStatus(it.message ?: "Could not rename the account.") }
        }
    }

    fun setAccountNote(accountId: String, note: String) {
        viewModelScope.launch {
            runCatching { repository.setAccountNote(accountId, note) }
                .onSuccess { smartStatus("Message hint saved — matching messages will attach to this account.") }
                .onFailure { smartStatus(it.message ?: "Could not save the hint.") }
        }
    }

    fun setAccountOpeningBalance(accountId: String, openingBalanceCents: Long) {
        viewModelScope.launch {
            runCatching { repository.setAccountOpeningBalance(accountId, openingBalanceCents) }
                .onSuccess { smartStatus("Opening balance saved; the balance recalculated from history.") }
                .onFailure { smartStatus(it.message ?: "Could not save the opening balance.") }
        }
    }

    fun setAccountArchived(accountId: String, archived: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setAccountArchived(accountId, archived) }
                .onSuccess {
                    smartStatus(if (archived) "Account archived; its history is kept." else "Account restored.")
                }
                .onFailure { smartStatus(it.message ?: "Could not change the account.") }
        }
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteAccount(accountId) }
                .onSuccess { smartStatus("Account deleted.") }
                .onFailure { smartStatus(it.message ?: "This account cannot be deleted.") }
        }
    }

    fun recordAccountAdjustment(accountId: String, deltaCents: Long, note: String = "") {
        viewModelScope.launch {
            runCatching { repository.recordAccountAdjustment(accountId, deltaCents, "MANUAL", note) }
                .onSuccess { smartStatus("Adjustment saved; the balance recalculated.") }
                .onFailure { smartStatus(it.message ?: "Could not record the adjustment.") }
        }
    }

    fun createTransfer(
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        feeCents: Long = 0L,
        note: String = ""
    ) {
        viewModelScope.launch {
            runCatching {
                repository.createTransfer(fromAccountId, toAccountId, amountCents, feeCents, note)
            }
                .onSuccess {
                    smartStatus(
                        "Transfer saved. Balances moved — income and expense totals are unchanged by design."
                    )
                }
                .onFailure { smartStatus(it.message ?: "Could not save the transfer.") }
        }
    }

    fun deleteTransfer(transferId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteTransfer(transferId) }
                .onSuccess { smartStatus("Transfer removed; balances recalculated.") }
                .onFailure { smartStatus(it.message ?: "Could not remove the transfer.") }
        }
    }

    // ---- smart SMS ---------------------------------------------------------

    fun setSmsEnabled(enabled: Boolean) {
        repository.updateSmartSettings { it.copy(smsEnabled = enabled) }
        smartStatus(
            if (enabled) {
                "Smart tracking on. Messages are read on this device only — " +
                    "nothing is uploaded, and bodies are never stored."
            } else {
                "Smart tracking off. Manual tracking keeps working exactly as before."
            }
        )
    }

    fun setSmsImportMode(mode: SmsImportMode) {
        repository.updateSmartSettings { it.copy(smsImportMode = mode) }
        smartStatus(
            when (mode) {
                SmsImportMode.REVIEW -> "Import mode: everything waits for your review first."
                SmsImportMode.AUTO -> "Import mode: confident transactions save immediately; " +
                    "ambiguous ones still wait for review."
            }
        )
    }

    /** Date-filtered historical import (requires READ_SMS, requested by the UI). */
    fun importSmsHistory(context: Context, fromMillis: Long, toMillis: Long) =
        importSmsHistoryAs(context, fromMillis, toMillis, repository.smartSettings.value.smsImportMode)

    /**
     * Import with an explicit one-shot mode — the initial-import flow lets the
     * user choose "auto-record the confident ones" or "review everything first"
     * without silently rewriting their saved default mode.
     */
    fun importSmsHistoryAs(context: Context, fromMillis: Long, toMillis: Long, mode: SmsImportMode) {
        viewModelScope.launch {
            runCatching {
                val messages = SmsHistoryReader.read(context.applicationContext, fromMillis, toMillis)
                if (messages.isEmpty()) return@runCatching null
                repository.ingestSmsBatch(messages, mode)
            }.fold(
                onSuccess = { summary ->
                    if (summary == null) {
                        smartStatus("No messages found in that date range.")
                    } else {
                        lastIngestSummary.tryEmit(summary)
                        lastBatchId.tryEmit(summary.batchId)
                        smartStatus(describeSummary(summary))
                    }
                },
                onFailure = {
                    smartStatus(it.message ?: "Import failed — check that SMS permission is granted.")
                }
            )
        }
    }

    /**
     * Dry-run the same range: parses and plans but writes nothing. [onResult]
     * receives null when reading failed (permission denied/revoked) — the
     * reason is placed in smart status, which the UI already surfaces.
     */
    fun previewSmsHistory(
        context: Context,
        fromMillis: Long,
        toMillis: Long,
        onResult: (SmsImportPreview?) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                repository.previewSmsBatch(
                    SmsHistoryReader.read(context.applicationContext, fromMillis, toMillis)
                )
            }.fold(
                onSuccess = { preview ->
                    smartStatus(
                        if (preview.totalMessages == 0) "No messages found in that date range."
                        else "Preview ready — nothing has been imported yet."
                    )
                    onResult(preview)
                },
                onFailure = {
                    smartStatus(
                        it.message ?: "Could not read messages — check that SMS permission is granted."
                    )
                    onResult(null)
                }
            )
        }
    }

    /**
     * Real connectivity diagnostic: sends ONE trivial "OK" request to the
     * configured provider and reports the typed outcome. No SMS and no ledger
     * data is transmitted. [onResult] receives a credential-free description.
     */
    fun testAiConnection(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { repository.testAiConnection() }
                .getOrElse { AiConnectionProbe.Result.NetworkError }
            val description = AiConnectionProbe.describe(result)
            smartStatus(description)
            onResult(description)
        }
    }

    /**
     * Optional AI narrative over the month's locally-computed figures. The
     * numbers stay authoritative; this only asks the model to describe them.
     * Returns null (and shows no narrative) on any failure or when AI is off.
     */
    fun generateAiNarrative(
        incomeCents: Long,
        expenseCents: Long,
        topCategories: List<Pair<String, Long>>,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            val narrative = runCatching {
                repository.generateAiNarrative(incomeCents, expenseCents, topCategories)
            }.getOrNull()
            onResult(narrative)
        }
    }

    /** Manual paste alternative — no SMS permission needed at all. */
    fun importPastedSms(text: String) {
        viewModelScope.launch {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                smartStatus("Paste a message first.")
                return@launch
            }
            runCatching {
                repository.ingestSmsBatch(
                    listOf(RawInboundSms(sender = "Pasted text", body = trimmed, receivedAtMillis = System.currentTimeMillis())),
                    repository.smartSettings.value.smsImportMode
                )
            }.fold(
                onSuccess = { summary ->
                    lastIngestSummary.tryEmit(summary)
                    lastBatchId.tryEmit(summary.batchId)
                    smartStatus(describeSummary(summary))
                },
                onFailure = { smartStatus(it.message ?: "Could not read that text as a transaction.") }
            )
        }
    }

    fun undoLastImport() {
        viewModelScope.launch {
            runCatching {
                val batch = repository.latestDetectionBatchId()
                    ?: error("There is no recent import to undo.")
                batch to repository.undoSmsBatch(batch)
            }.fold(
                onSuccess = { (_, removed) ->
                    lastIngestSummary.tryEmit(null)
                    lastBatchId.tryEmit(null)
                    smartStatus("Import undone — $removed record(s) removed; totals recalculated.")
                },
                onFailure = { smartStatus(it.message ?: "Nothing to undo.") }
            )
        }
    }

    fun clearSmsImportHistory() {
        viewModelScope.launch {
            runCatching { repository.clearSmsImportHistory() }
                .onSuccess {
                    lastIngestSummary.tryEmit(null)
                    lastBatchId.tryEmit(null)
                    smartStatus(
                        "SMS import history and review queue cleared. Recorded transactions are untouched."
                    )
                }
                .onFailure { smartStatus(it.message ?: "Could not clear the history.") }
        }
    }

    fun approveDetection(detectionId: Long, approval: DetectionApproval = DetectionApproval()) {
        viewModelScope.launch {
            repository.approveDetection(detectionId, approval).fold(
                onSuccess = { smartStatus("Transaction confirmed and recorded.") },
                onFailure = { smartStatus(it.message ?: "Could not confirm this transaction.") }
            )
        }
    }

    fun rejectDetection(detectionId: Long) {
        viewModelScope.launch {
            runCatching { repository.rejectDetection(detectionId) }
                .onSuccess { smartStatus("Rejected — nothing was recorded.") }
                .onFailure { smartStatus(it.message ?: "Could not reject this detection.") }
        }
    }

    // ---- notifications & app lock -----------------------------------------

    fun setNotifyNewDetections(enabled: Boolean) =
        repository.updateSmartSettings { it.copy(notifyNewDetections = enabled) }

    fun setNotifyBudgetWarnings(enabled: Boolean) =
        repository.updateSmartSettings { it.copy(notifyBudgetWarnings = enabled) }

    fun setNotifyReconciliation(enabled: Boolean) =
        repository.updateSmartSettings { it.copy(notifyReconciliation = enabled) }

    fun setNotifyRecurringDue(enabled: Boolean) =
        repository.updateSmartSettings { it.copy(notifyRecurringDue = enabled) }

    fun setAppLockEnabled(enabled: Boolean) {
        repository.updateSmartSettings { it.copy(appLockEnabled = enabled) }
        smartStatus(
            if (enabled) {
                "App lock on: it hides the app behind your fingerprint, face or device " +
                    "unlock. It is a privacy screen — it does not encrypt your data."
            } else {
                "App lock off."
            }
        )
    }

    // ---- recurring ---------------------------------------------------------

    fun saveRecurringRule(
        id: String?,
        title: String,
        amountCents: Long,
        categoryId: String,
        intervalDays: Int,
        nextDueMillis: Long,
        accountId: String = ""
    ) {
        viewModelScope.launch {
            runCatching {
                repository.saveRecurringRule(id, title, amountCents, categoryId, intervalDays, nextDueMillis, accountId)
            }
                .onSuccess { smartStatus("Recurring payment saved. Forecasts never count as actual spending.") }
                .onFailure { smartStatus(it.message ?: "Could not save the recurring payment.") }
        }
    }

    fun deleteRecurringRule(ruleId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteRecurringRule(ruleId) }
                .onSuccess { smartStatus("Recurring payment removed.") }
                .onFailure { smartStatus(it.message ?: "Could not remove it.") }
        }
    }

    fun setRecurringRuleActive(ruleId: String, active: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setRecurringRuleActive(ruleId, active) }
                .onFailure { smartStatus(it.message ?: "Could not change the state.") }
        }
    }

    fun markRecurringPaid(ruleId: String) {
        viewModelScope.launch {
            runCatching { repository.markRecurringPaid(ruleId) }
                .onSuccess { smartStatus("Marked as paid; a real expense was recorded and the next date advanced.") }
                .onFailure { smartStatus(it.message ?: "Could not mark it as paid.") }
        }
    }

    /** Accept a detected pattern — detection proposes, this confirms, nothing auto-saves. */
    fun confirmRecurringCandidate(candidate: RecurringCandidate, categoryId: String?) {
        viewModelScope.launch {
            runCatching {
                repository.saveRecurringRule(
                    id = null,
                    title = candidate.merchant,
                    amountCents = candidate.avgAmountCents,
                    categoryId = categoryId ?: candidate.categoryId ?: CategoryEntity.UNCATEGORIZED_ID,
                    intervalDays = candidate.intervalDays,
                    nextDueMillis = candidate.expectedNextMillis,
                    accountId = candidate.accountHint ?: ""
                )
            }
                .onSuccess { smartStatus("'${candidate.merchant}' saved as a recurring payment.") }
                .onFailure { smartStatus(it.message ?: "Could not save this rule.") }
        }
    }

    // ---- savings goals -----------------------------------------------------

    fun saveSavingsGoal(
        id: String?,
        title: String,
        targetCents: Long,
        deadlineMillis: Long?,
        linkedAccountId: String = ""
    ) {
        viewModelScope.launch {
            runCatching { repository.saveSavingsGoal(id, title, targetCents, deadlineMillis, linkedAccountId) }
                .onSuccess { smartStatus("Goal saved. Contributions are progress labels, not extra spending.") }
                .onFailure { smartStatus(it.message ?: "Could not save the goal.") }
        }
    }

    fun deleteSavingsGoal(goalId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteSavingsGoal(goalId) }
                .onSuccess { smartStatus("Goal removed.") }
                .onFailure { smartStatus(it.message ?: "Could not remove the goal.") }
        }
    }

    fun addGoalContribution(goalId: String, amountCents: Long, accountId: String = "") {
        viewModelScope.launch {
            runCatching { repository.addGoalContribution(goalId, amountCents, accountId) }
                .onSuccess { smartStatus("Contribution recorded — progress updated; account balance unchanged.") }
                .onFailure { smartStatus(it.message ?: "Could not record the contribution.") }
        }
    }

    fun deleteGoalContribution(contributionId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteGoalContribution(contributionId) }
                .onSuccess { smartStatus("Contribution removed.") }
                .onFailure { smartStatus(it.message ?: "Could not remove it.") }
        }
    }

    // ---- categorisation rules ---------------------------------------------

    fun saveCategoryRule(keyword: String, categoryId: String) {
        viewModelScope.launch {
            runCatching { repository.saveCategoryRule(keyword, categoryId) }
                .onSuccess { smartStatus("Rule saved: messages containing '$keyword' will suggest this category.") }
                .onFailure { smartStatus(it.message ?: "Could not save the rule.") }
        }
    }

    fun deleteCategoryRule(ruleId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteCategoryRule(ruleId) }
                .onSuccess { smartStatus("Rule removed.") }
                .onFailure { smartStatus(it.message ?: "Could not remove the rule.") }
        }
    }

    private fun describeSummary(summary: SmsIngestSummary): String = buildString {
        append("${summary.totalMessages} message(s) read — ")
        append("${summary.recorded} recorded, ${summary.queuedForReview} waiting for review")
        if (summary.duplicates > 0) append(", ${summary.duplicates} duplicate(s) skipped")
        if (summary.ignored > 0) append(", ${summary.ignored} not financial")
        append(". No message bodies were stored.")
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
                val transfers = repository.snapshotTransfers()
                val detections = repository.snapshotDetections()
                val accounts = repository.snapshotAccounts()
                val accountStates = repository.accountStatesOnce()

                val categoryLabel: (String) -> String =
                    { id -> categories.firstOrNull { it.id == id }?.displayName
                        ?: SpendingRepository.DEFAULT_CATEGORIES.firstOrNull { it.id == id }?.displayName
                        ?: id }
                val accountName: (String) -> String =
                    { id -> accounts.firstOrNull { it.id == id }?.name ?: id }

                val report = LedgerReportBuilder.build(
                    range = range,
                    allExpenses = allExpenses,
                    allIncomes = allIncomes,
                    zone = zone,
                    categoryLabel = categoryLabel,
                    transfers = transfers,
                    pendingDetections = detections,
                    accountName = accountName,
                    accountBalances = accountStates.filter { !it.isArchived }.map { state ->
                        com.kanso.spending.domain.ReportAccountBalance(
                            name = state.name,
                            typeLabel = state.type.name,
                            balanceCents = state.calculatedBalanceCents
                        )
                    },
                    reconciliationWarnings = accountStates.mapNotNull { state ->
                        val rec = state.reconciliation ?: return@mapNotNull null
                        if (rec.isInAgreement) null
                        else com.kanso.spending.domain.ReportReconciliationWarning(
                            name = state.name,
                            reportedCents = rec.reportedBalanceCents,
                            ledgerCents = state.calculatedBalanceCents,
                            differenceCents = rec.differenceCents
                        )
                    }
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
