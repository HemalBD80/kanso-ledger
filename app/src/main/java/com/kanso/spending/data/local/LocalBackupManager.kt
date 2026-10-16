package com.kanso.spending.data.local

import com.kanso.spending.data.local.entity.AccountAdjustmentEntity
import com.kanso.spending.data.local.entity.AccountEntity
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure local JSON snapshot serializer & parser so users can back up or restore
 * their entire on-device SQLite ledger without any cloud account or server.
 *
 * Schema history:
 *  - v1: expenses, budgets, currency.
 *  - v2: adds incomes and categories.
 *  - v3: adds accounts, transfers, adjustments, recurring rules, savings goals
 *        and contributions, category rules, plus SMS dedup metadata and
 *        detections (pending review queue).
 *
 * Privacy: raw SMS message bodies are NEVER part of any snapshot — only
 * fingerprints and the structured fields the app extracted. Older snapshots
 * (v1/v2) still restore; missing structures fall back to defaults.
 */
object LocalBackupManager {

    private const val SCHEMA_VERSION = 3

    data class LedgerSnapshot(
        val expenses: List<ExpenseEntity>,
        val monthlyBudgets: List<MonthlyBudgetEntity>,
        val categoryBudgets: List<CategoryBudgetEntity>,
        val incomes: List<IncomeEntity>,
        val categories: List<CategoryEntity>,
        val currencySymbol: String,
        val accounts: List<AccountEntity> = emptyList(),
        val transfers: List<TransferEntity> = emptyList(),
        val adjustments: List<AccountAdjustmentEntity> = emptyList(),
        val recurringRules: List<RecurringRuleEntity> = emptyList(),
        val savingsGoals: List<SavingsGoalEntity> = emptyList(),
        val goalContributions: List<GoalContributionEntity> = emptyList(),
        val categoryRules: List<CategoryRuleEntity> = emptyList(),
        val smsMetas: List<SmsMetaEntity> = emptyList(),
        val smsDetections: List<SmsDetectionEntity> = emptyList()
    )

