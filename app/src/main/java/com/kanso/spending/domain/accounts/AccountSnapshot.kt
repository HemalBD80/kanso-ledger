package com.kanso.spending.domain.accounts

/**
 * Pure snapshot of an account row (kept free of Room/Android imports so
 * balance logic is unit-testable on the JVM).
 */
data class AccountSnapshot(
    val id: String,
    val name: String,
    val type: AccountType,
    val openingBalanceCents: Long,
    val isArchived: Boolean,
    val sortOrder: Int = 0,
    val lastReportedBalanceCents: Long? = null,
    val lastReportedAtMillis: Long? = null,
    val note: String = ""
)
