package com.kanso.spending.domain.accounts

import com.kanso.spending.data.local.entity.AccountAdjustmentEntity
import com.kanso.spending.data.local.entity.AccountEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.TransferEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account-assembly tests: per-account balances, transfer net-zero, payment
 * method fallback for legacy rows, pending-vs-confirmed isolation, fees and
 * reconciliation. Uses in-memory rows only — no database required.
 */
class AccountStateFactoryTest {

    private val cash = AccountEntity.CASH_ID
    private val bank = AccountEntity.BANK_ID
    private val wallet = AccountEntity.WALLET_ID
    private val card = AccountEntity.CARD_ID

    private val t0 = 1_760_000_000_000L

    private fun expense(
        amountCents: Long,
        paymentMethod: String = "cash",
        accountId: String = "",
        feeCents: Long = 0L
    ) = ExpenseEntity(
        title = "Test spend",
        amountCents = amountCents,
        categoryId = "other",
        paymentMethod = paymentMethod,
        timestampEpochMillis = t0,
        accountId = accountId,
        feeCents = feeCents
    )

    private fun income(amountCents: Long, paymentMethod: String = "cash", accountId: String = "") =
        IncomeEntity(
            title = "Money in",
            amountCents = amountCents,
            sourceId = "other",
            paymentMethod = paymentMethod,
            timestampEpochMillis = t0,
            accountId = accountId
        )

    private fun states(
        accounts: List<AccountEntity> = AccountEntity.SEEDS,
        expenses: List<ExpenseEntity> = emptyList(),
        incomes: List<IncomeEntity> = emptyList(),
        transfers: List<TransferEntity> = emptyList(),
        adjustments: List<AccountAdjustmentEntity> = emptyList()
    ) = AccountStateFactory.states(accounts, expenses, incomes, transfers, adjustments)

    private fun List<AccountState>.byId(id: String) = first { it.accountId == id }

    // ---- fresh install -----------------------------------------------------

    @Test
    fun freshInstallSeedsAllAccountsAtZero() {
        val result = states()
        assertEquals(4, result.size)
        result.forEach { state ->
            assertEquals("৳0 expected for ${state.name}", 0L, state.calculatedBalanceCents)
        }
    }

    // ---- balance equation --------------------------------------------------

    @Test
    fun balanceIsOpeningPlusIncomeMinusExpense() {
        val accounts = listOf(
            AccountEntity(id = cash, name = "Cash", type = "CASH", openingBalanceCents = 50_000)
        )
        val result = states(
            accounts = accounts,
            expenses = listOf(expense(1_500L, accountId = cash)),
            incomes = listOf(income(20_000L, accountId = cash))
        )
        assertEquals(50_000L + 20_000L - 1_500L, result.byId(cash).calculatedBalanceCents)
    }

    @Test
    fun feesReduceTheAccountTheyDebit() {
        val result = states(
            expenses = listOf(expense(85_000L, paymentMethod = "wallet", feeCents = 500L))
        )
        // ৳850 purchase + ৳5 fee, both leave the wallet.
        assertEquals(-85_500L, result.byId(wallet).calculatedBalanceCents)
        assertEquals(500L, result.byId(wallet).feesCents)
        assertEquals("fee counts as outflow", 85_500L, result.byId(wallet).outgoingCents)
    }

    @Test
    fun standaloneFeeRowWithZeroPrincipalStillDebits() {
        val result = states(
            expenses = listOf(expense(0L, paymentMethod = "transfer", feeCents = 12_000L))
        )
        assertEquals(-12_000L, result.byId(bank).calculatedBalanceCents)
        assertEquals(0L, result.byId(bank).incomingCents)
    }

    // ---- payment-method fallback ------------------------------------------

    @Test
    fun blankAccountIdFallsBackToPaymentMethod() {
        // Rows written before v3 carry no account id — they must still land on
        // the account their payment method implies, never nowhere.
        val result = states(
            expenses = listOf(
                expense(1_000L, paymentMethod = "wallet"),
                expense(2_000L, paymentMethod = "debit"),
                expense(3_000L, paymentMethod = "cash")
            )
        )
        assertEquals(-1_000L, result.byId(wallet).calculatedBalanceCents)
        assertEquals(-2_000L, result.byId(card).calculatedBalanceCents)
        assertEquals(-3_000L, result.byId(cash).calculatedBalanceCents)
        assertEquals(0L, result.byId(bank).calculatedBalanceCents)
    }

    @Test
    fun danglingAccountIdFallsBackInsteadOfLosingTheRow() {
        val result = states(expenses = listOf(expense(700L, paymentMethod = "wallet", accountId = "acc_gone")))
        assertEquals(-700L, result.byId(wallet).calculatedBalanceCents)
        assertEquals(0L, result.byId(cash).calculatedBalanceCents)
    }

    @Test
    fun legacyCardMethodResolvesToCardAccount() {
        assertEquals(card, AccountStateFactory.defaultAccountIdForMethod("card"))
        assertEquals(card, AccountStateFactory.defaultAccountIdForMethod("credit"))
        assertEquals(bank, AccountStateFactory.defaultAccountIdForMethod("transfer"))
        assertEquals(wallet, AccountStateFactory.defaultAccountIdForMethod("wallet"))
        assertEquals(cash, AccountStateFactory.defaultAccountIdForMethod("cash"))
        assertEquals(cash, AccountStateFactory.defaultAccountIdForMethod(""))
    }