    fun exportToJsonString(snapshot: LedgerSnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAtMillis", System.currentTimeMillis())
        root.put("currencySymbol", snapshot.currencySymbol)

        val expensesArray = JSONArray()
        snapshot.expenses.forEach { expense ->
            val item = JSONObject().apply {
                put("id", expense.id)
                put("title", expense.title)
                put("amountCents", expense.amountCents)
                put("categoryId", expense.categoryId)
                put("paymentMethod", expense.paymentMethod)
                put("paymentProvider", expense.paymentProvider)
                put("timestampEpochMillis", expense.timestampEpochMillis)
                put("note", expense.note)
                put("isRecurring", expense.isRecurring)
                put("accountId", expense.accountId)
                put("feeCents", expense.feeCents)
                put("source", expense.source)
                put("smsFingerprint", expense.smsFingerprint)
            }
            expensesArray.put(item)
        }
        root.put("expenses", expensesArray)

        val monthlyBudgetsArray = JSONArray()
        snapshot.monthlyBudgets.forEach { budget ->
            val item = JSONObject().apply {
                put("monthKey", budget.monthKey)
                put("overallLimitCents", budget.overallLimitCents)
                put("updatedAtMillis", budget.updatedAtMillis)
            }
            monthlyBudgetsArray.put(item)
        }
        root.put("monthlyBudgets", monthlyBudgetsArray)

        val categoryBudgetsArray = JSONArray()
        snapshot.categoryBudgets.forEach { catBudget ->
            val item = JSONObject().apply {
                put("monthKey", catBudget.monthKey)
                put("categoryId", catBudget.categoryId)
                put("limitCents", catBudget.limitCents)
            }
            categoryBudgetsArray.put(item)
        }
        root.put("categoryBudgets", categoryBudgetsArray)

        val incomesArray = JSONArray()
        snapshot.incomes.forEach { income ->
            val item = JSONObject().apply {
                put("id", income.id)
                put("title", income.title)
                put("amountCents", income.amountCents)
                put("sourceId", income.sourceId)
                put("paymentMethod", income.paymentMethod)
                put("paymentProvider", income.paymentProvider)
                put("timestampEpochMillis", income.timestampEpochMillis)
                put("note", income.note)
                put("accountId", income.accountId)
                put("source", income.source)
                put("smsFingerprint", income.smsFingerprint)
            }
            incomesArray.put(item)
        }
        root.put("incomes", incomesArray)

        val categoriesArray = JSONArray()
        snapshot.categories.forEach { category ->
            val item = JSONObject().apply {
                put("id", category.id)
                put("displayName", category.displayName)
                put("iconKey", category.iconKey)
                put("colorHex", category.colorHex)
                put("sortOrder", category.sortOrder)
                put("isCustom", category.isCustom)
                put("isHidden", category.isHidden)
            }
            categoriesArray.put(item)
        }
        root.put("categories", categoriesArray)

        val accountsArray = JSONArray()
        snapshot.accounts.forEach { account ->
            val item = JSONObject().apply {
                put("id", account.id)
                put("name", account.name)
                put("type", account.type)
                put("openingBalanceCents", account.openingBalanceCents)
                put("isArchived", account.isArchived)
                put("sortOrder", account.sortOrder)
                put("note", account.note)
                put("createdAtMillis", account.createdAtMillis)
                if (account.lastReportedBalanceCents != null) {
                    put("lastReportedBalanceCents", account.lastReportedBalanceCents)
                    put("lastReportedAtMillis", account.lastReportedAtMillis ?: 0L)
                }
            }
            accountsArray.put(item)
        }
        root.put("accounts", accountsArray)

        val transfersArray = JSONArray()
        snapshot.transfers.forEach { transfer ->
            val item = JSONObject().apply {
                put("id", transfer.id)
                put("fromAccountId", transfer.fromAccountId)
                put("toAccountId", transfer.toAccountId)
                put("amountCents", transfer.amountCents)
                put("feeCents", transfer.feeCents)
                put("timestampEpochMillis", transfer.timestampEpochMillis)
                put("note", transfer.note)
                put("origin", transfer.origin)
                put("refId", transfer.refId)
                put("status", transfer.status)
                put("smsFingerprint", transfer.smsFingerprint)
            }
            transfersArray.put(item)
        }
        root.put("transfers", transfersArray)

        val adjustmentsArray = JSONArray()
        snapshot.adjustments.forEach { adjustment ->
            val item = JSONObject().apply {
                put("id", adjustment.id)
                put("accountId", adjustment.accountId)
                put("deltaCents", adjustment.deltaCents)
                put("kind", adjustment.kind)
                put("timestampEpochMillis", adjustment.timestampEpochMillis)
                put("note", adjustment.note)
            }
            adjustmentsArray.put(item)
        }
        root.put("accountAdjustments", adjustmentsArray)

        val recurringArray = JSONArray()
        snapshot.recurringRules.forEach { rule ->
            val item = JSONObject().apply {
                put("id", rule.id)
                put("title", rule.title)
                put("amountCents", rule.amountCents)
                put("categoryId", rule.categoryId)
                put("accountId", rule.accountId)
                put("intervalDays", rule.intervalDays)
                put("nextDueMillis", rule.nextDueMillis)
                put("isActive", rule.isActive)
                put("source", rule.source)
                if (rule.lastPaidMillis != null) put("lastPaidMillis", rule.lastPaidMillis)
                put("createdAtMillis", rule.createdAtMillis)
            }
            recurringArray.put(item)
        }
        root.put("recurringRules", recurringArray)

        val goalsArray = JSONArray()
        snapshot.savingsGoals.forEach { goal ->
            val item = JSONObject().apply {
                put("id", goal.id)
                put("title", goal.title)
                put("targetCents", goal.targetCents)
                if (goal.deadlineMillis != null) put("deadlineMillis", goal.deadlineMillis)
                put("linkedAccountId", goal.linkedAccountId)
                put("createdAtMillis", goal.createdAtMillis)
            }
            goalsArray.put(item)
        }
        root.put("savingsGoals", goalsArray)

        val contributionsArray = JSONArray()
        snapshot.goalContributions.forEach { contribution ->
            val item = JSONObject().apply {
                put("id", contribution.id)
                put("goalId", contribution.goalId)
                put("amountCents", contribution.amountCents)
                put("accountId", contribution.accountId)
                put("timestampEpochMillis", contribution.timestampEpochMillis)
                put("note", contribution.note)
            }
            contributionsArray.put(item)
        }
        root.put("goalContributions", contributionsArray)

        val categoryRulesArray = JSONArray()
        snapshot.categoryRules.forEach { rule ->
            val item = JSONObject().apply {
                put("id", rule.id)
                put("keyword", rule.keyword)
                put("categoryId", rule.categoryId)
                put("scope", rule.scope)
                put("createdAtMillis", rule.createdAtMillis)
            }
            categoryRulesArray.put(item)
        }
        root.put("categoryRules", categoryRulesArray)

        // Fingerprints + structured fields only — never message bodies.
        val smsMetasArray = JSONArray()
        snapshot.smsMetas.forEach { meta ->
            val item = JSONObject().apply {
                put("fingerprint", meta.fingerprint)
                put("sender", meta.sender)
                put("provider", meta.provider)
                put("receivedAtMillis", meta.receivedAtMillis)
                put("processedAtMillis", meta.processedAtMillis)
                put("outcome", meta.outcome)
                put("txType", meta.txType)
            }
            smsMetasArray.put(item)
        }
        root.put("smsMetas", smsMetasArray)

        val detectionsArray = JSONArray()
        snapshot.smsDetections.forEach { detection ->
            val item = JSONObject().apply {
                put("fingerprint", detection.fingerprint)
                put("sender", detection.sender)
                put("provider", detection.provider)
                put("txType", detection.txType)
                put("action", detection.action)
                if (detection.amountCents != null) put("amountCents", detection.amountCents)
                put("feeCents", detection.feeCents)
                put("timestampEpochMillis", detection.timestampEpochMillis)
                put("merchant", detection.merchant)
                put("refId", detection.refId)
                put("accountHint", detection.accountHint)
                if (detection.reportedBalanceCents != null) {
                    put("reportedBalanceCents", detection.reportedBalanceCents)
                }
                put("confidence", detection.confidence)
                put("reviewReason", detection.reviewReason)
                put("reviewStatus", detection.reviewStatus)
                put("categoryId", detection.categoryId)
                put("accountId", detection.accountId)
                put("linkedRecordType", detection.linkedRecordType)
                put("linkedRecordId", detection.linkedRecordId)
                put("batchId", detection.batchId)
                put("createdAtMillis", detection.createdAtMillis)
                put("updatedAtMillis", detection.updatedAtMillis)
            }
            detectionsArray.put(item)
        }
        root.put("smsDetections", detectionsArray)

        return root.toString(2)
    }

