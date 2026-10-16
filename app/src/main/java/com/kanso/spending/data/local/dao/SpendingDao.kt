package com.kanso.spending.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kanso.spending.data.local.entity.AccountAdjustmentEntity
import com.kanso.spending.data.local.entity.AccountEntity
import com.kanso.spending.data.local.entity.ActivityRow
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

    // --------------------------------------------------------------- accounts

    @Query("SELECT * FROM accounts ORDER BY sort_order ASC, name ASC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sort_order ASC, name ASC")
    suspend fun getAllAccountsSnapshot(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :accountId LIMIT 1")
    suspend fun findAccountById(accountId: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE is_archived = 0 ORDER BY sort_order ASC, name ASC")
    suspend fun getActiveAccounts(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccountIfMissing(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :accountId")
    suspend fun deleteAccountById(accountId: String)

    /** Safety counts so an account with history can never be silently deleted. */
    @Query("SELECT COUNT(*) FROM expenses WHERE account_id = :accountId")
    suspend fun countExpensesForAccount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM income_entries WHERE account_id = :accountId")
    suspend fun countIncomeForAccount(accountId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM transfers
        WHERE from_account_id = :accountId OR to_account_id = :accountId
        """
    )
    suspend fun countTransfersForAccount(accountId: String): Int

    @Query(
        """
        UPDATE accounts SET last_reported_balance_cents = :balanceCents,
            last_reported_at_millis = :reportedAtMillis
        WHERE id = :accountId
        """
    )
    suspend fun updateAccountReportedBalance(
        accountId: String,
        balanceCents: Long,
        reportedAtMillis: Long
    )

    // ------------------------------------------------------------ activities
    // Unified projections feeding the account balance engine. Room can only
    // return typed rows, so income and expense share one mapping shape.

    @Query(
        """
        SELECT id AS id, amount_cents AS amountCents, fee_cents AS feeCents,
               0 AS directionIn, account_id AS accountId,
               payment_method AS paymentMethod,
               timestamp_epoch_millis AS timestampEpochMillis,
               source AS source
        FROM expenses
        """
    )
    suspend fun getExpenseActivityRows(): List<ActivityRow>

    @Query(
        """
        SELECT id AS id, amount_cents AS amountCents, 0 AS feeCents,
               1 AS directionIn, account_id AS accountId,
               payment_method AS paymentMethod,
               timestamp_epoch_millis AS timestampEpochMillis,
               source AS source
        FROM income_entries
        """
    )
    suspend fun getIncomeActivityRows(): List<ActivityRow>

    @Query(
        """
        SELECT id AS id, amount_cents AS amountCents, fee_cents AS feeCents,
               0 AS directionIn, account_id AS accountId,
               payment_method AS paymentMethod,
               timestamp_epoch_millis AS timestampEpochMillis,
               source AS source
        FROM expenses
        WHERE timestamp_epoch_millis >= :startEpochMillis
          AND timestamp_epoch_millis < :endEpochMillis
        """
    )
    suspend fun getExpenseActivityForRange(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): List<ActivityRow>

    @Query(
        """
        SELECT id AS id, amount_cents AS amountCents, 0 AS feeCents,
               1 AS directionIn, account_id AS accountId,
               payment_method AS paymentMethod,
               timestamp_epoch_millis AS timestampEpochMillis,
               source AS source
        FROM income_entries
        WHERE timestamp_epoch_millis >= :startEpochMillis
          AND timestamp_epoch_millis < :endEpochMillis
        """
    )
    suspend fun getIncomeActivityForRange(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): List<ActivityRow>

    // ------------------------------------------------------------- transfers

    @Query("SELECT * FROM transfers ORDER BY timestamp_epoch_millis DESC, id DESC")
    fun observeTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers ORDER BY timestamp_epoch_millis DESC, id DESC")
    suspend fun getAllTransfersSnapshot(): List<TransferEntity>

    @Query(
        """
        SELECT * FROM transfers
        WHERE timestamp_epoch_millis >= :startEpochMillis
          AND timestamp_epoch_millis < :endEpochMillis
        ORDER BY timestamp_epoch_millis ASC
        """
    )
    suspend fun getTransfersForRange(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): List<TransferEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertTransfer(transfer: TransferEntity): Long

    @Query("DELETE FROM transfers WHERE id = :transferId")
    suspend fun deleteTransferById(transferId: String)

    // ----------------------------------------------------------- adjustments

    @Query("SELECT * FROM account_adjustments ORDER BY timestamp_epoch_millis ASC")
    fun observeAdjustments(): Flow<List<AccountAdjustmentEntity>>

    @Query("SELECT * FROM account_adjustments ORDER BY timestamp_epoch_millis ASC")
    suspend fun getAllAdjustmentsSnapshot(): List<AccountAdjustmentEntity>

    @Insert
    suspend fun insertAdjustment(adjustment: AccountAdjustmentEntity): Long

    @Query("DELETE FROM account_adjustments WHERE id = :adjustmentId")
    suspend fun deleteAdjustmentById(adjustmentId: Long)

    // ------------------------------------------------------- SMS meta / dedup

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSmsMetaIfNew(meta: SmsMetaEntity): Long

    @Query("SELECT * FROM sms_meta WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findSmsMetaByFingerprint(fingerprint: String): SmsMetaEntity?

    @Query("SELECT fingerprint FROM sms_meta")
    suspend fun getAllKnownFingerprints(): List<String>

    @Query("SELECT * FROM sms_meta ORDER BY received_at_millis DESC")
    suspend fun getAllSmsMetasSnapshot(): List<SmsMetaEntity>

    /** Fingerprints already committed to the ledger (kept after history clears). */
    @Query(
        """
        SELECT sms_fingerprint FROM expenses WHERE sms_fingerprint != ''
        UNION
        SELECT sms_fingerprint FROM income_entries WHERE sms_fingerprint != ''
        UNION
        SELECT sms_fingerprint FROM transfers WHERE sms_fingerprint != ''
        """
    )
    suspend fun getAllLedgerFingerprints(): List<String>

    @Query("DELETE FROM sms_meta WHERE fingerprint IN (:fingerprints)")
    suspend fun deleteSmsMetaForFingerprints(fingerprints: List<String>)

    @Query("SELECT COUNT(*) FROM sms_meta")
    suspend fun countSmsMeta(): Int

    @Query("DELETE FROM sms_meta")
    suspend fun clearSmsMeta()

    // ----------------------------------------------------------- detections

    @Query("SELECT * FROM sms_detections ORDER BY timestamp_epoch_millis DESC, id DESC")
    fun observeDetections(): Flow<List<SmsDetectionEntity>>

    @Query(
        """
        SELECT * FROM sms_detections
        WHERE review_status = 'PENDING'
        ORDER BY timestamp_epoch_millis ASC
        """
    )
    fun observePendingDetections(): Flow<List<SmsDetectionEntity>>

    @Query(
        """
        SELECT * FROM sms_detections
        WHERE review_status = 'PENDING'
        ORDER BY timestamp_epoch_millis ASC
        """
    )
    suspend fun getPendingDetections(): List<SmsDetectionEntity>

    @Query("SELECT * FROM sms_detections ORDER BY timestamp_epoch_millis DESC, id DESC")
    suspend fun getAllDetectionsSnapshot(): List<SmsDetectionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDetectionIfNew(detection: SmsDetectionEntity): Long

    @Update
    suspend fun updateDetection(detection: SmsDetectionEntity)

    @Query("SELECT * FROM sms_detections WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findDetectionByFingerprint(fingerprint: String): SmsDetectionEntity?

    @Query(
        """
        SELECT COUNT(*) FROM sms_detections
        WHERE review_status = 'PENDING' AND batch_id = :batchId
        """
    )
    suspend fun countPendingInBatch(batchId: String): Int

    @Query("DELETE FROM sms_detections WHERE batch_id = :batchId")
    suspend fun deleteDetectionsInBatch(batchId: String)

    @Query(
        """
        SELECT * FROM sms_detections
        WHERE batch_id = :batchId ORDER BY timestamp_epoch_millis ASC
        """
    )
    suspend fun getDetectionsInBatch(batchId: String): List<SmsDetectionEntity>

    // ------------------------------------------------------- recurring rules

    @Query("SELECT * FROM recurring_rules WHERE is_active = 1 ORDER BY next_due_millis ASC")
    fun observeActiveRecurringRules(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY next_due_millis ASC")
    fun observeRecurringRules(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY next_due_millis ASC")
    suspend fun getAllRecurringRulesSnapshot(): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecurringRule(rule: RecurringRuleEntity)

    @Query("DELETE FROM recurring_rules WHERE id = :ruleId")
    suspend fun deleteRecurringRuleById(ruleId: String)

    // ----------------------------------------------------------- savings goals

    @Query("SELECT * FROM savings_goals ORDER BY created_at_millis ASC")
    fun observeSavingsGoals(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goals ORDER BY created_at_millis ASC")
    suspend fun getAllSavingsGoalsSnapshot(): List<SavingsGoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavingsGoal(goal: SavingsGoalEntity)

    @Query("DELETE FROM savings_goals WHERE id = :goalId")
    suspend fun deleteSavingsGoalById(goalId: String)

    @Query("SELECT * FROM goal_contributions ORDER BY timestamp_epoch_millis DESC")
    fun observeGoalContributions(): Flow<List<GoalContributionEntity>>

    @Query("SELECT * FROM goal_contributions ORDER BY timestamp_epoch_millis DESC")
    suspend fun getAllGoalContributionsSnapshot(): List<GoalContributionEntity>

    @Insert
    suspend fun insertGoalContribution(contribution: GoalContributionEntity): Long

    @Query("DELETE FROM goal_contributions WHERE id = :contributionId")
    suspend fun deleteGoalContributionById(contributionId: Long)

    @Query("DELETE FROM goal_contributions WHERE goal_id = :goalId")
    suspend fun deleteContributionsForGoal(goalId: String)

    // ---------------------------------------------------------- category rules

    @Query("SELECT * FROM category_rules ORDER BY created_at_millis DESC")
    fun observeCategoryRules(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules ORDER BY created_at_millis DESC")
    suspend fun getAllCategoryRulesSnapshot(): List<CategoryRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryRule(rule: CategoryRuleEntity)

    @Query("DELETE FROM category_rules WHERE id = :ruleId")
    suspend fun deleteCategoryRuleById(ruleId: String)

    // ---------------------------------------------------------- data lifecycle

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

    @Query("DELETE FROM transfers")
    suspend fun clearAllTransfers()

    @Query("DELETE FROM account_adjustments")
    suspend fun clearAllAdjustments()

    @Query("DELETE FROM sms_detections")
    suspend fun clearAllDetections()

    @Query("DELETE FROM recurring_rules")
    suspend fun clearAllRecurringRules()

    @Query("DELETE FROM savings_goals")
    suspend fun clearAllSavingsGoals()

    @Query("DELETE FROM goal_contributions")
    suspend fun clearAllGoalContributions()

    @Query("DELETE FROM category_rules")
    suspend fun clearAllCategoryRules()

    @Transaction
    suspend fun wipeAllLocalData() {
        clearAllExpenses()
        clearAllMonthlyBudgets()
        clearAllCategoryBudgets()
        clearAllIncome()
        clearAllAuditLog()
        clearAllTransfers()
        clearAllAdjustments()
        clearAllDetections()
        clearAllRecurringRules()
        clearAllSavingsGoals()
        clearAllGoalContributions()
        clearAllCategoryRules()
        // Dedup metadata belongs to the wiped records too: after a wipe the
        // same messages must be importable again, not silently skipped.
        clearSmsMeta()
        // Categories are wiped with everything else; the repository immediately
        // re-seeds the default set so the picker stays usable after a wipe.
        clearAllCategories()
        // Accounts survive a wipe: they are ledger structure, not private
        // records, and the repository re-seeds the defaults if missing.
    }

    @Transaction
    suspend fun replaceAllLocalData(
        expenses: List<ExpenseEntity>,
        monthlyBudgets: List<MonthlyBudgetEntity>,
        categoryBudgets: List<CategoryBudgetEntity>,
        incomes: List<IncomeEntity>,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity> = emptyList(),
        transfers: List<TransferEntity> = emptyList(),
        adjustments: List<AccountAdjustmentEntity> = emptyList(),
        recurringRules: List<RecurringRuleEntity> = emptyList(),
        savingsGoals: List<SavingsGoalEntity> = emptyList(),
        goalContributions: List<GoalContributionEntity> = emptyList(),
        categoryRules: List<CategoryRuleEntity> = emptyList()
    ) {
        wipeAllLocalData()
        // Preserve the incoming record ids so a restored ledger round-trips exactly.
        expenses.forEach { upsertExpense(it) }
        insertMonthlyBudgets(monthlyBudgets)
        insertCategoryBudgets(categoryBudgets)
        incomes.forEach { upsertIncome(it) }
        insertCategories(categories)
        accounts.forEach { upsertAccount(it) }
        transfers.forEach { upsertTransfer(it) }
        adjustments.forEach { insertAdjustment(it) }
        recurringRules.forEach { upsertRecurringRule(it) }
        savingsGoals.forEach { upsertSavingsGoal(it) }
        goalContributions.forEach { insertGoalContribution(it) }
        categoryRules.forEach { upsertCategoryRule(it) }
    }
}