    // ---- transfers ---------------------------------------------------------

    @Test
    fun transferMovesMoneyWithoutChangingNetFunds() {
        val transfer = TransferEntity(
            id = "tr_1",
            fromAccountId = wallet,
            toAccountId = cash,
            amountCents = 50_000L,
            timestampEpochMillis = t0
        )
        val result = states(transfers = listOf(transfer))
        assertEquals(-50_000L, result.byId(wallet).calculatedBalanceCents)
        assertEquals(50_000L, result.byId(cash).calculatedBalanceCents)
        assertEquals("net across accounts must be zero", 0L, AccountBalanceEngine.netWorth(result))
        // Not income, not expense.
        assertEquals(0L, result.byId(wallet).incomingCents)
        assertEquals(0L, result.byId(wallet).outgoingCents)
        assertEquals(50_000L, result.byId(wallet).transfersOutCents)
    }

    @Test
    fun transferFeeReducesNetFundsOnce() {
        val transfer = TransferEntity(
            id = "tr_1",
            fromAccountId = bank,
            toAccountId = wallet,
            amountCents = 10_000L,
            feeCents = 150L,
            timestampEpochMillis = t0
        )
        val result = states(transfers = listOf(transfer))
        assertEquals(-10_150L, result.byId(bank).calculatedBalanceCents)
        assertEquals(10_000L, result.byId(wallet).calculatedBalanceCents)
        assertEquals(-150L, AccountBalanceEngine.netWorth(result))
    }

    @Test
    fun pendingTransferNeverTouchesConfirmedBalances() {
        val transfer = TransferEntity(
            id = "tr_1",
            fromAccountId = bank,
            toAccountId = wallet,
            amountCents = 10_000L,
            timestampEpochMillis = t0,
            status = "PENDING"
        )
        val result = states(transfers = listOf(transfer))
        assertEquals(0L, result.byId(bank).calculatedBalanceCents)
        assertEquals(0L, result.byId(wallet).calculatedBalanceCents)
        // Visible for display, excluded from confirmed totals.
        assertEquals(10_000L, result.byId(wallet).balanceIfApprovedCents)
    }

    // ---- adjustments & reconciliation --------------------------------------

    @Test
    fun manualAdjustmentShiftsTheBalance() {
        val result = states(
            adjustments = listOf(
                AccountAdjustmentEntity(
                    accountId = cash,
                    deltaCents = -250L,
                    kind = "RECONCILE",
                    timestampEpochMillis = t0
                )
            )
        )
        assertEquals(-250L, result.byId(cash).calculatedBalanceCents)
        assertEquals(AdjustmentKind.RECONCILE, result.byId(cash).adjustments.first().kind)
    }

    @Test
    fun reportedBalanceIsComparedNeverSubstituted() {
        val accounts = listOf(
            AccountEntity(
                id = bank,
                name = "Bank Account",
                type = "BANK",
                openingBalanceCents = 100_000L,
                lastReportedBalanceCents = 99_000L,
                lastReportedAtMillis = t0
            )
        )
        val state = states(accounts = accounts).byId(bank)
        // Ledger truth stays; the report appears beside it as a difference.
        assertEquals(100_000L, state.calculatedBalanceCents)
        val reconciliation = state.reconciliation
        assertNotNull(reconciliation)
        assertEquals(-1_000L, reconciliation!!.differenceCents)
        assertTrue(!reconciliation.isInAgreement)
        assertEquals(99_000L, reconciliation.reportedBalanceCents)
    }

    @Test
    fun noReportedBalanceMeansNoReconciliationClaim() {
        assertNull(states().byId(bank).reconciliation)
    }

    // ---- card debt & net worth --------------------------------------------

    @Test
    fun cardSpendingBecomesDebtAndLowersNetWorth() {
        val result = states(expenses = listOf(expense(5_000L, paymentMethod = "credit")))
        assertEquals(5_000L, result.byId(card).creditDebtCents)
        assertEquals(-5_000L, AccountBalanceEngine.netWorth(result))
    }

    @Test
    fun archivedAccountsDropOutOfNetWorth() {
        val accounts = AccountEntity.SEEDS.map {
            if (it.id == card) it.copy(isArchived = true) else it
        }
        val result = states(
            accounts = accounts,
            expenses = listOf(expense(5_000L, paymentMethod = "credit"))
        )
        assertEquals(0L, AccountBalanceEngine.netWorth(result))
        // The row still exists and still shows its own balance.
        assertEquals(-5_000L, result.byId(card).calculatedBalanceCents)
    }

    // ---- unconfirmed rows ---------------------------------------------------

    @Test
    fun unconfirmedEntryNeverCountsAsConfirmed() {
        val accounts = AccountEntity.SEEDS
        val entries = listOf(
            AccountEntry(accountId = cash, outCents = 100L, isConfirmed = false)
        )
        val state = AccountBalanceEngine.state(
            accounts.first { it.id == cash }.let {
                AccountStateFactory.run { it.toSnapshot() }
            },
            entries,
            emptyList()
        )
        assertEquals(0L, state.calculatedBalanceCents)
        assertEquals(100L, state.pendingOutCents)
    }
}
