package com.kanso.spending.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the "connection works but nothing imports" defect.
 *
 * The root cause was that a local [SmsParseResult.NotFinancial] verdict was
 * TERMINAL — the AI was never consulted — and that verdict was reached for any
 * message lacking the literal token "BDT"/"Tk"/"taka", which silently dropped
 * real bank SMS. These tests pin the corrected behaviour:
 *
 *  - a message that shows a financial signal but cannot be read locally is kept
 *    (queued / AI-eligible), never dropped;
 *  - genuinely non-financial text (OTP, promo, chatter) is still dropped and
 *    still never sent;
 *  - reprocessing is idempotent: the message identity is the DETERMINISTIC local
 *    fingerprint, so a re-import after enabling AI recognises previously-queued
 *    messages and can upgrade them — without double-recording or blind import;
 *  - both Review and Auto modes behave as documented.
 */
class SmsPipelineFixTest {

    private val now = 1_760_000_000_000L

    private fun msg(body: String, sender: String, at: Long = now) =
        RawInboundSms(sender = sender, body = body, receivedAtMillis = at)

    private fun context(
        mode: SmsImportMode,
        known: Set<String> = emptySet(),
        queued: Set<String> = emptySet()
    ) = IngestContext(
        knownFingerprints = known,
        mode = mode,
        batchId = "batch_fix",
        nowMillis = now,
        queuedFingerprints = queued
    )

    private fun financialFingerprint(body: String, sender: String, at: Long = now): String {
        val r = SmsParser.parse(sender, body, at)
        assertTrue("expected a local financial parse for '$body', got $r", r is SmsParseResult.Financial)
        return (r as SmsParseResult.Financial).transaction.fingerprint
    }

    private fun ambiguousFingerprint(body: String, sender: String, at: Long = now): String {
        val r = SmsParser.parse(sender, body, at)
        assertTrue("expected a local ambiguous parse for '$body', got $r", r is SmsParseResult.Ambiguous)
        return (r as SmsParseResult.Ambiguous).fingerprint
    }

    // --------------------------------------------------- eligibility (root cause)

    /** A bank debit with NO "BDT"/"Tk"/"taka" token, only a reference. */
    private val currencylessDebit =
        "Your A/c ***4512 has been debited by 5,000.00 on 15/10/26 10:00. Ref: ABC12345"

    @Test
    fun currencylessBankDebitIsKeptNotDropped() {
        val result = SmsParser.parse("HBL Bank", currencylessDebit, now)
        assertFalse(
            "a currencyless-but-financial bank SMS must not be dropped",
            result is SmsParseResult.NotFinancial
        )
        // With no local currency/amount anchor it queues for review (and is now
        // AI-eligible) instead of vanishing.
        val plan = SmsIngestPlanner.plan(listOf(msg(currencylessDebit, "HBL Bank")), context(SmsImportMode.AUTO))
        assertEquals("the message leaves a trace", 1, plan.items.size)
        assertEquals(0, plan.ignoredCount)
        assertEquals("queued, never auto-posted", 1, plan.pendingCount)
    }

    @Test
    fun bdtSymbolAloneCountsAsACurrencyMention() {
        val body = "৳ 500.00 sent to 01712345678. Ref: XY1234"
        val result = SmsParser.parse("bKash", body, now)
        assertFalse(result is SmsParseResult.NotFinancial)
    }

    @Test
    fun currencylessAmountWithATransactionVerbIsKept() {
        val result = SmsParser.parse("HBL Bank", "You have been debited 5000.00 today.", now)
        assertFalse(result is SmsParseResult.NotFinancial)
    }

    @Test
    fun anAmountWithoutACurrencyOrVerbIsStillDropped() {
        // A bare amount with no financial verb is not enough signal.
        val result = SmsParser.parse("Someone", "The report has 5,000.00 rows.", now)
        assertTrue(result is SmsParseResult.NotFinancial)
    }

    @Test
    fun genuinelyNonFinancialTextIsStillDropped() {
        for (body in listOf(
            SmsFixtures.BKASH_OTP,
            SmsFixtures.BKASH_PROMO,
            SmsFixtures.CASHBACK_PROMO,
            "Are we still meeting tomorrow?"
        )) {
            val result = SmsParser.parse("bKash", body, now)
            assertTrue("must stay non-financial: $body", result is SmsParseResult.NotFinancial)
        }
    }

    // ---------------------------------------------------------- idempotent upgrade

