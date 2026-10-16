package com.kanso.spending.data.local

import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure local JSON snapshot serializer & parser so users can back up or restore
 * their entire on-device SQLite ledger without any cloud account or server.
 *
 * Schema v2 adds `incomes` and `categories`. v1 snapshots (expenses, budgets,
 * currency only) still restore; their category ids resolve to what the snapshot
 * declares or to "Other" on import so links never dangle.
 */
object LocalBackupManager {

    private const val SCHEMA_VERSION = 2

    data class LedgerSnapshot(
        val expenses: List<ExpenseEntity>,
        val monthlyBudgets: List<MonthlyBudgetEntity>,
        val categoryBudgets: List<CategoryBudgetEntity>,
        val incomes: List<IncomeEntity> = emptyList(),
        val categories: List<CategoryEntity> = emptyList(),
        val currencySymbol: String
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
                isRecurring = o.optBoolean("isRecurring", false)
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
                note = o.optString("note", "")
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

        val currencySymbol = root.optString("currencySymbol", "৳").ifBlank { "৳" }

        return LedgerSnapshot(
            expenses = expenses,
            monthlyBudgets = monthlyBudgets,
            categoryBudgets = categoryBudgets,
            incomes = incomes,
            categories = categories,
            currencySymbol = currencySymbol
        )
    }
}
