package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AccountKind { CASH, BANK, WALLET, CARD }

/**
 * A user-owned account: cash, bank, mobile wallet or card. Every account keeps
 * its own balance (opening + activity + adjustments) and its own slice of the
 * transaction history. Opening balances default to 0 — a fresh install holds
 * ৳0.00 everywhere and contains no sample data.
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["name"])]
)
data class AccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** [AccountKind] name: CASH / BANK / WALLET / CARD. */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "opening_balance_cents")
    val openingBalanceCents: Long = 0L,

    @ColumnInfo(name = "is_archived", defaultValue = "0")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** Latest provider-reported balance captured from an SMS (minor units). */
    @ColumnInfo(name = "last_reported_balance_cents")
    val lastReportedBalanceCents: Long? = null,

    @ColumnInfo(name = "last_reported_at_millis")
    val lastReportedAtMillis: Long? = null,

    /** Masked account hint that links provider messages here, e.g. "***4512". */
    @ColumnInfo(name = "note", defaultValue = "")
    val note: String = "",

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = 0L
) {
    companion object {
        /** Structural account ids — identical to the v2→v3 migration seeds. */
        const val CASH_ID = "acc_cash"
        const val BANK_ID = "acc_bank"
        const val WALLET_ID = "acc_wallet"
        const val CARD_ID = "acc_card"

        /** The four defaults can never be deleted (archive at most). */
        val STRUCTURAL_IDS = listOf(CASH_ID, BANK_ID, WALLET_ID, CARD_ID)

        /**
         * Seeded on every fresh install at ৳0.00 — identical rows to the ones
         * MIGRATION_2_3 inserts for existing installs.
         */
        val SEEDS: List<AccountEntity> = listOf(
            AccountEntity(id = CASH_ID, name = "Cash", type = AccountKind.CASH.name, sortOrder = 10),
            AccountEntity(id = BANK_ID, name = "Bank Account", type = AccountKind.BANK.name, sortOrder = 20),
            AccountEntity(id = WALLET_ID, name = "Mobile Wallet", type = AccountKind.WALLET.name, sortOrder = 30),
            AccountEntity(id = CARD_ID, name = "Card", type = AccountKind.CARD.name, sortOrder = 40)
        )
    }
}
