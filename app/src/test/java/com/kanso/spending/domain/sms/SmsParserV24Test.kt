package com.kanso.spending.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v2.4 local-parser regression suite for the supplied real-world message shapes.
 *
 * These pin what the DETERMINISTIC parser does with each example (the AI can
 * improve on it, but offline behaviour must still be safe and correct). Nothing
 * here touches the network.
 */
class SmsParserV24Test {

    private fun financial(fixture: String, sender: String): ParsedSmsTransaction {
        val result = SmsFixtures.parse(fixture, sender)
        assertTrue("expected Financial, got $result", result is SmsParseResult.Financial)
        return (result as SmsParseResult.Financial).transaction
    }

    // ------------------------------------------------------- Example A: bank

    @Test
    fun exampleA_bankDebitExtractsAmountAccountAndBalance() {
        val tx = financial(SmsFixtures.TRUST_DEBIT, "TRUST")
        assertEquals(20_600L, tx.amountCents)                 // ৳206.00 — not the balance
        assertEquals("033***558", tx.accountHint)             // masked account hint
        assertEquals(952_895L, tx.reportedBalanceCents)       // ৳9,528.95 kept separately
        // The type is genuinely unclear from the text alone → queued, never guessed.
        assertEquals(LedgerAction.REVIEW_ONLY, tx.action)
        assertTrue(tx.needsReview)
    }

    @Test
    fun exampleA_parsesThe12HourTimestamp() {
        val tx = financial(SmsFixtures.TRUST_DEBIT, "TRUST")
        assertNotEquals("a stated timestamp must be parsed, not defaulted", 1_760_000_000_000L, tx.timestampMillis)
        val cal = Calendar.getInstance().apply { timeInMillis = tx.timestampMillis }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, cal.get(Calendar.MONTH))
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))        // 12:17 AM → 00:17
    }

    // ---------------------------------------------------- Example B: cashback

    @Test
    fun exampleB_cashbackIsIncomeAndNotARefund() {
        val tx = financial(SmsFixtures.BKASH_CASHBACK, "bKash")
        assertEquals(SmsTxType.CASHBACK, tx.type)
        assertEquals(LedgerAction.RECORD_INCOME, tx.action)
        assertEquals(2_500L, tx.amountCents)                  // ৳25.00
        assertEquals("DJE9WXYIXT", tx.referenceId)
        assertEquals(12_858L, tx.reportedBalanceCents)        // ৳128.58
        assertFalse("confident cashback may auto-record", tx.needsReview)
    }

    @Test
    fun exampleB_congratulatoryWordingDoesNotGetFilteredAsPromo() {
        // "Congratulations!" would once have dropped this real credit.
        val result = SmsFixtures.parse(SmsFixtures.BKASH_CASHBACK, "bKash")
        assertTrue("a referenced credit is not a promo", result is SmsParseResult.Financial)
    }

    @Test
    fun cashbackWithoutAReferenceIsStillFilteredAsPromo() {
        val result = SmsFixtures.parse(SmsFixtures.CASHBACK_PROMO, "bKash")
        assertTrue("expected NotFinancial, got $result", result is SmsParseResult.NotFinancial)
    }

    // ---------------------------------------------------- Example C: recharge

    @Test
    fun exampleC_mobileRechargeExpensePreservesLeadingZeroReference() {
        val tx = financial(SmsFixtures.MOBILE_RECHARGE, "GP")
        assertEquals(SmsTxType.PURCHASE, tx.type)
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(12_000L, tx.amountCents)                 // ৳120.00
        assertEquals("000319000740", tx.referenceId)          // leading zeros preserved
        // The sender's phone number must NOT become a payment method or account.
        assertNull("no account inferred from the phone number", tx.accountHint)
    }

    // ---------------------------------------------------------- reversal/fee

    @Test
    fun reversalIsQueuedNeverTreatedAsIncome() {
        val tx = financial(SmsFixtures.CARD_REVERSAL, "VISA")
        assertEquals(SmsTxType.REVERSAL, tx.type)
        assertEquals(LedgerAction.REVIEW_ONLY, tx.action)
        assertTrue(tx.needsReview)
        assertEquals(115_000L, tx.amountCents)
    }

    @Test
    fun creditCardRefundStaysARefundNotAReversal() {
        val tx = financial(SmsFixtures.CARD_REFUND, "VISA")
        assertEquals(SmsTxType.REFUND, tx.type)
        assertEquals(LedgerAction.RECORD_INCOME, tx.action)
    }

    @Test
    fun salaryCreditIsIncome() {
        val tx = financial(SmsFixtures.BANK_CREDIT_SALARY, "HBL Bank")
        assertEquals(SmsTxType.MONEY_RECEIVED, tx.type)
        assertEquals(LedgerAction.RECORD_INCOME, tx.action)
        assertEquals(4_200_000L, tx.amountCents)              // ৳42,000.00
    }

    @Test
    fun balanceNoticeNeverCreatesAnAmount() {
        val tx = financial(SmsFixtures.BKASH_BALANCE_NOTICE, "bKash")
        assertEquals(SmsTxType.BALANCE_ONLY, tx.type)
        assertEquals(LedgerAction.IGNORE, tx.action)
        assertNull("a balance notice never invents a transaction amount", tx.amountCents)
        assertEquals(150_000L, tx.reportedBalanceCents)
    }

    // --------------------------------------------------------------- duplicates

    @Test
    fun duplicateReferenceCollapsesToTheSameFingerprint() {
        // Two deliveries of the same message (broadcast repeat) share a fingerprint.
        val a = financial(SmsFixtures.BKASH_CASHBACK, "bKash")
        val b = financial(SmsFixtures.BKASH_CASHBACK, "bKash")
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun fingerprintIsNeverAmountOnly() {
        val refA = SmsParser.fingerprint("bKash", "bKash", "REFAAA", 10_000L, SmsTxType.PURCHASE, 1L)
        val refB = SmsParser.fingerprint("bKash", "bKash", "REFBBB", 10_000L, SmsTxType.PURCHASE, 1L)
        assertNotEquals(refA, refB)
    }
}
