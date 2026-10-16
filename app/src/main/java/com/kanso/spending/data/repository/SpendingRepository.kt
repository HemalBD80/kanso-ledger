package com.kanso.spending.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.kanso.spending.data.ai.AiSettingsStore
import com.kanso.spending.data.ai.HttpAiTransport
import com.kanso.spending.data.local.LocalBackupManager
import com.kanso.spending.data.local.dao.SpendingDao
import com.kanso.spending.data.local.entity.AccountAdjustmentEntity
import com.kanso.spending.data.local.entity.AccountEntity
import com.kanso.spending.data.local.entity.AccountKind
import com.kanso.spending.data.local.entity.AuditLogEntity
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.CategoryRuleEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.GoalContributionEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity
import com.kanso.spending.data.local.entity.RecurringRuleEntity
import com.kanso.spending.data.local.entity.SavingsGoalEntity
import com.kanso.spending.data.local.entity.SmsDetectionEntity
import com.kanso.spending.data.local.entity.SmsMetaEntity
import com.kanso.spending.data.local.entity.TransferEntity
import com.kanso.spending.data.model.MoneySource
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.domain.accounts.AccountState
import com.kanso.spending.domain.accounts.AccountStateFactory
import com.kanso.spending.domain.ai.AiSmsAugmenter
import com.kanso.spending.domain.ai.AiSmsInterpreter
import com.kanso.spending.domain.categorization.CategoryRule
import com.kanso.spending.domain.categorization.CategorySuggester
import com.kanso.spending.domain.sms.DuplicateNotice
import com.kanso.spending.domain.sms.IngestContext
import com.kanso.spending.domain.sms.LedgerAction
import com.kanso.spending.domain.sms.RawInboundSms
import com.kanso.spending.domain.sms.SmsImportMode
import com.kanso.spending.domain.sms.SmsIngestPlanner
import com.kanso.spending.domain.sms.SmsTxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
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
 * Overrides a detection can carry at approval time. Every field is optional:
 * an empty approval accepts exactly what the parser extracted (which the user
 * has just reviewed), never anything guessed.
 */
data class DetectionApproval(
    val categoryId: String? = null,
    val accountId: String? = null,
    val transferFromAccountId: String? = null,
    val transferToAccountId: String? = null,
    val amountCents: Long? = null,
    val title: String? = null,
    val note: String? = null,
    /** RECORD_EXPENSE / RECORD_INCOME — lets review resolve unclear messages. */
    val action: LedgerAction? = null,
    /** Keyword that becomes a permanent categorisation rule when present. */
    val saveAsRuleKeyword: String? = null
)

/** Outcome of one [SpendingRepository.ingestSmsBatch] call. */
data class SmsIngestSummary(
    val totalMessages: Int,
    val recorded: Int,
    val queuedForReview: Int,
    val duplicates: Int,
    val ignored: Int,
    val batchId: String
)

/**
 * Dry-run outcome of a candidate historical import. Previewing writes
 * nothing: no fingerprints, no detections, no ledger rows — the user sees
 * what would happen (including which messages are duplicates of what they
 * already have) before choosing auto-record or review-first.
 */
data class SmsImportPreview(
    val totalMessages: Int,
    /** High-confidence, unambiguous: would record immediately in AUTO mode. */
    val confidentCount: Int,
    /** Ambiguous or needs-review: waits for approval in either mode. */
    val queueCount: Int,
    /** Already-known fingerprints: skipped by the idempotent import. */
    val duplicateCount: Int,
    /** Not financial (OTP, promo, personal chatter): ignored entirely. */
    val ignoredCount: Int,
    /** Balance notices only: recognised but never become transactions. */
    val balanceNoticeCount: Int,
    /** Parsed fields of what would be imported — message bodies never. */
    val samples: List<SmsPreviewSample>,
    /** Skipped duplicates, structured only, for inspection. */
    val duplicateItems: List<DuplicateNotice>
)

/** One parsed preview row. Never contains the SMS body. */
data class SmsPreviewSample(
    val provider: String,
    val merchantOrCounterparty: String,
    val amountCents: Long?,
    val feeCents: Long,
    val txType: String,
    val confidence: Int,
    val referenceId: String?
)

/**
 * User-facing switches for the intelligence features. Off by default:
 * SMS import must be enabled deliberately, never silently.
 */