    fun importFromJsonString(json: String): Result<LedgerSnapshot> = runCatching {
        parseJson(json.trim())
    }

    private fun parseJson(json: String): LedgerSnapshot {
        val root = JSONObject(json)
        val schemaVersion = root.optInt("schemaVersion", 1)
        require(schemaVersion in 1..SCHEMA_VERSION) { "Unsupported backup schema version $schemaVersion" }

        val expenses = mutableListOf<ExpenseEntity>()
        val expensesArray = root.optJSONArray("expenses") ?: JSONArray()
        for (i in 0 until expensesArray.length()) {
            val o = expensesArray.getJSONObject(i)
            val title = o.optString("title").ifBlank { "Expense" }
            val categoryId = o.optString("categoryId").ifBlank { CategoryEntity.UNCATEGORIZED_ID }
            val paymentMethod = o.optString("paymentMethod").ifBlank { "cash" }
            expenses += ExpenseEntity(
                id = o.optLong("id", 0L),
                title = title,
                amountCents = o.optLong("amountCents", 0L),
                categoryId = categoryId,
                paymentMethod = paymentMethod,
                paymentProvider = o.optString("paymentProvider", ""),
                timestampEpochMillis = o.optLong("timestampEpochMillis", 0L),
                note = o.optString("note", ""),
                isRecurring = o.optBoolean("isRecurring", false),
                accountId = o.optString("accountId", ""),
                feeCents = o.optLong("feeCents", 0L),
                source = o.optString("source", "MANUAL"),
                smsFingerprint = o.optString("smsFingerprint", "")
            )
        }

        val monthlyBudgets = mutableListOf<MonthlyBudgetEntity>()
        val budgetsArray = root.optJSONArray("monthlyBudgets") ?: JSONArray()
        for (i in 0 until budgetsArray.length()) {
            val o = budgetsArray.getJSONObject(i)
            val monthKey = o.optString("monthKey")
            require(monthKey.isNotBlank()) { "monthlyBudgets row missing monthKey" }
            monthlyBudgets += MonthlyBudgetEntity(
                monthKey = monthKey,
                overallLimitCents = o.optLong("overallLimitCents", 0L),
                updatedAtMillis = o.optLong("updatedAtMillis", 0L)
            )
        }

        val categoryBudgets = mutableListOf<CategoryBudgetEntity>()
        val catBudgetsArray = root.optJSONArray("categoryBudgets") ?: JSONArray()
        for (i in 0 until catBudgetsArray.length()) {
            val o = catBudgetsArray.getJSONObject(i)
            val monthKey = o.optString("monthKey")
            require(monthKey.isNotBlank()) { "categoryBudgets row missing monthKey" }
            val categoryId = o.optString("categoryId").ifBlank { CategoryEntity.UNCATEGORIZED_ID }
            categoryBudgets += CategoryBudgetEntity(
                monthKey = monthKey,
                categoryId = categoryId,
                limitCents = o.optLong("limitCents", 0L)
            )
        }

        val incomes = mutableListOf<IncomeEntity>()
        val incomesArray = root.optJSONArray("incomes") ?: JSONArray()
        for (i in 0 until incomesArray.length()) {
            val o = incomesArray.getJSONObject(i)
            incomes += IncomeEntity(
                id = o.optLong("id", 0L),
                title = o.optString("title").ifBlank { "Money In" },
                amountCents = o.optLong("amountCents", 0L),
                sourceId = o.optString("sourceId").ifBlank { "other" },
                paymentMethod = o.optString("paymentMethod").ifBlank { "cash" },
                paymentProvider = o.optString("paymentProvider", ""),
                timestampEpochMillis = o.optLong("timestampEpochMillis", 0L),
                note = o.optString("note", ""),
                accountId = o.optString("accountId", ""),
                source = o.optString("source", "MANUAL"),
                smsFingerprint = o.optString("smsFingerprint", "")
            )
        }

        val categories = mutableListOf<CategoryEntity>()
        val categoriesArray = root.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until categoriesArray.length()) {
            val o = categoriesArray.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue
            categories += CategoryEntity(
                id = id,
                displayName = o.optString("displayName").ifBlank { id },
                iconKey = o.optString("iconKey", CategoryEntity.ICON_OTHER).ifBlank { CategoryEntity.ICON_OTHER },
                colorHex = o.optLong("colorHex", 0xFF161614),
                sortOrder = o.optInt("sortOrder", 100),
                isCustom = o.optBoolean("isCustom", id.startsWith("custom_")),
                isHidden = o.optBoolean("isHidden", false)
            )
        }

