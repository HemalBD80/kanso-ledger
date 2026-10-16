package com.kanso.spending.domain.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBalanceEngineTest {

    private val cash = AccountSnapshot(
        id = "acc_cash", name = "Cash", type = AccountType.CASH,
        openingBalanceCents = 50_000L, isArchived = false
    )
    private val bank = AccountSnapshot(
        id = "acc_bank", name = "Bank", type = AccountType.BANK,
        openingBalanceCents = 100_000L, isArchived = false
    )

    @Test
    fun openingPlusIncomingMinusOutgoingPlusAdjustments() {
        val state = AccountBalanceEngine.state(
            cash,
            entries = listOf(
                AccountEntry(cash.id, inCents = 20_000L),           // ৳200 in
                AccountEntry(cash.id, outCents = 5_000L),           // ৳50 out
                AccountEntry(cash.id, outCents = 3_000L, feeCents = 200L) // ৳30 + ৳2 fee
            ),
            adjustments = listOf(AccountAdjustment(cash.id, deltaCents = -1_000L))
        )
        // 500 + 200 − (50 + 30 + 2) − 10 = 608.00
        assertEquals(60_800L, state.calculatedBalanceCents)
        assertEquals(20_000L, state.incomingCents)
        assertEquals(8_200L, state.outgoingCents)
        assertEquals(-1_000L, state.adjustmentsCents)
    }

    @Test
    fun transfersMoveMoneyWithoutCountingAsIncomeOrExpense() {
        val start = listOf(
            AccountBalanceEngine.state(cash, emptyList(), emptyList()),
            AccountBalanceEngine.state(bank, emptyList(), emptyList())
        )
        val after = AccountBalanceEngine.applyTransfer(
            start, fromAccountId = bank.id, toAccountId = cash.id, amountCents = 30_000L
        )
        val cashAfter = after.first { it.accountId == cash.id }
        val bankAfter = after.first { it.accountId == bank.id }

        assertEquals(80_000L, cashAfter.calculatedBalanceCents)     // 500 + 300
        assertEquals(70_000L, bankAfter.calculatedBalanceCents)     // 1000 − 300
        // Transfers never appear as income/expense:
        assertEquals(0L, cashAfter.incomingCents)
        assertEquals(0L, bankAfter.outgoingCents)
        assertEquals(30_000L, cashAfter.transfersInCents)
        assertEquals(30_000L, bankAfter.transfersOutCents)
        // Conservation: total net unchanged by an internal transfer.
        assertEquals(
            AccountBalanceEngine.netWorth(start),
            AccountBalanceEngine.netWorth(after)
        )
    }

    @Test
    fun transferWithFeeRemovesFeeFromSourceOnly() {
        val start = listOf(
            AccountBalanceEngine.state(cash, emptyList(), emptyList()),
            AccountBalanceEngine.state(bank, emptyList(), emptyList())
        )
        val after = AccountBalanceEngine.applyTransfer(
            start, fromAccountId = bank.id, toAccountId = cash.id,
            amountCents = 10_000L, feeCents = 500L
        )
        assertEquals(89_500L, after.first { it.accountId == bank.id }.calculatedBalanceCents)
        assertEquals(60_000L, after.first { it.accountId == cash.id }.calculatedBalanceCents)
        // Net drops by exactly the fee.
        assertEquals(
            AccountBalanceEngine.netWorth(start) - 500L,
            AccountBalanceEngine.netWorth(after)
        )
    }

    @Test
    fun pendingEntriesNeverTouchConfirmedBalance() {
        val state = AccountBalanceEngine.state(
            cash,
            entries = listOf(
                AccountEntry(cash.id, inCents = 10_000L, isConfirmed = true),
                AccountEntry(cash.id, inCents = 99_900L, isConfirmed = false) // pending detection
                ),
            adjustments = emptyList()
        )
        assertEquals(60_000L, state.calculatedBalanceCents)         // 500 + 100
        assertEquals(159_900L, state.balanceIfApprovedCents)        // display-only
    }

    @Test
    fun providerReportIsStoredBesideLedgerNeverOverwritten() {
        val state = AccountBalanceEngine.state(
            cash,
            entries = listOf(AccountEntry(cash.id, outCents = 2_000L)),
            adjustments = emptyList()
        ).copy(lastReportedBalanceCents = 46_000L, lastReportedAtMillis = 1_760_000_000_000L)

        assertEquals(48_000L, state.calculatedBalanceCents)         // ledger truth unchanged
        val rec = AccountBalanceEngine.reconcile(state)!!
        assertFalse(rec.isInAgreement)
        assertEquals(-2_000L, rec.differenceCents)                  // reported − ledger
        assertEquals(46_000L, rec.reportedBalanceCents)
        assertEquals(48_000L, rec.ledgerBalanceCents)
    }

    @Test
    fun matchingReportIsInAgreement() {
        val state = AccountBalanceEngine.state(cash, emptyList(), emptyList())
            .copy(lastReportedBalanceCents = 50_000L, lastReportedAtMillis = 1L)
        val rec = AccountBalanceEngine.reconcile(state)!!
        assertTrue(rec.isInAgreement)
        assertEquals(0L, rec.differenceCents)
    }

    @Test
    fun noReportMeansNoReconciliationWarning() {
        val state = AccountBalanceEngine.state(cash, emptyList(), emptyList())
        assertNull(AccountBalanceEngine.reconcile(state))
    }

    @Test
    fun creditCardDebtIsPositiveWhenOwing() {
        val card = AccountSnapshot(
            id = "acc_card", name = "Credit Card", type = AccountType.CARD,
            openingBalanceCents = 0L, isArchived = false
        )
        val state = AccountBalanceEngine.state(
            card,
            entries = listOf(AccountEntry(card.id, outCents = 25_000L)),   // spent ৳250 on credit
            adjustments = emptyList()
        )
        assertEquals(-25_000L, state.calculatedBalanceCents)   // owe ৳250
        assertEquals(25_000L, state.creditDebtCents)           // debt shown positive
        // Total net funds subtract the debt:
        assertEquals(-25_000L, AccountBalanceEngine.netWorth(listOf(state)))
    }

    @Test
    fun archivedAccountsExcludedFromNetWorth() {
        val archived = cash.copy(isArchived = true)
        val state = AccountBalanceEngine.state(archived, emptyList(), emptyList())
        assertEquals(50_000L, state.calculatedBalanceCents)          // still visible alone
        assertEquals(0L, AccountBalanceEngine.netWorth(listOf(state))) // but not in totals
    }

    @Test
    fun selfTransferAndNonPositiveAmountsAreRejected() {
        val start = listOf(AccountBalanceEngine.state(cash, emptyList(), emptyList()))
        val illegalSelf = runCatching {
            AccountBalanceEngine.applyTransfer(start, cash.id, cash.id, 100L)
        }
        assertTrue(illegalSelf.isFailure)
        val illegalAmount = runCatching {
            AccountBalanceEngine.applyTransfer(start, cash.id, bank.id, 0L)
        }
        assertTrue(illegalAmount.isFailure)
    }
}
