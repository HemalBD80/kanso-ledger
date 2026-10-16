package com.kanso.spending.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests over fictional fixtures. Covers every supported provider and
 * transaction type, ambiguous handling, and idempotent fingerprints.
 */
class SmsParserTest {

    private fun financial(fixture: String, sender: String = "bKash"): ParsedSmsTransaction {
        val result = SmsFixtures.parse(fixture, sender)
        assertTrue("expected Financial, got $result", result is SmsParseResult.Financial)
        return (result as SmsParseResult.Financial).transaction
    }

    // ---- bKash ------------------------------------------------------------

    @Test
    fun bkashSendMoneyParsesAmountFeeBalanceAndRef() {
        val tx = financial(SmsFixtures.BKASH_SENT, "bKash")
        assertEquals(SmsTxType.MONEY_SENT, tx.type)
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(125_000L, tx.amountCents)           // ৳1,250.00
        assertEquals(500L, tx.feeCents)                   // ৳5.00
        assertEquals(432_050L, tx.reportedBalanceCents)   // ৳4,320.50
        assertEquals("bK6P9Q2R", tx.referenceId)
        assertEquals("bKash", tx.provider)
        assertFalse(tx.needsReview)
        assertTrue(tx.confidence >= 80)
    }

    @Test
    fun bkashReceivedIsIncome() {
        val tx = financial(SmsFixtures.BKASH_RECEIVED, "bKash")
        assertEquals(SmsTxType.MONEY_RECEIVED, tx.type)
        assertEquals(LedgerAction.RECORD_INCOME, tx.action)
        assertEquals(300_000L, tx.amountCents)
        assertEquals(0L, tx.feeCents)
        assertEquals(745_025L, tx.reportedBalanceCents)
    }

    @Test
    fun bkashCashOutCarriesFee() {
        val tx = financial(SmsFixtures.BKASH_CASHOUT, "bKash")
        assertEquals(SmsTxType.WITHDRAWAL, tx.type)
        assertEquals(LedgerAction.RECORD_TRANSFER, tx.action)   // cash-out: transfer, not spend
        assertEquals(500_000L, tx.amountCents)
        assertEquals(1_050L, tx.feeCents)
    }

    @Test
    fun bkashPromoIsNotFinancial() {
        val result = SmsFixtures.parse(SmsFixtures.BKASH_PROMO, "bKash")
        assertTrue(result is SmsParseResult.NotFinancial)
    }

    @Test
    fun otpMessageIsNotFinancial() {
        val result = SmsFixtures.parse(SmsFixtures.BKASH_OTP, "bKash")
        assertTrue(result is SmsParseResult.NotFinancial)
    }

    // ---- Nagad ------------------------------------------------------------

    @Test
    fun nagadSendParsesAmountAndBalance() {
        val tx = financial(SmsFixtures.NAGAD_SENT, "Nagad")
        assertEquals("Nagad", tx.provider)
        assertEquals(SmsTxType.MONEY_SENT, tx.type)
        assertEquals(50_000L, tx.amountCents)             // ৳500.00
        assertEquals(210_000L, tx.reportedBalanceCents)   // ৳2,100.00
        assertEquals("NG4T7Y8U", tx.referenceId)
    }

    @Test
    fun nagadReceiveIsIncome() {
        val tx = financial(SmsFixtures.NAGAD_RECEIVED, "Nagad")
        assertEquals(SmsTxType.MONEY_RECEIVED, tx.type)
        assertEquals(LedgerAction.RECORD_INCOME, tx.action)
        assertEquals(120_000L, tx.amountCents)
    }

    // ---- Rocket -----------------------------------------------------------

    @Test
    fun rocketReceiveParses() {
        val tx = financial(SmsFixtures.ROCKET_RECEIVED, "Rocket")
        assertEquals("Rocket", tx.provider)
        assertEquals(LedgerAction.RECORD_INCOME, tx.action)
        assertEquals(300_000L, tx.amountCents)
        assertEquals(745_025L, tx.reportedBalanceCents)
        assertEquals("RK9X2Y3Z", tx.referenceId)
    }

    @Test
    fun rocketSendIsOutgoing() {
        val tx = financial(SmsFixtures.ROCKET_SENT, "Rocket")
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(75_000L, tx.amountCents)
    }

    // ---- Bank -------------------------------------------------------------