data class SmartSettings(
    val smsEnabled: Boolean = false,
    val smsImportMode: SmsImportMode = SmsImportMode.REVIEW,
    val notifyNewDetections: Boolean = true,
    val notifyBudgetWarnings: Boolean = true,
    val notifyReconciliation: Boolean = true,
    val notifyRecurringDue: Boolean = true,
    val appLockEnabled: Boolean = false
)

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

    // ------------------------------------------------------------- AI assist
    //
    // Optional, off by default, and gated three ways before a single byte
    // leaves the device: the user must enable AI, consent to external
    // processing, and supply their own credential. When any gate is closed the
    // augmenter routes every message to the local parser — byte-for-byte the
    // offline behaviour the app had before AI existed.

    private val aiSettings = AiSettingsStore(context.applicationContext)
    private val aiInterpreter = AiSmsInterpreter(HttpAiTransport())

    private val aiAugmenter = AiSmsAugmenter(
        consultAi = { message ->
            withContext(Dispatchers.IO) {
                aiInterpreter.interpret(
                    config = aiSettings.read(),
                    sender = message.sender,
                    body = message.body,
                    receivedAtMillis = message.receivedAtMillis
                )
            }
        },
        enabled = {
            aiSettings.aiEnabled && aiSettings.aiConsentGranted && aiSettings.read().isConfigured
        }
    )

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
            currencySymbol = _currencySymbol.value,
            accounts = spendingDao.getAllAccountsSnapshot(),
            transfers = spendingDao.getAllTransfersSnapshot(),
            adjustments = spendingDao.getAllAdjustmentsSnapshot(),
            recurringRules = spendingDao.getAllRecurringRulesSnapshot(),
            savingsGoals = spendingDao.getAllSavingsGoalsSnapshot(),
            goalContributions = spendingDao.getAllGoalContributionsSnapshot(),
            categoryRules = spendingDao.getAllCategoryRulesSnapshot(),
            smsMetas = spendingDao.getAllSmsMetasSnapshot(),
            smsDetections = spendingDao.getAllDetectionsSnapshot()
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
                categories = categoriesToInsert,
                accounts = snapshot.accounts,
                transfers = snapshot.transfers,
                adjustments = snapshot.adjustments,
                recurringRules = snapshot.recurringRules,
                savingsGoals = snapshot.savingsGoals,
                goalContributions = snapshot.goalContributions,
                categoryRules = snapshot.categoryRules
            )
            // v1/v2 snapshots carry no accounts; either way the four structural
            // accounts must exist after a restore.
            seedDefaultAccountsIfMissing()
            // Older snapshots without sms tables still need dedup protection
            // from the ledger rows' own fingerprints — that is automatic.
            if (snapshot.smsMetas.isNotEmpty() || snapshot.smsDetections.isNotEmpty()) {
                snapshot.smsMetas.forEach { spendingDao.insertSmsMetaIfNew(it) }
                snapshot.smsDetections.forEach { spendingDao.insertDetectionIfNew(it) }
            }
            setCurrencySymbol(snapshot.currencySymbol)
            logAudit("vault", "restore", "restored", "Vault restored from local snapshot (schema v" +
                "${if (snapshot.accounts.isNotEmpty()) 3 else 1}): " +
                "${sanitizedExpenses.size} expenses, ${snapshot.incomes.size} money-in records, " +
                "${snapshot.transfers.size} transfers, ${snapshot.savingsGoals.size} goals")
            (sanitizedExpenses.size + snapshot.incomes.size)
        }
    }

    suspend fun wipeAllData() {
        spendingDao.wipeAllLocalData()
        seedDefaultCategoriesIfMissing()
        seedDefaultAccountsIfMissing()
        logAudit("vault", "wipe", "wiped", "Local vault wiped clean and defaults re-seeded")
    }

    // ------------------------------------------------------------- settings

    private val _smartSettings = MutableStateFlow(loadSmartSettings())
    val smartSettings: StateFlow<SmartSettings> = _smartSettings.asStateFlow()

    private fun loadSmartSettings() = SmartSettings(
        smsEnabled = prefs.getBoolean(KEY_SMS_ENABLED, false),
        smsImportMode = runCatching {
            SmsImportMode.valueOf(prefs.getString(KEY_SMS_MODE, SmsImportMode.REVIEW.name) ?: "")
        }.getOrDefault(SmsImportMode.REVIEW),
        notifyNewDetections = prefs.getBoolean(KEY_NOTIF_DETECTED, true),
        notifyBudgetWarnings = prefs.getBoolean(KEY_NOTIF_BUDGET, true),
        notifyReconciliation = prefs.getBoolean(KEY_NOTIF_RECONCILE, true),
        notifyRecurringDue = prefs.getBoolean(KEY_NOTIF_RECURRING, true),
        appLockEnabled = prefs.getBoolean(KEY_APP_LOCK, false)
    )

    fun updateSmartSettings(transform: (SmartSettings) -> SmartSettings) {
        val next = transform(_smartSettings.value)
        prefs.edit()
            .putBoolean(KEY_SMS_ENABLED, next.smsEnabled)
            .putString(KEY_SMS_MODE, next.smsImportMode.name)
            .putBoolean(KEY_NOTIF_DETECTED, next.notifyNewDetections)
            .putBoolean(KEY_NOTIF_BUDGET, next.notifyBudgetWarnings)
            .putBoolean(KEY_NOTIF_RECONCILE, next.notifyReconciliation)
            .putBoolean(KEY_NOTIF_RECURRING, next.notifyRecurringDue)
            .putBoolean(KEY_APP_LOCK, next.appLockEnabled)
            .apply()
        _smartSettings.value = next
    }

    // ------------------------------------------------ first-launch smart setup

    /**
     * The one-time setup screen (Smart SMS onboarding) writes its outcome here
     * so it appears exactly once. Absent = first launch, no choice made yet.
     */
    fun isSmartSetupDone(): Boolean = prefs.getBoolean(KEY_SMART_SETUP_DONE, false)

    fun markSmartSetupDone() {
        prefs.edit().putBoolean(KEY_SMART_SETUP_DONE, true).apply()
    }

    // ------------------------------------------------------------- accounts

    fun observeAccounts(): Flow<List<AccountEntity>> = spendingDao.observeAccounts()

    fun observeTransfers(): Flow<List<TransferEntity>> = spendingDao.observeTransfers()

    fun observeAdjustments(): Flow<List<AccountAdjustmentEntity>> = spendingDao.observeAdjustments()

    suspend fun snapshotAccounts(): List<AccountEntity> = spendingDao.getAllAccountsSnapshot()

    /** Confirmed spend (principal + fees) inside a calendar month. */
    suspend fun monthSpentCents(yearMonth: YearMonth): Long {
        val zone = ZoneId.systemDefault()
        val start = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return spendingDao.getAllExpensesSnapshot()
            .filter { it.timestampEpochMillis in start until end }
            .sumOf { it.amountCents + it.feeCents }
    }

    suspend fun snapshotRecurringRules(): List<RecurringRuleEntity> =
        spendingDao.getAllRecurringRulesSnapshot()

    suspend fun snapshotTransfers(): List<TransferEntity> = spendingDao.getAllTransfersSnapshot()

    suspend fun snapshotDetections(): List<SmsDetectionEntity> = spendingDao.getAllDetectionsSnapshot()

    /** Seeds the four structural accounts (৳0.00) the first time v3 runs. */
    suspend fun seedDefaultAccountsIfMissing() {
        val existing = spendingDao.getAllAccountsSnapshot()
        val now = System.currentTimeMillis()
        AccountEntity.SEEDS.forEach { seed ->
            if (existing.none { it.id == seed.id }) {
                spendingDao.insertAccountIfMissing(seed.copy(createdAtMillis = now))
            }
        }
    }

    /**
     * Live per-account balances from the ledger itself: opening + activity +
     * transfers + adjustments, exactly what [AccountBalanceEngine] defines.
     */
    fun observeAccountStates(): Flow<List<AccountState>> = combine(
        spendingDao.observeAccounts(),
        spendingDao.observeAllExpenses(),
        spendingDao.observeAllIncome(),
        spendingDao.observeTransfers(),
        spendingDao.observeAdjustments()
    ) { accounts, expenses, incomes, transfers, adjustments ->
        AccountStateFactory.states(accounts, expenses, incomes, transfers, adjustments)
    }

    /** One-shot variant for reports. */
    suspend fun accountStatesOnce(): List<AccountState> = AccountStateFactory.states(
        accounts = spendingDao.getAllAccountsSnapshot(),
        expenses = spendingDao.getAllExpensesSnapshot(),
        incomes = spendingDao.getAllIncomeSnapshot(),
        transfers = spendingDao.getAllTransfersSnapshot(),
        adjustments = spendingDao.getAllAdjustmentsSnapshot()
    )

    suspend fun createAccount(
        name: String,
        kind: AccountKind,
        openingBalanceCents: Long,
        note: String = ""
    ): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Account name cannot be empty" }
        val existing = spendingDao.getAllAccountsSnapshot()
        require(existing.none { it.name.equals(trimmed, ignoreCase = true) }) {
            "An account with this name already exists"
        }
        val id = "acc_${System.currentTimeMillis()}_${auditCounter.incrementAndGet()}"
        val account = AccountEntity(
            id = id,
            name = trimmed,
            type = kind.name,
            openingBalanceCents = openingBalanceCents,
            sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 10,
            note = note.trim(),
            createdAtMillis = System.currentTimeMillis()
        )
        spendingDao.upsertAccount(account)
        logAudit(
            "account", id, "added",
            "Created ${kind.name} account '$trimmed' with opening balance ${openingBalanceCents} minor units"
        )
        return id
    }

    suspend fun renameAccount(accountId: String, newName: String) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Account name cannot be empty" }
        val account = spendingDao.findAccountById(accountId) ?: error("Unknown account $accountId")
        require(
            accountId in AccountEntity.STRUCTURAL_IDS ||
                spendingDao.getAllAccountsSnapshot().none {
                    it.id != accountId && it.name.equals(trimmed, ignoreCase = true)
                }
        ) { "An account with this name already exists" }
        spendingDao.upsertAccount(account.copy(name = trimmed))
        logAudit("account", accountId, "edited", "Renamed ${account.name} → '$trimmed'")
    }

    /** The note links provider messages to this account (e.g. "***4512"). */
    suspend fun setAccountNote(accountId: String, note: String) {
        val account = spendingDao.findAccountById(accountId) ?: error("Unknown account $accountId")
        spendingDao.upsertAccount(account.copy(note = note.trim()))
        logAudit("account", accountId, "edited", "Updated linked-message hint for ${account.name}")
    }

    suspend fun setAccountOpeningBalance(accountId: String, openingBalanceCents: Long) {
        val account = spendingDao.findAccountById(accountId) ?: error("Unknown account $accountId")
        spendingDao.upsertAccount(account.copy(openingBalanceCents = openingBalanceCents))
        logAudit(
            "account", accountId, "edited",
            "Opening balance of ${account.name} set to ${openingBalanceCents} minor units; " +
                "all activity recalculated automatically"
        )
    }

    suspend fun setAccountArchived(accountId: String, archived: Boolean) {
        val account = spendingDao.findAccountById(accountId) ?: error("Unknown account $accountId")
        spendingDao.upsertAccount(account.copy(isArchived = archived))
        logAudit(
            "account", accountId, if (archived) "archived" else "restored",
            (if (archived) "Archived " else "Restored ") + account.name +
                (if (archived) "; its history is kept and excluded from net totals" else "")
        )
    }

    /**
     * Deleting is guarded: structural accounts never, and custom accounts only
     * when their balance is ৳0.00 and nothing references them (archive is the
     * safe alternative, and is always offered first).
     */
    suspend fun deleteAccount(accountId: String) {
        require(accountId !in AccountEntity.STRUCTURAL_IDS) {
            "Built-in accounts cannot be deleted — archive them instead."
        }
        val account = spendingDao.findAccountById(accountId) ?: error("Unknown account $accountId")
        val balance = accountStatesOnce().firstOrNull { it.accountId == accountId }
            ?.calculatedBalanceCents ?: 0L
        require(balance == 0L) {
            "Account '${account.name}' still holds a balance — adjust or transfer it to ৳0.00 first."
        }
        require(spendingDao.countExpensesForAccount(accountId) == 0) {
            "Account '${account.name}' still has spending history — archive it instead."
        }
        require(spendingDao.countIncomeForAccount(accountId) == 0) {
            "Account '${account.name}' still has money-in history — archive it instead."
        }
        require(spendingDao.countTransfersForAccount(accountId) == 0) {
            "Account '${account.name}' is referenced by transfers — archive it instead."
        }
        spendingDao.deleteAccountById(accountId)
        logAudit("account", accountId, "deleted", "Deleted empty custom account '${account.name}'")
    }

    suspend fun recordAccountAdjustment(
        accountId: String,
        deltaCents: Long,
        kind: String = "MANUAL",
        note: String = ""
    ): Long {
        require(spendingDao.findAccountById(accountId) != null) { "Unknown account $accountId" }
        val id = spendingDao.insertAdjustment(
            AccountAdjustmentEntity(
                accountId = accountId,
                deltaCents = deltaCents,
                kind = kind,
                timestampEpochMillis = System.currentTimeMillis(),
                note = note.trim()
            )
        )
        logAudit(
            "account", accountId, "adjusted",
            "Adjustment of ${deltaCents} minor units (${kind}) recorded; balance recalculated"
        )
        return id
    }

    /** Stored beside the ledger — the calculated balance is never rewritten. */
    suspend fun setAccountReportedBalance(accountId: String, balanceCents: Long, atMillis: Long) {
        spendingDao.updateAccountReportedBalance(accountId, balanceCents, atMillis)
    }

    // ------------------------------------------------------------ transfers

    suspend fun createTransfer(
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        feeCents: Long = 0L,
        note: String = "",
        timestampEpochMillis: Long = System.currentTimeMillis()
    ): String {
        require(fromAccountId != toAccountId) { "Choose two different accounts" }
        require(amountCents > 0L) { "Transfer amount must be greater than zero" }
        require(spendingDao.findAccountById(fromAccountId) != null) { "Unknown source account" }
        require(spendingDao.findAccountById(toAccountId) != null) { "Unknown destination account" }
        val id = "tr_${System.currentTimeMillis()}_${auditCounter.incrementAndGet()}"
        spendingDao.upsertTransfer(
            TransferEntity(
                id = id,
                fromAccountId = fromAccountId,
                toAccountId = toAccountId,
                amountCents = amountCents,
                feeCents = feeCents,
                timestampEpochMillis = timestampEpochMillis,
                note = note.trim()
            )
        )
        logAudit(
            "transfer", id, "added",
            "Moved ${amountCents} minor units between accounts (fee ${feeCents}); " +
                "income and expense totals unchanged by design"
        )
        return id
    }

    suspend fun deleteTransfer(transferId: String) {
        spendingDao.deleteTransferById(transferId)
        logAudit("transfer", transferId, "deleted", "Removed transfer $transferId; balances recalculated")
    }

    // --------------------------------------------------------- SMS ingestion

    fun observeDetections(): Flow<List<SmsDetectionEntity>> = spendingDao.observeDetections()

    fun observePendingDetections(): Flow<List<SmsDetectionEntity>> =
        spendingDao.observePendingDetections()

    /** Most recent import batch, so "undo last import" has a target. */
    suspend fun latestDetectionBatchId(): String? =
        spendingDao.getAllDetectionsSnapshot().firstOrNull()?.batchId

    /** Shared facts for one batch: known fingerprints (ledger + SMS meta) and account hints. */
    private suspend fun buildIngestContext(mode: SmsImportMode, batchId: String): IngestContext {
        val accounts = spendingDao.getAllAccountsSnapshot()
        val known = HashSet<String>(spendingDao.getAllKnownFingerprints())
        known.addAll(spendingDao.getAllLedgerFingerprints())
        return IngestContext(
            knownFingerprints = known,
            mode = mode,
            batchId = batchId,
            nowMillis = System.currentTimeMillis(),
            hintToAccountId = accounts.filter { it.note.isNotBlank() }
                .associate { it.note.lowercase() to it.id },
            nameToAccountId = accounts.associate { it.name.lowercase() to it.id }
        )
    }

    /**
     * Parse, de-duplicate and persist one batch of messages. Nothing here ever
     * stores a message body — only fingerprints, structured fields, and the
     * ledger rows the mode (or the later review) commits.
     */
    suspend fun ingestSmsBatch(
        messages: List<RawInboundSms>,
        mode: SmsImportMode = smartSettings.value.smsImportMode
    ): SmsIngestSummary {
        if (messages.isEmpty()) return SmsIngestSummary(0, 0, 0, 0, 0, "")
        seedDefaultAccountsIfMissing()

        val batchId = "batch_${System.currentTimeMillis()}_${auditCounter.incrementAndGet()}"
        val context = buildIngestContext(mode, batchId)
        // AI assist (when the user has enabled + consented + configured it)
        // resolves only the messages the local parser found ambiguous. Every
        // failure degrades to that local result — an AI outage never blocks,
        // drops, or guesses a transaction.
        val aiResult = aiAugmenter.buildOverrides(messages)
        val plan = SmsIngestPlanner.plan(messages, context, aiResult.overrides)
        val now = context.nowMillis

        for (item in plan.items) {
            spendingDao.insertSmsMetaIfNew(
                SmsMetaEntity(
                    fingerprint = item.fingerprint,
                    sender = item.sender,
                    provider = item.provider,
                    receivedAtMillis = item.receivedAtMillis,
                    processedAtMillis = now,
                    outcome = item.metaOutcome,
                    txType = item.txType
                )
            )
            val planned = item.detection ?: continue
            val tx = planned.parsed
            val detection = SmsDetectionEntity(
                fingerprint = item.fingerprint,
                sender = item.sender,
                provider = tx.provider,
                txType = tx.type.name,
                action = tx.action.name,
                amountCents = tx.amountCents,
                feeCents = tx.feeCents,
                timestampEpochMillis = tx.timestampMillis,
                merchant = tx.merchantOrCounterparty,
                refId = tx.referenceId ?: "",
                accountHint = tx.accountHint ?: "",
                reportedBalanceCents = tx.reportedBalanceCents,
                confidence = tx.confidence,
                reviewReason = tx.reviewReason ?: "",
                reviewStatus = planned.reviewStatus,
                categoryId = suggestCategoryId(
                    listOf(tx.merchantOrCounterparty, tx.provider)
                        .filter { it.isNotBlank() }.joinToString(" ")
                ),
                accountId = planned.resolvedAccountId,
                batchId = batchId,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            val detectionId = spendingDao.insertDetectionIfNew(detection)
            if (detectionId == -1L) continue // lost a race with a parallel import

            var finalDetection = detection.copy(id = detectionId)
            if (planned.writeLedger) {
                val outcome = runCatching { materializeDetection(finalDetection, DetectionApproval()) }
                val link = outcome.getOrNull()
                if (link != null) {
                    finalDetection = finalDetection.copy(
                        linkedRecordType = link.first,
                        linkedRecordId = link.second
                    )
                } else {
                    // Something about this message prevents a safe write
                    // (e.g. a withdrawal we cannot place): queue it for human
                    // review instead of guessing or crashing.
                    finalDetection = finalDetection.copy(
                        reviewStatus = "PENDING",
                        reviewReason = outcome.exceptionOrNull()?.message ?: "needs manual setup"
                    )
                }
            }
            if (finalDetection.reviewStatus == "APPROVED") {
                applyReportedBalance(finalDetection)
            }
            if (finalDetection != detection.copy(id = detectionId)) {
                spendingDao.updateDetection(finalDetection)
            }
        }

        if (plan.items.isNotEmpty()) {
            logAudit(
                "sms", batchId, "imported",
                "Imported ${plan.items.size} message(s): ${plan.recordedCount} recorded, " +
                    "${plan.pendingCount} queued for review, ${plan.duplicateCount} duplicate(s), " +
                    "${plan.ignoredCount} not financial (no content stored)"
            )
        }
        return SmsIngestSummary(
            totalMessages = messages.size,
            recorded = plan.recordedCount,
            queuedForReview = plan.pendingCount,
            duplicates = plan.duplicateCount,
            ignored = plan.ignoredCount,
            batchId = batchId
        )
    }

    /**
     * Dry run for a candidate historical import: parses and plans exactly the
     * way [ingestSmsBatch] would, but persists nothing — no meta rows, no
     * detections, no fingerprints, no ledger rows. Used by the first-launch
     * flow and the import preview so the user sees confident/queued/duplicate
     * counts before choosing how (or whether) to import.
     *
     * The counts are mode-independent: [SmsImportPreview.confidentCount] is
     * what AUTO mode would record now, [SmsImportPreview.queueCount] is what
     * either mode keeps waiting for review.
     */
    suspend fun previewSmsBatch(messages: List<RawInboundSms>): SmsImportPreview {
        if (messages.isEmpty()) {
            return SmsImportPreview(0, 0, 0, 0, 0, 0, emptyList(), emptyList())
        }
        val context = buildIngestContext(SmsImportMode.AUTO, "preview")
        // Preview runs the exact same AI-assist path as the real import, so the
        // confident/queued counts the user sees match what committing will do.
        val aiResult = aiAugmenter.buildOverrides(messages)
        val plan = SmsIngestPlanner.plan(messages, context, aiResult.overrides)
        val samples = plan.items.mapNotNull { item ->
            val planned = item.detection ?: return@mapNotNull null
            val tx = planned.parsed
            SmsPreviewSample(
                provider = tx.provider,
                merchantOrCounterparty = tx.merchantOrCounterparty,
                amountCents = tx.amountCents,
                feeCents = tx.feeCents,
                txType = tx.type.name,
                confidence = tx.confidence,
                referenceId = tx.referenceId
            )
        }
        return SmsImportPreview(
            totalMessages = messages.size,
            confidentCount = plan.recordedCount,
            queueCount = plan.pendingCount,
            duplicateCount = plan.duplicateCount,
            ignoredCount = plan.ignoredCount,
            balanceNoticeCount = plan.items.count { it.detection == null },
            samples = samples.take(20),
            duplicateItems = plan.duplicateItems.take(10)
        )
    }

    /** Approve a reviewed detection: write the real ledger row and link it. */
    suspend fun approveDetection(
        detectionId: Long,
        approval: DetectionApproval = DetectionApproval()
    ): Result<String> = runCatching {
        val detection = spendingDao.getAllDetectionsSnapshot().firstOrNull { it.id == detectionId }
            ?: error("Detection not found")
        require(detection.reviewStatus == "PENDING") { "Only pending detections can be approved" }
        val now = System.currentTimeMillis()

        // Balance-only notices carry no transaction — approving attaches them
        // to an account so reconciliation can compare provider vs ledger.
        if (detection.action == LedgerAction.IGNORE.name) {
            val accountId = (approval.accountId ?: detection.accountId).ifBlank {
                error("Choose which account this balance belongs to")
            }
            spendingDao.updateDetection(
                detection.copy(reviewStatus = "APPROVED", accountId = accountId, updatedAtMillis = now)
            )
            applyReportedBalance(detection.copy(accountId = accountId))
            logAudit(
                "sms", detection.fingerprint.take(16), "approved",
                "Provider-reported balance attached to an account"
            )
            return@runCatching accountId
        }

        val effective = if (approval.action != null) detection.copy(action = approval.action.name)
        else detection
        val withOverrides = effective.copy(
            amountCents = approval.amountCents ?: detection.amountCents,
            merchant = approval.title?.takeIf { it.isNotBlank() } ?: detection.merchant
        )
        val link = materializeDetection(withOverrides, approval)
            ?: error("This detection has no amount to record — edit it first")
        val accountId = approval.accountId ?: detection.accountId
        val categoryId = approval.categoryId ?: detection.categoryId

        spendingDao.updateDetection(
            detection.copy(
                action = effective.action,
                amountCents = withOverrides.amountCents,
                merchant = withOverrides.merchant,
                reviewStatus = "APPROVED",
                categoryId = categoryId,
                accountId = accountId,
                linkedRecordType = link.first,
                linkedRecordId = link.second,
                updatedAtMillis = now
            )
        )
        if (detection.reportedBalanceCents != null) {
            applyReportedBalance(detection.copy(accountId = accountId))
        }
        approval.saveAsRuleKeyword?.trim()?.takeIf { it.isNotEmpty() }?.let { keyword ->
            saveCategoryRule(keyword, categoryId)
        }
        logAudit(
            "sms", detection.fingerprint.take(16), "approved",
            "Detection approved → ${link.first.lowercase()} ${link.second}" +
                (approval.saveAsRuleKeyword?.takeIf { it.isNotBlank() }
                    ?.let { "; rule saved for '$it'" } ?: "")
        )
        link.second
    }

    /** Reject: the message stays recorded as seen, but never becomes money. */
    suspend fun rejectDetection(detectionId: Long, reason: String = "") {
        val detection = spendingDao.getAllDetectionsSnapshot().firstOrNull { it.id == detectionId }
            ?: error("Detection not found")
        require(detection.reviewStatus == "PENDING") { "Only pending detections can be rejected" }
        spendingDao.updateDetection(
            detection.copy(
                reviewStatus = "REJECTED",
                reviewReason = reason.ifBlank { "rejected by user" },
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        logAudit(
            "sms", detection.fingerprint.take(16), "rejected",
            "Detection rejected; no ledger row created"
        )
    }

    /**
     * Undo a recent import batch: removes every ledger row it created, the
     * detections, and their fingerprints so the same messages can be imported
     * again later. Rows edited by hand after import are removed too — that is
     * what "undo the import" means, and it is reported in the audit trail.
     */
    suspend fun undoSmsBatch(batchId: String): Int {
        val detections = spendingDao.getDetectionsInBatch(batchId)
        for (detection in detections) {
            when (detection.linkedRecordType) {
                "EXPENSE" -> detection.linkedRecordId.toLongOrNull()?.let {
                    spendingDao.deleteExpenseById(it)
                }
                "INCOME" -> detection.linkedRecordId.toLongOrNull()?.let {
                    spendingDao.deleteIncomeById(it)
                }
                "TRANSFER" -> spendingDao.deleteTransferById(detection.linkedRecordId)
            }
        }
        val fingerprints = detections.map { it.fingerprint }
        spendingDao.deleteDetectionsInBatch(batchId)
        if (fingerprints.isNotEmpty()) spendingDao.deleteSmsMetaForFingerprints(fingerprints)
        logAudit(
            "sms", batchId, "undone",
            "Rolled back import batch: ${detections.size} detection(s) removed with their ledger rows; " +
                "confirmed totals recalculated"
        )
        return detections.size
    }

    /**
     * Clears stored SMS metadata and the review queue. Ledger rows created
     * from detections are kept (they are confirmed money), and their
     * fingerprints live on the rows themselves, so a later re-import still
     * cannot duplicate them.
     */
    suspend fun clearSmsImportHistory() {
        spendingDao.clearSmsMeta()
        spendingDao.clearAllDetections()
        logAudit(
            "sms", "history", "cleared",
            "Cleared SMS import history and review queue; recorded transactions untouched"
        )
    }

    /** Write one approved (or auto-approved) detection into the ledger. */
    private suspend fun materializeDetection(
        detection: SmsDetectionEntity,
        approval: DetectionApproval
    ): Pair<String, String>? {
        val action = approval.action ?: runCatching {
            LedgerAction.valueOf(detection.action)
        }.getOrDefault(LedgerAction.REVIEW_ONLY)

        val accountId = approval.accountId ?: detection.accountId
        val categoryId = (approval.categoryId ?: detection.categoryId)
            .ifBlank { CategoryEntity.UNCATEGORIZED_ID }
        val amountCents = (approval.amountCents ?: detection.amountCents ?: 0L).coerceAtLeast(0L)
        val method = paymentMethodFor(detection, accountId)
        val note = listOfNotNull(
            approval.note?.trim()?.takeIf { it.isNotEmpty() },
            detection.refId.takeIf { it.isNotBlank() }?.let { "Ref $it" }
        ).joinToString(" · ")

        return when (action) {
            LedgerAction.RECORD_EXPENSE -> {
                val id = spendingDao.upsertExpense(
                    ExpenseEntity(
                        title = approval.title?.ifBlank { null } ?: detectionTitle(detection),
                        amountCents = amountCents,
                        categoryId = categoryId,
                        paymentMethod = method.id,
                        paymentProvider = detection.provider,
                        timestampEpochMillis = detection.timestampEpochMillis,
                        note = note,
                        accountId = accountId,
                        feeCents = detection.feeCents.coerceAtLeast(0L),
                        source = SOURCE_SMS,
                        smsFingerprint = detection.fingerprint
                    )
                )
                "EXPENSE" to id.toString()
            }

            LedgerAction.RECORD_INCOME -> {
                val sourceId = if (detection.txType == SmsTxType.REFUND.name) {
                    MoneySource.REFUND.id
                } else {
                    MoneySource.TRANSFER_IN.id
                }
                val id = spendingDao.upsertIncome(
                    IncomeEntity(
                        title = approval.title?.ifBlank { null } ?: detectionTitle(detection),
                        amountCents = amountCents,
                        sourceId = sourceId,
                        paymentMethod = method.id,
                        paymentProvider = detection.provider,
                        timestampEpochMillis = detection.timestampEpochMillis,
                        note = note,
                        accountId = accountId,
                        source = SOURCE_SMS,
                        smsFingerprint = detection.fingerprint
                    )
                )
                "INCOME" to id.toString()
            }

            LedgerAction.RECORD_FEE -> {
                // The fee IS the debit: principal stays ৳0.00 so reports can
                // show transaction fees as their own line, never as spending.
                val fee = (detection.amountCents ?: detection.feeCents).coerceAtLeast(0L)
                val id = spendingDao.upsertExpense(
                    ExpenseEntity(
                        title = approval.title?.ifBlank { null } ?: "${detection.provider} Fee",
                        amountCents = 0L,
                        categoryId = categoryId,
                        paymentMethod = method.id,
                        paymentProvider = detection.provider,
                        timestampEpochMillis = detection.timestampEpochMillis,
                        note = note,
                        accountId = accountId,
                        feeCents = fee,
                        source = SOURCE_SMS,
                        smsFingerprint = detection.fingerprint
                    )
                )
                "EXPENSE" to id.toString()
            }

            LedgerAction.RECORD_TRANSFER -> {
                val txType = runCatching { SmsTxType.valueOf(detection.txType) }
                    .getOrDefault(SmsTxType.UNKNOWN)
                val fallback = AccountStateFactory.resolve(accountId, method.id, structuralIdSet())
                val (from, to) = when (txType) {
                    SmsTxType.DEPOSIT -> (approval.transferFromAccountId ?: AccountEntity.CASH_ID) to
                        (approval.transferToAccountId ?: fallback)
                    else -> (approval.transferFromAccountId ?: fallback) to
                        (approval.transferToAccountId ?: AccountEntity.CASH_ID)
                }
                require(from != to) { "Choose two different accounts for this transfer" }
                val id = "tr_${System.currentTimeMillis()}_${auditCounter.incrementAndGet()}"
                spendingDao.upsertTransfer(
                    TransferEntity(
                        id = id,
                        fromAccountId = from,
                        toAccountId = to,
                        amountCents = amountCents.coerceAtLeast(1L),
                        feeCents = detection.feeCents.coerceAtLeast(0L),
                        timestampEpochMillis = detection.timestampEpochMillis,
                        note = note,
                        origin = "SMS",
                        refId = detection.refId,
                        smsFingerprint = detection.fingerprint
                    )
                )
                "TRANSFER" to id
            }

            // Nothing financial to write (balance-only / unclear): review owns it.
            LedgerAction.REVIEW_ONLY, LedgerAction.IGNORE -> null
        }
    }

    /** Provider balance → account, stored beside (never over) the ledger. */
    private suspend fun applyReportedBalance(detection: SmsDetectionEntity) {
        val balance = detection.reportedBalanceCents ?: return
        val accountId = detection.accountId.ifBlank { return }
        if (spendingDao.findAccountById(accountId) == null) return
        setAccountReportedBalance(accountId, balance, detection.timestampEpochMillis)
    }

    private suspend fun structuralIdSet(): Set<String> =
        spendingDao.getAllAccountsSnapshot().map { it.id }.toSet()

    private fun detectionTitle(detection: SmsDetectionEntity): String {
        val what = when (
            runCatching { SmsTxType.valueOf(detection.txType) }.getOrDefault(SmsTxType.UNKNOWN)
        ) {
            SmsTxType.FEE -> "Fee"
            SmsTxType.REFUND -> "Refund"
            SmsTxType.MONEY_RECEIVED -> "Money Received"
            SmsTxType.MONEY_SENT -> "Money Sent"
            SmsTxType.WITHDRAWAL -> "Cash Withdrawal"
            SmsTxType.DEPOSIT -> "Cash Deposit"
            SmsTxType.PURCHASE -> "Purchase"
            SmsTxType.TRANSFER_SELF -> "Transfer"
            else -> "Imported Transaction"
        }
        return if (detection.merchant.isNotBlank()) "$what · ${detection.merchant}"
        else "$what · ${detection.provider}"
    }

    /** Known account → its method; otherwise the provider decides. */
    private suspend fun paymentMethodFor(
        detection: SmsDetectionEntity,
        accountId: String
    ): PaymentMethod {
        val account = accountId.takeIf { it.isNotBlank() }?.let { spendingDao.findAccountById(it) }
        if (account != null) {
            return when (runCatching { AccountKind.valueOf(account.type) }.getOrDefault(AccountKind.CASH)) {
                AccountKind.CASH -> PaymentMethod.CASH
                AccountKind.BANK -> PaymentMethod.BANK_TRANSFER
                AccountKind.WALLET -> PaymentMethod.MOBILE_WALLET
                AccountKind.CARD ->
                    if (detection.provider.contains("credit", ignoreCase = true)) PaymentMethod.CREDIT_CARD
                    else PaymentMethod.DEBIT_CARD
            }
        }
        val provider = detection.provider
        return when {
            provider.equals("bKash", true) || provider.equals("Nagad", true) ||
                provider.equals("Rocket", true) -> PaymentMethod.MOBILE_WALLET
            provider.contains("card", true) -> PaymentMethod.DEBIT_CARD
            provider.contains("bank", true) -> PaymentMethod.BANK_TRANSFER
            else -> PaymentMethod.OTHER
        }
    }

    // -------------------------------------------------------- category rules

    fun observeCategoryRules(): Flow<List<CategoryRuleEntity>> = spendingDao.observeCategoryRules()

    suspend fun saveCategoryRule(keyword: String, categoryId: String): String {
        val trimmed = keyword.trim()
        require(trimmed.isNotEmpty()) { "Rule keyword cannot be empty" }
        val id = "rule_${System.currentTimeMillis()}_${auditCounter.incrementAndGet()}"
        spendingDao.upsertCategoryRule(
            CategoryRuleEntity(
                id = id,
                keyword = trimmed,
                categoryId = categoryId,
                scope = "GLOBAL",
                createdAtMillis = System.currentTimeMillis()
            )
        )
        logAudit("rule", id, "added", "Rule saved: messages containing '$trimmed' → $categoryId")
        return id
    }

    suspend fun deleteCategoryRule(ruleId: String) {
        spendingDao.deleteCategoryRuleById(ruleId)
        logAudit("rule", ruleId, "deleted", "Category rule removed")
    }

    /** User rules → bundled keywords → uncategorized ("other"). */
    private suspend fun suggestCategoryId(text: String): String {
        if (text.isBlank()) return CategoryEntity.UNCATEGORIZED_ID
        val rules = spendingDao.getAllCategoryRulesSnapshot().map {
            CategoryRule(id = it.id, keyword = it.keyword, categoryId = it.categoryId, scope = it.scope)
        }
        val available = spendingDao.getAllCategoriesSnapshot().map { it.id }.toSet()
        return CategorySuggester.suggest(text, rules, available)?.categoryId
            ?: CategorySuggester.fallbackCategory()
    }

    // ------------------------------------------------------- recurring rules

    fun observeRecurringRules(): Flow<List<RecurringRuleEntity>> =
        spendingDao.observeRecurringRules()

    suspend fun saveRecurringRule(
        id: String?,
        title: String,
        amountCents: Long,
        categoryId: String,
        intervalDays: Int,
        nextDueMillis: Long,
        accountId: String = "",
        isActive: Boolean = true
    ): String {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Title cannot be empty" }
        require(amountCents > 0L) { "Amount must be greater than zero" }
        require(intervalDays > 0) { "Interval must be at least one day" }
        val ruleId = id ?: "rec_${System.currentTimeMillis()}_${auditCounter.incrementAndGet()}"
        spendingDao.upsertRecurringRule(
            RecurringRuleEntity(
                id = ruleId,
                title = trimmed,
                amountCents = amountCents,
                categoryId = categoryId,
                accountId = accountId,
                intervalDays = intervalDays,
                nextDueMillis = nextDueMillis,
                isActive = isActive,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        logAudit("recurring", ruleId, "added", "Recurring '$trimmed' every $intervalDays day(s) saved")
        return ruleId
    }

    suspend fun deleteRecurringRule(ruleId: String) {
        spendingDao.deleteRecurringRuleById(ruleId)
        logAudit("recurring", ruleId, "deleted", "Recurring rule removed")
    }

    suspend fun setRecurringRuleActive(ruleId: String, active: Boolean) {
        val rule = spendingDao.getAllRecurringRulesSnapshot().firstOrNull { it.id == ruleId }
            ?: error("Unknown recurring rule $ruleId")
        spendingDao.upsertRecurringRule(rule.copy(isActive = active))
        logAudit(
            "recurring", ruleId, if (active) "activated" else "paused",
            (if (active) "Resumed " else "Paused ") + "'${rule.title}'"
        )
    }

    /**
     * User confirms a recurring payment happened: the real expense is recorded
     * (that is what makes it an *actual*, distinct from the forecast) and the
     * next due date advances by one interval.
     */
    suspend fun markRecurringPaid(ruleId: String, paidAtMillis: Long = System.currentTimeMillis()) {
        val rule = spendingDao.getAllRecurringRulesSnapshot().firstOrNull { it.id == ruleId }
            ?: error("Unknown recurring rule $ruleId")
        val account = rule.accountId.takeIf { it.isNotBlank() }?.let { spendingDao.findAccountById(it) }
        val method = when (account?.let { runCatching { AccountKind.valueOf(it.type) }.getOrNull() }) {
            AccountKind.BANK -> PaymentMethod.BANK_TRANSFER
            AccountKind.WALLET -> PaymentMethod.MOBILE_WALLET
            AccountKind.CARD -> PaymentMethod.CREDIT_CARD
            AccountKind.CASH -> PaymentMethod.CASH
            null -> PaymentMethod.CASH
        }
        spendingDao.upsertExpense(
            ExpenseEntity(
                title = rule.title,
                amountCents = rule.amountCents,
                categoryId = rule.categoryId,
                paymentMethod = method.id,
                timestampEpochMillis = paidAtMillis,
                accountId = rule.accountId,
                isRecurring = true,
                source = SOURCE_MANUAL
            )
        )
        spendingDao.upsertRecurringRule(
            rule.copy(
                lastPaidMillis = paidAtMillis,
                nextDueMillis = rule.nextDueMillis + rule.intervalDays * 86_400_000L
            )
        )
        logAudit(
            "recurring", ruleId, "paid",
            "'${rule.title}' marked paid: real expense of ${rule.amountCents} minor units recorded " +
                "and the next due date advanced (forecasts remain forecasts)"
        )
    }

    // ---------------------------------------------------------- savings goals

    fun observeSavingsGoals(): Flow<List<SavingsGoalEntity>> = spendingDao.observeSavingsGoals()

    fun observeGoalContributions(): Flow<List<GoalContributionEntity>> =
        spendingDao.observeGoalContributions()

    suspend fun saveSavingsGoal(
        id: String?,
        title: String,
        targetCents: Long,
        deadlineMillis: Long?,
        linkedAccountId: String = ""
    ): String {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Goal title cannot be empty" }
        require(targetCents > 0L) { "Goal target must be greater than zero" }
        val goalId = id ?: "goal_${System.currentTimeMillis()}_${auditCounter.incrementAndGet()}"
        spendingDao.upsertSavingsGoal(
            SavingsGoalEntity(
                id = goalId,
                title = trimmed,
                targetCents = targetCents,
                deadlineMillis = deadlineMillis,
                linkedAccountId = linkedAccountId,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        logAudit(
            "goal", goalId, "added",
            "Savings goal '$trimmed' (target ${targetCents} minor units) saved"
        )
        return goalId
    }

    suspend fun deleteSavingsGoal(goalId: String) {
        spendingDao.deleteContributionsForGoal(goalId)
        spendingDao.deleteSavingsGoalById(goalId)
        logAudit("goal", goalId, "deleted", "Savings goal and its contributions removed")
    }

    suspend fun addGoalContribution(
        goalId: String,
        amountCents: Long,
        accountId: String = "",
        note: String = "",
        timestampEpochMillis: Long = System.currentTimeMillis()
    ): Long {
        require(amountCents > 0L) { "Contribution must be greater than zero" }
        require(spendingDao.getAllSavingsGoalsSnapshot().any { it.id == goalId }) {
            "Unknown savings goal"
        }
        val id = spendingDao.insertGoalContribution(
            GoalContributionEntity(
                goalId = goalId,
                amountCents = amountCents,
                accountId = accountId,
                timestampEpochMillis = timestampEpochMillis,
                note = note.trim()
            )
        )
        logAudit(
            "goal", goalId, "contributed",
            "Contributed ${amountCents} minor units toward the goal (progress label only — " +
                "the money stays in its account, balances are unchanged)"
        )
        return id
    }

    suspend fun deleteGoalContribution(contributionId: Long) {
        spendingDao.deleteGoalContributionById(contributionId)
        logAudit("goal", contributionId.toString(), "corrected", "Goal contribution removed")
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

        /** Row source labels: machine-imported vs typed by hand. */
        const val SOURCE_SMS = "SMS"
        const val SOURCE_MANUAL = "MANUAL"

        private const val PREFS_NAME = "kanso_local_prefs"
        private const val KEY_CURRENCY_SYMBOL = "currency_symbol"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SMS_ENABLED = "sms_enabled"
        private const val KEY_SMS_MODE = "sms_import_mode"
        private const val KEY_SMART_SETUP_DONE = "smart_setup_done"
        private const val KEY_NOTIF_DETECTED = "notif_detected"
        private const val KEY_NOTIF_BUDGET = "notif_budget"
        private const val KEY_NOTIF_RECONCILE = "notif_reconcile"
        private const val KEY_NOTIF_RECURRING = "notif_recurring"
        private const val KEY_APP_LOCK = "app_lock_enabled"
    }
}
