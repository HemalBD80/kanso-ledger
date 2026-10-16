package com.kanso.spending.data.local.entity

/**
 * Read-only projection that unifies income and expense rows for the account
 * balance engine. Room materialises this from plain @Query SELECTs — it is not
 * a table.
 *
 * @param directionIn 1 for money-in rows, 0 for money-out rows.
 * @param feeCents provider fee debited alongside the expense (0 for income).
 * @param accountId may be blank for legacy rows written before v3; callers
 *                  resolve those through the payment-method mapping.
 */
data class ActivityRow(
    val id: Long,
    val amountCents: Long,
    val feeCents: Long,
    val directionIn: Int,
    val accountId: String,
    val paymentMethod: String,
    val timestampEpochMillis: Long,
    val source: String
)