    @Test
    fun bankCardPurchaseIsExpenseWithMerchant() {
        val tx = financial(SmsFixtures.BANK_DEBIT_PURCHASE, "HBL Bank")
        assertEquals(SmsTxType.PURCHASE, tx.type)
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(85_000L, tx.amountCents)             // ৳850.00
        assertEquals("TXN442101", tx.referenceId)
        assertEquals(1_234_011L, tx.reportedBalanceCents) // ৳12,340.11
        assertTrue("merchant should be detected", tx.merchantOrCounterparty.contains("AGORA", true))
    }

    @Test
    fun salaryCreditIsIncome() {
        val tx = financial(SmsFixtures.BANK_SALARY_CREDIT, "HBL Bank")
        assertEquals(SmsTxType.MONEY_RECEIVED, tx.type)
        assertEquals(LedgerAction.RECORD_INCOME, tx.action)
        assertEquals(2_500_000L, tx.amountCents)          // ৳25,000.00
        assertEquals("***4512", tx.accountHint)
    }

    @Test
    fun atmWithdrawalIsTransferNotExpense() {
        val tx = financial(SmsFixtures.BANK_ATM_WITHDRAWAL, "HBL Bank")
        assertEquals(SmsTxType.WITHDRAWAL, tx.type)
        assertEquals(LedgerAction.RECORD_TRANSFER, tx.action)
        assertEquals(500_000L, tx.amountCents)
    }

    @Test
    fun standaloneFeeIsRecordedAsFee() {
        val tx = financial(SmsFixtures.BANK_FEE, "HBL Bank")
        assertEquals(SmsTxType.FEE, tx.type)
        assertEquals(LedgerAction.RECORD_FEE, tx.action)
        assertEquals(12_000L, tx.feeCents)                // ৳120.00 in poisha
        assertNull(tx.amountCents)                        // no separate principal
    }

    @Test
    fun balanceInquiryCreatesNoTransaction() {
        val tx = financial(SmsFixtures.BANK_BALANCE_INQUIRY, "HBL Bank")
        assertEquals(SmsTxType.BALANCE_ONLY, tx.type)
        assertEquals(LedgerAction.IGNORE, tx.action)
        assertNull(tx.amountCents)
        assertFalse(tx.needsReview)
        assertEquals(5_312_090L, tx.reportedBalanceCents)
    }

    @Test
    fun cashDepositIsTransferBetweenOwnAccounts() {
        val tx = financial(SmsFixtures.BANK_CASH_DEPOSIT, "HBL Bank")
        assertEquals(SmsTxType.DEPOSIT, tx.type)
        assertEquals(LedgerAction.RECORD_TRANSFER, tx.action)
        assertEquals(1_000_000L, tx.amountCents)
    }

    // ---- Card -------------------------------------------------------------

    @Test
    fun creditCardPurchaseIsExpense() {
        val tx = financial(SmsFixtures.CARD_PURCHASE, "VISA Card")
        assertEquals(SmsTxType.PURCHASE, tx.type)
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(230_000L, tx.amountCents)            // ৳2,300.00
        assertEquals("TXN9981", tx.referenceId)
        // "Available Limit" must NOT be treated as a ledger balance.
        assertNull(tx.reportedBalanceCents)
    }

    @Test
    fun creditCardRefundIsMoneyIn() {
        val tx = financial(SmsFixtures.CARD_REFUND, "VISA Card")
        assertEquals(SmsTxType.REFUND, tx.type)
        assertEquals(LedgerAction.RECORD_INCOME, tx.action)
        assertEquals(115_000L, tx.amountCents)            // ৳1,150.00
    }

    @Test
    fun creditCardLimitNoticeCreatesNoTransaction() {
        val tx = financial(SmsFixtures.CARD_BALANCE_ONLY, "VISA Card")
        assertEquals(LedgerAction.IGNORE, tx.action)
        assertNull(tx.amountCents)
        assertNull(tx.reportedBalanceCents)               // limit ≠ balance
    }

    // ---- Ambiguity --------------------------------------------------------