        val accounts = mutableListOf<AccountEntity>()
        val accountsArray = root.optJSONArray("accounts") ?: JSONArray()
        for (i in 0 until accountsArray.length()) {
            val o = accountsArray.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue
            accounts += AccountEntity(
                id = id,
                name = o.optString("name").ifBlank { id },
                type = o.optString("type").ifBlank { "CASH" },
                openingBalanceCents = o.optLong("openingBalanceCents", 0L),
                isArchived = o.optBoolean("isArchived", false),
                sortOrder = o.optInt("sortOrder", 100),
                lastReportedBalanceCents =
                    if (o.has("lastReportedBalanceCents")) o.optLong("lastReportedBalanceCents") else null,
                lastReportedAtMillis =
                    if (o.has("lastReportedAtMillis")) o.optLong("lastReportedAtMillis") else null,
                note = o.optString("note", ""),
                createdAtMillis = o.optLong("createdAtMillis", 0L)
            )
        }

        val transfers = mutableListOf<TransferEntity>()
        val transfersArray = root.optJSONArray("transfers") ?: JSONArray()
        for (i in 0 until transfersArray.length()) {
            val o = transfersArray.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue
            val amountCents = o.optLong("amountCents", 0L)
            require(amountCents > 0L) { "Backup contains a transfer without an amount" }
            val from = o.optString("fromAccountId")
            val to = o.optString("toAccountId")
            require(from.isNotBlank() && to.isNotBlank() && from != to) {
                "Backup contains a transfer with invalid endpoints"
            }
            transfers += TransferEntity(
                id = id,
                fromAccountId = from,
                toAccountId = to,
                amountCents = amountCents,
                feeCents = o.optLong("feeCents", 0L),
                timestampEpochMillis = o.optLong("timestampEpochMillis", 0L),
                note = o.optString("note", ""),
                origin = o.optString("origin", "MANUAL"),
                refId = o.optString("refId", ""),
                status = o.optString("status", "CONFIRMED"),
                smsFingerprint = o.optString("smsFingerprint", "")
            )
        }

