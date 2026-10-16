package com.kanso.spending.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recharge, utility-bill and data-package recognition over fictional fixtures —
 * message classes Smart SMS Tracking explicitly claims to support. Each one
 * must classify deterministically as money-out (an expense), extract the
 * amount, and stay above the auto-record confidence floor; balance text in the
 * same message must never be mistaken for the amount.
 */
class SmsParserRechargeBillTest {

    private fun financial(fixture: String, sender: String): ParsedSmsTransaction {
        val result = SmsFixtures.parse(fixture, sender)
        assertTrue("expected Financial, got $result", result is SmsParseResult.Financial)
        return (result as SmsParseResult.Financial).transaction
    }

    @Test
    fun mobileRechargeIsExpenseWithBalanceAndReference() {
        val tx = financial(SmsFixtures.BKASH_RECHARGE, "bKash")
        assertEquals("bKash", tx.provider)
        assertEquals(SmsTxType.PURCHASE, tx.type)
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(15_000L, tx.amountCents)             // ৳150.00 — not the balance
        assertEquals(0L, tx.feeCents)
        assertEquals(105_000L, tx.reportedBalanceCents)   // ৳1,050.00 kept separately
        assertEquals("RC5T6U7V", tx.referenceId)
        assertFalse(tx.needsReview)
        assertTrue(tx.confidence >= 65)
    }

    @Test
    fun electricityBillIsExpenseWithAccountHintAndReference() {
        val tx = financial(SmsFixtures.BANK_ELECTRICITY_BILL, "EBL")
        assertEquals("Bank", tx.provider)
        assertEquals(SmsTxType.PURCHASE, tx.type)
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(125_000L, tx.amountCents)            // ৳1,250.00 — not the balance
        assertEquals(0L, tx.feeCents)                     // the bill is not a "fee"
        assertEquals(2_000_000L, tx.reportedBalanceCents) // ৳20,000.00 avl bal
        assertEquals("***4512", tx.accountHint)
        assertEquals("BILL7712", tx.referenceId)
        assertFalse(tx.needsReview)
        assertTrue(tx.confidence >= 65)
    }

    @Test
    fun internetPackageFromUnknownSenderIsExpense() {
        val tx = financial(SmsFixtures.GENERIC_INTERNET_PACKAGE, "GP")
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(SmsTxType.PURCHASE, tx.type)
        assertEquals(34_900L, tx.amountCents)             // ৳349.00
        assertEquals("GP882114", tx.referenceId)
        assertFalse(tx.needsReview)
    }

    @Test
    fun rechargePromoIsStillIgnored() {
        // "recharge and get ..." is marketing — it must never become a purchase.
        val result = SmsFixtures.parse(
            "Recharge and get 10% cashback! Offer valid till 31 Oct. T&C apply.",
            "bKash"
        )
        assertTrue("expected NotFinancial, got $result", result is SmsParseResult.NotFinancial)
    }
}