    @Test
    fun aStillPendingMessageIsUpgradedOnReimportWhenNowConfident() {
        val fp = financialFingerprint(SmsFixtures.BKASH_SENT, "bKash")
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_SENT, "bKash")),
            context(SmsImportMode.AUTO, known = setOf(fp), queued = setOf(fp))
        )
        assertEquals(1, plan.items.size)
        assertTrue("planned as an upgrade", plan.items[0].upgrade)
        assertEquals(1, plan.upgradedCount)
        assertEquals(1, plan.recordedCount)
        assertEquals("not counted as a duplicate", 0, plan.duplicateCount)
    }

    @Test
    fun aRecordedFingerprintIsStillADuplicateNeverAnUpgrade() {
        val fp = financialFingerprint(SmsFixtures.BKASH_SENT, "bKash")
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_SENT, "bKash")),
            // Known + already in the ledger (not in the queued set) → skip.
            context(SmsImportMode.AUTO, known = setOf(fp), queued = emptySet())
        )
        assertEquals(0, plan.items.size)
        assertEquals(1, plan.duplicateCount)
        assertEquals(0, plan.upgradedCount)
    }

    @Test
    fun reviewModeNeverUpgradesAPendingMessage() {
        val fp = financialFingerprint(SmsFixtures.BKASH_SENT, "bKash")
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_SENT, "bKash")),
            context(SmsImportMode.REVIEW, known = setOf(fp), queued = setOf(fp))
        )
        assertEquals("nothing auto-recorded in review mode", 0, plan.items.size)
        assertEquals(1, plan.duplicateCount)
    }

    @Test
    fun anAmbiguousMessageIsUpgradedByAConfidentAiExtractionEvenThoughFingerprintsDiffer() {
        val body = SmsFixtures.AMBIGUOUS_UNFAMILIAR
        val localFp = ambiguousFingerprint(body, "HBL Bank")
        val confident = (SmsParser.parse("bKash", SmsFixtures.BKASH_SENT, now) as SmsParseResult.Financial).transaction
        assertNotEquals("the model fingerprint really can differ", localFp, confident.fingerprint)

        val plan = SmsIngestPlanner.plan(
            listOf(msg(body, "HBL Bank")),
            context(SmsImportMode.AUTO, known = setOf(localFp), queued = setOf(localFp)),
            mapOf(0 to SmsParseResult.Financial(confident))
        )
        assertEquals(1, plan.items.size)
        assertTrue(plan.items[0].upgrade)
        assertEquals("identity is the deterministic local fingerprint", localFp, plan.items[0].fingerprint)
        assertEquals(1, plan.recordedCount)
    }

    @Test
    fun anUncertainResultDoesNotUpgradeAPendingMessage() {
        val body = SmsFixtures.AMBIGUOUS_UNFAMILIAR
        val localFp = ambiguousFingerprint(body, "HBL Bank")
        val plan = SmsIngestPlanner.plan(
            listOf(msg(body, "HBL Bank")),
            context(SmsImportMode.AUTO, known = setOf(localFp), queued = setOf(localFp)),
            mapOf(0 to SmsParseResult.NotFinancial("still not money"))
        )
        // Model says non-financial but local was ambiguous → dropped, not upgraded.
        assertEquals(0, plan.items.size)
        assertEquals(0, plan.upgradedCount)
    }

    // ------------------------------------------------------------------- modes

    @Test
    fun autoModeRecordsConfidentTransactionsAndReviewModeQueuesThem() {
        val messages = listOf(
            msg(SmsFixtures.BKASH_SENT, "bKash"),
            msg(SmsFixtures.BKASH_CASHBACK, "bKash"),
            msg(SmsFixtures.MOBILE_RECHARGE, "GP"),
            msg(SmsFixtures.BANK_FEE, "HBL Bank")
        )

        val auto = SmsIngestPlanner.plan(messages, context(SmsImportMode.AUTO))
        assertEquals("confident in Auto mode", 4, auto.recordedCount)
        assertEquals(0, auto.pendingCount)

        val review = SmsIngestPlanner.plan(messages, context(SmsImportMode.REVIEW))
        assertEquals("everything waits in Review mode", 0, review.recordedCount)
        assertEquals(4, review.pendingCount)
    }

    @Test
    fun aLocallyAmbiguousRealMessageQueuesRatherThanGuessing() {
        // TRUST's "TK 206.00 DEBIT" has no direction word the rules recognise, so
        // even Auto mode must queue it — and it is exactly what the AI resolves.
        val auto = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.TRUST_DEBIT, "TRUST Bank")),
            context(SmsImportMode.AUTO)
        )
        assertEquals(0, auto.recordedCount)
        assertEquals(1, auto.pendingCount)
    }

    @Test
    fun cashbackIsIncomeAndFeeQueuesWithoutAnInventedAmount() {
        val auto = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_CASHBACK, "bKash"), msg(SmsFixtures.BANK_FEE, "HBL Bank")),
            context(SmsImportMode.AUTO)
        )
        val cashback = auto.items.first { it.provider == "bKash" }.detection!!.parsed
        assertEquals(SmsTxType.CASHBACK, cashback.type)
        assertEquals(LedgerAction.RECORD_INCOME, cashback.action)

        val fee = auto.items.first { it.txType == SmsTxType.FEE.name }.detection!!.parsed
        assertEquals(LedgerAction.RECORD_FEE, fee.action)
        assertEquals("the fee IS the debit; no principal amount is invented", null, fee.amountCents)
    }

    @Test
    fun balanceNoticeNeverBecomesATransaction() {
        val auto = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_BALANCE_NOTICE, "bKash")),
            context(SmsImportMode.AUTO)
        )
        val detection = auto.items[0].detection
        assertTrue("balance-only is never a ledger write", detection == null || !detection.writeLedger)
        assertEquals(0, auto.recordedCount)
    }

    @Test
    fun duplicateMessagesAreSkippedNotReprocessed() {
        val fp = financialFingerprint(SmsFixtures.BKASH_SENT, "bKash")
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_SENT, "bKash")),
            context(SmsImportMode.AUTO, known = setOf(fp))
        )
        assertEquals(0, plan.items.size)
        assertEquals(1, plan.duplicateCount)
        assertEquals(0, plan.recordedCount)
    }
}