        val adjustments = mutableListOf<AccountAdjustmentEntity>()
        val adjustmentsArray = root.optJSONArray("accountAdjustments") ?: JSONArray()
        for (i in 0 until adjustmentsArray.length()) {
            val o = adjustmentsArray.getJSONObject(i)
            adjustments += AccountAdjustmentEntity(
                id = o.optLong("id", 0L),
                accountId = o.optString("accountId"),
                deltaCents = o.optLong("deltaCents", 0L),
                kind = o.optString("kind", "MANUAL"),
                timestampEpochMillis = o.optLong("timestampEpochMillis", 0L),
                note = o.optString("note", "")
            )
        }

        val recurringRules = mutableListOf<RecurringRuleEntity>()
        val recurringArray = root.optJSONArray("recurringRules") ?: JSONArray()
        for (i in 0 until recurringArray.length()) {
            val o = recurringArray.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue
            recurringRules += RecurringRuleEntity(
                id = id,
                title = o.optString("title").ifBlank { "Recurring" },
                amountCents = o.optLong("amountCents", 0L),
                categoryId = o.optString("categoryId").ifBlank { CategoryEntity.UNCATEGORIZED_ID },
                accountId = o.optString("accountId", ""),
                intervalDays = o.optInt("intervalDays", 30),
                nextDueMillis = o.optLong("nextDueMillis", 0L),
                isActive = o.optBoolean("isActive", true),
                source = o.optString("source", "MANUAL"),
                lastPaidMillis = if (o.has("lastPaidMillis")) o.optLong("lastPaidMillis") else null,
                createdAtMillis = o.optLong("createdAtMillis", 0L)
            )
        }

        val savingsGoals = mutableListOf<SavingsGoalEntity>()
        val goalsArray = root.optJSONArray("savingsGoals") ?: JSONArray()
        for (i in 0 until goalsArray.length()) {
            val o = goalsArray.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue
            savingsGoals += SavingsGoalEntity(
                id = id,
                title = o.optString("title").ifBlank { "Goal" },
                targetCents = o.optLong("targetCents", 0L),
                deadlineMillis = if (o.has("deadlineMillis")) o.optLong("deadlineMillis") else null,
                linkedAccountId = o.optString("linkedAccountId", ""),
                createdAtMillis = o.optLong("createdAtMillis", 0L)
            )
        }