    @Test
    fun ambiguousMessageIsQueuedForReviewNotGuessed() {
        val result = SmsFixtures.parse(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank")
        when (result) {
            is SmsParseResult.Ambiguous -> assertTrue(result.reason.isNotBlank())
            is SmsParseResult.Financial -> {
                assertTrue("must demand review", result.transaction.needsReview)
                assertTrue(
                    "action must not auto-post",
                    result.transaction.action == LedgerAction.REVIEW_ONLY
                )
            }
            is SmsParseResult.NotFinancial -> fail("financial-sounding message dropped: ${result.reason}")
        }
    }

    @Test
    fun messageWithoutCurrencyIsIgnored() {
        val result = SmsParser.parse("Someone", "Hello, are we still meeting tomorrow?", 1_760_000_000_000L)
        assertTrue(result is SmsParseResult.NotFinancial)
    }

    // ---- Fingerprints / idempotency ---------------------------------------

    @Test
    fun sameMessageParsedTwiceYieldsIdenticalFingerprint() {
        val a = SmsFixtures.parse(SmsFixtures.BKASH_SENT, "bKash")
        val b = SmsFixtures.parse(SmsFixtures.BKASH_SENT, "bKash")
        assertEquals(fingerprintOf(a), fingerprintOf(b))
    }

    @Test
    fun sameAmountDifferentReferencesGetDistinctFingerprints() {
        val one = SmsParser.parse("bKash", SmsFixtures.BKASH_SENT, 1_760_000_000_000L)
        val altered = SmsFixtures.BKASH_SENT.replace("bK6P9Q2R", "bK6P9Q9Z")
        val two = SmsParser.parse("bKash", altered, 1_760_000_000_000L)
        assertNotEquals(fingerprintOf(one), fingerprintOf(two))
    }

    @Test
    fun identicalAmountDifferentTimeGetDistinctFingerprints() {
        // No reference id available → composite fingerprint must include time,
        // so two separate ৳900 sends a minute apart never collapse into one.
        val msg = "You have sent BDT 900.00 to 01712345678. New Balance BDT 1,000.00."
        val a = SmsParser.parse("bKash", msg, 1_760_000_000_000L)
        val b = SmsParser.parse("bKash", msg, 1_760_000_060_000L)
        assertNotEquals(fingerprintOf(a), fingerprintOf(b))
    }

    @Test
    fun sameAmountDifferentReferenceIsNeverMerged() {
        // Two legitimate same-amount transactions with different refs must not
        // merge just because amounts are identical.
        val one = SmsParser.parse(
            "bKash",
            SmsFixtures.BKASH_SENT.replace("Ref", "").replace("bK6P9Q2R", "AAA1111"),
            1_760_000_000_000L
        )
        val two = SmsParser.parse(
            "bKash",
            SmsFixtures.BKASH_SENT.replace("bK6P9Q2R", "BBB2222"),
            1_760_000_000_000L
        )
        assertNotEquals(fingerprintOf(one), fingerprintOf(two))
    }

    @Test
    fun referenceBasedFingerprintIgnoresTimestamp() {
        // Same reference (provider says it's the same transaction) → same key
        // even if the message was re-delivered later.
        val a = SmsParser.parse("bKash", SmsFixtures.BKASH_SENT, 1_760_000_000_000L)
        val b = SmsParser.parse("bKash", SmsFixtures.BKASH_SENT, 1_760_000_500_000L)
        assertEquals(fingerprintOf(a), fingerprintOf(b))
    }

    // ---- Amount parsing ----------------------------------------------------

    @Test
    fun parseAmountHandlesCommasAndDecimals() {
        assertEquals(125_000L, SmsParser.parseAmount("1,250.00"))
        assertEquals(500L, SmsParser.parseAmount("5.00"))
        assertEquals(432_050L, SmsParser.parseAmount("4,320.50"))
        assertEquals(100L, SmsParser.parseAmount("1"))
        assertEquals(1_050L, SmsParser.parseAmount("10.50"))
        assertNull(SmsParser.parseAmount(""))
    }

    @Test
    fun feesAndBalanceAreNotConfusedWithPrincipal() {
        val tx = financial(SmsFixtures.BKASH_SENT, "bKash")
        assertEquals(125_000L, tx.amountCents)    // not 500 (fee), not 432050 (balance)
        assertEquals(500L, tx.feeCents)
        assertEquals(432_050L, tx.reportedBalanceCents)
    }

    private fun fingerprintOf(result: SmsParseResult): String = when (result) {
        is SmsParseResult.Financial -> result.transaction.fingerprint
        is SmsParseResult.Ambiguous -> result.fingerprint
        is SmsParseResult.NotFinancial -> "none"
    }
}

private fun fail(message: String): Nothing = throw AssertionError(message)
