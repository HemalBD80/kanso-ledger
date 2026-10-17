package com.kanso.spending.ui

import android.content.Context
import com.kanso.spending.data.local.entity.AccountKind
import com.kanso.spending.data.repository.DetectionApproval
import com.kanso.spending.data.repository.SmsImportPreview
import com.kanso.spending.domain.recurring.RecurringCandidate
import com.kanso.spending.domain.sms.SmsImportMode

/**
 * Callback bundle for every v2.0 intelligence feature, passed to the dashboard
 * as a single parameter so its signature stays readable. All implementations
 * delegate straight to [SpendingViewModel]; none of them touch the network —
 * there is none.
 */
data class SmartLedgerActions(
    // ---- accounts & transfers
    val onCreateAccount: (name: String, kind: AccountKind, openingBalanceCents: Long, note: String) -> Unit,
    val onRenameAccount: (accountId: String, newName: String) -> Unit,
    val onSetAccountNote: (accountId: String, note: String) -> Unit,
    val onSetAccountOpening: (accountId: String, openingBalanceCents: Long) -> Unit,
    val onSetAccountArchived: (accountId: String, archived: Boolean) -> Unit,
    val onDeleteAccount: (accountId: String) -> Unit,
    val onRecordAdjustment: (accountId: String, deltaCents: Long, note: String) -> Unit,
    val onCreateTransfer: (fromId: String, toId: String, amountCents: Long, feeCents: Long, note: String) -> Unit,
    val onDeleteTransfer: (transferId: String) -> Unit,

    // ---- smart SMS
    val onSetSmsEnabled: (Boolean) -> Unit,
    val onSetImportMode: (SmsImportMode) -> Unit,
    val onImportHistory: (context: Context, fromMillis: Long, toMillis: Long) -> Unit,
    /**
     * Dry-run a historical import first: writes nothing, reports what would be
     * recorded / queued / skipped as duplicates. Null result = read failure
     * (permission denied or no messages) — the reason lands in smart status.
     */
    val onPreviewHistory: (context: Context, fromMillis: Long, toMillis: Long, onResult: (SmsImportPreview?) -> Unit) -> Unit,
    /** Import the range with an explicit one-shot mode (auto-record vs review-first). */
    val onImportHistoryAs: (context: Context, fromMillis: Long, toMillis: Long, mode: SmsImportMode) -> Unit,
    val onPasteImport: (text: String) -> Unit,
    val onUndoLastImport: () -> Unit,
    val onClearSmsHistory: () -> Unit,
    val onApproveDetection: (detectionId: Long, approval: DetectionApproval) -> Unit,
    val onRejectDetection: (detectionId: Long) -> Unit,

    // ---- notifications & app lock
    val onSetNotifyDetected: (Boolean) -> Unit,
    val onSetNotifyBudget: (Boolean) -> Unit,
    val onSetNotifyReconcile: (Boolean) -> Unit,
    val onSetNotifyRecurring: (Boolean) -> Unit,
    val onSetAppLock: (Boolean) -> Unit,

    // ---- recurring payments
    val onSaveRecurring: (
        id: String?, title: String, amountCents: Long, categoryId: String,
        intervalDays: Int, nextDueMillis: Long, accountId: String
    ) -> Unit,
    val onDeleteRecurring: (ruleId: String) -> Unit,
    val onSetRecurringActive: (ruleId: String, active: Boolean) -> Unit,
    val onMarkRecurringPaid: (ruleId: String) -> Unit,
    val onConfirmCandidate: (candidate: RecurringCandidate, categoryId: String?) -> Unit,

    // ---- savings goals
    val onSaveGoal: (id: String?, title: String, targetCents: Long, deadlineMillis: Long?, linkedAccountId: String) -> Unit,
    val onDeleteGoal: (goalId: String) -> Unit,
    val onAddContribution: (goalId: String, amountCents: Long, accountId: String) -> Unit,
    val onDeleteContribution: (contributionId: Long) -> Unit,

    // ---- categorisation rules
    val onSaveCategoryRule: (keyword: String, categoryId: String) -> Unit,
    val onDeleteCategoryRule: (ruleId: String) -> Unit,

    // ---- optional AI diagnostics & narrative
    /** Real connectivity probe against the configured provider (no SMS sent). */
    val onTestAiConnection: (onResult: (String) -> Unit) -> Unit,
    /**
     * Stronger diagnostic: a FICTIONAL labelled bank SMS through the full
     * classify pipeline, to catch "connects but cannot classify" (bad model ID,
     * non-JSON reply). No real SMS or ledger data is sent.
     */
    val onTestAiClassification: (onResult: (String) -> Unit) -> Unit,
    /**
     * Optional AI narrative over the month's locally-computed figures. The
     * numbers stay authoritative; the AI only describes them. Null on failure.
     */
    val onGenerateAiNarrative: (
        incomeCents: Long,
        expenseCents: Long,
        topCategories: List<Pair<String, Long>>,
        onResult: (String?) -> Unit
    ) -> Unit,

    val onClearSmartStatus: () -> Unit
)