        val goalContributions = mutableListOf<GoalContributionEntity>()
        val contributionsArray = root.optJSONArray("goalContributions") ?: JSONArray()
        for (i in 0 until contributionsArray.length()) {
            val o = contributionsArray.getJSONObject(i)
            goalContributions += GoalContributionEntity(
                id = o.optLong("id", 0L),
                goalId = o.optString("goalId"),
                amountCents = o.optLong("amountCents", 0L),
                accountId = o.optString("accountId", ""),
                timestampEpochMillis = o.optLong("timestampEpochMillis", 0L),
                note = o.optString("note", "")
            )
        }

        val categoryRules = mutableListOf<CategoryRuleEntity>()
        val rulesArray = root.optJSONArray("categoryRules") ?: JSONArray()
        for (i in 0 until rulesArray.length()) {
            val o = rulesArray.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue
            categoryRules += CategoryRuleEntity(
                id = id,
                keyword = o.optString("keyword"),
                categoryId = o.optString("categoryId").ifBlank { CategoryEntity.UNCATEGORIZED_ID },
                scope = o.optString("scope", "GLOBAL"),
                createdAtMillis = o.optLong("createdAtMillis", 0L)
            )
        }

        val smsMetas = mutableListOf<SmsMetaEntity>()
        val metasArray = root.optJSONArray("smsMetas") ?: JSONArray()
        for (i in 0 until metasArray.length()) {
            val o = metasArray.getJSONObject(i)
            val fingerprint = o.optString("fingerprint")
            if (fingerprint.isBlank()) continue
            smsMetas += SmsMetaEntity(
                fingerprint = fingerprint,
                sender = o.optString("sender"),
                provider = o.optString("provider"),
                receivedAtMillis = o.optLong("receivedAtMillis", 0L),
                processedAtMillis = o.optLong("processedAtMillis", 0L),
                outcome = o.optString("outcome", "QUEUED"),
                txType = o.optString("txType", "UNKNOWN")
            )
        }

        val smsDetections = mutableListOf<SmsDetectionEntity>()
        val detectionsArray = root.optJSONArray("smsDetections") ?: JSONArray()
        for (i in 0 until detectionsArray.length()) {
            val o = detectionsArray.getJSONObject(i)
            val fingerprint = o.optString("fingerprint")
            if (fingerprint.isBlank()) continue
            smsDetections += SmsDetectionEntity(
                fingerprint = fingerprint,
                sender = o.optString("sender"),
                provider = o.optString("provider"),
                txType = o.optString("txType", "UNKNOWN"),
                action = o.optString("action", "REVIEW_ONLY"),
                amountCents = if (o.has("amountCents")) o.optLong("amountCents") else null,
                feeCents = o.optLong("feeCents", 0L),
                timestampEpochMillis = o.optLong("timestampEpochMillis", 0L),
                merchant = o.optString("merchant", ""),
                refId = o.optString("refId", ""),
                accountHint = o.optString("accountHint", ""),
                reportedBalanceCents =
                    if (o.has("reportedBalanceCents")) o.optLong("reportedBalanceCents") else null,
                confidence = o.optInt("confidence", 0),
                reviewReason = o.optString("reviewReason", ""),
                reviewStatus = o.optString("reviewStatus", "PENDING"),
                categoryId = o.optString("categoryId", ""),
                accountId = o.optString("accountId", ""),
                linkedRecordType = o.optString("linkedRecordType", ""),
                linkedRecordId = o.optString("linkedRecordId", ""),
                batchId = o.optString("batchId", ""),
                createdAtMillis = o.optLong("createdAtMillis", 0L),
                updatedAtMillis = o.optLong("updatedAtMillis", 0L)
            )
        }

        val currencySymbol = root.optString("currencySymbol", "৳").ifBlank { "৳" }

        return LedgerSnapshot(
            expenses = expenses,
            monthlyBudgets = monthlyBudgets,
            categoryBudgets = categoryBudgets,
            incomes = incomes,
            categories = categories,
            currencySymbol = currencySymbol,
            accounts = accounts,
            transfers = transfers,
            adjustments = adjustments,
            recurringRules = recurringRules,
            savingsGoals = savingsGoals,
            goalContributions = goalContributions,
            categoryRules = categoryRules,
            smsMetas = smsMetas,
            smsDetections = smsDetections
        )
    }
}
