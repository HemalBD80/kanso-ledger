package com.kanso.spending.data.local

import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.MonthlyBudgetEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure local JSON snapshot serializer & parser so users can back up or restore
 * their entire on-device SQLite ledger without any cloud account or server.
 */
object LocalBackupManager {

    private const val SCHEMA_VERSION = 1

    data class LedgerSnapshot(
        val expenses: List<ExpenseEntity>,
        val monthlyBudgets: List<MonthlyBudgetEntity>,
        val categoryBudgets: List<CategoryBudgetEntity>,
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

        return root.toString(2)
    }

    fun importFromJsonString(json: String): Result<LedgerSnapshot> {
        return runCatching {
            val root = JSONObject(json)
            val currencySymbol = root.optString("currencySymbol", "$").ifBlank { "$" }

            val expenses = mutableListOf<ExpenseEntity>()
            val expensesArray = root.optJSONArray("expenses") ?: JSONArray()
            for (i in 0 until expensesArray.length()) {
                val obj = expensesArray.getJSONObject(i)
                expenses.add(
                    ExpenseEntity(
                        id = obj.optLong("id", 0L),
                        title = obj.getString("title"),
                        amountCents = obj.getLong("amountCents"),
                        categoryId = obj.optString("categoryId", "dining"),
                        paymentMethod = obj.optString("paymentMethod", "card"),
                        timestampEpochMillis = obj.getLong("timestampEpochMillis"),
                        note = obj.optString("note", ""),
                        isRecurring = obj.optBoolean("isRecurring", false)
                    )
                )
            }

            val monthlyBudgets = mutableListOf<MonthlyBudgetEntity>()
            val monthlyArray = root.optJSONArray("monthlyBudgets") ?: JSONArray()
            for (i in 0 until monthlyArray.length()) {
                val obj = monthlyArray.getJSONObject(i)
                monthlyBudgets.add(
                    MonthlyBudgetEntity(
                        monthKey = obj.getString("monthKey"),
                        overallLimitCents = obj.getLong("overallLimitCents"),
                        updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis())
                    )
                )
            }

            val categoryBudgets = mutableListOf<CategoryBudgetEntity>()
            val categoryArray = root.optJSONArray("categoryBudgets") ?: JSONArray()
            for (i in 0 until categoryArray.length()) {
                val obj = categoryArray.getJSONObject(i)
                categoryBudgets.add(
                    CategoryBudgetEntity(
                        monthKey = obj.getString("monthKey"),
                        categoryId = obj.getString("categoryId"),
                        limitCents = obj.getLong("limitCents")
                    )
                )
            }

            LedgerSnapshot(
                expenses = expenses,
                monthlyBudgets = monthlyBudgets,
                categoryBudgets = categoryBudgets,
                currencySymbol = currencySymbol
            )
        }
    }
}
