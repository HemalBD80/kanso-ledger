package com.kanso.spending.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ingest-planner tests over fictional fixtures: deduplication, review modes,
 * balance-only handling and account resolution. Guarantees under test —
 * ambiguous input always queues, duplicates never re-record, and non-financial
 * messages leave no trace.
 */
class SmsIngestPlannerTest {

    private val now = 1_760_000_000_000L
    private val batch = "batch_test_1"

    private fun context(
        mode: SmsImportMode,
        known: Set<String> = emptySet(),
        hints: Map<String, String> = emptyMap(),
        names: Map<String, String> = emptyMap()
    ) = IngestContext(
        knownFingerprints = known,
        mode = mode,
        batchId = batch,
        nowMillis = now,
        hintToAccountId = hints,
        nameToAccountId = names
    )

    private fun msg(body: String, sender: String, at: Long = now) =
        RawInboundSms(sender = sender, body = body, receivedAtMillis = at)

    // ---- review modes ------------------------------------------------------

    @Test
    fun reviewModeQueuesEvenHighConfidenceMessages() {
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_SENT, "bKash")),
            context(SmsImportMode.REVIEW)
        )
        assertEquals(1, plan.items.size)
        val detection = plan.items[0].detection
        assertNotNull(detection)
        assertEquals("PENDING", detection!!.reviewStatus)
        assertFalse(detection.writeLedger)
        assertEquals("QUEUED", plan.items[0].metaOutcome)
        assertEquals(1, plan.pendingCount)
        assertEquals(0, plan.recordedCount)
    }

    @Test
    fun autoModeRecordsConfidentTransactionImmediately() {
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_SENT, "bKash")),
            context(SmsImportMode.AUTO)
        )
        val item = plan.items[0]
        val detection = item.detection!!
        assertEquals("APPROVED", detection.reviewStatus)
        assertTrue(detection.writeLedger)
        assertEquals("RECORDED", item.metaOutcome)
        assertEquals(125_000L, detection.parsed.amountCents)
        assertEquals(500L, detection.parsed.feeCents)
    }

    @Test
    fun ambiguousMessageAlwaysQueuesEvenInAutoMode() {
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank")),
            context(SmsImportMode.AUTO)
        )
        assertEquals(1, plan.items.size)
        val item = plan.items[0]
        assertEquals("AMBIGUOUS", item.metaOutcome)
        assertEquals("PENDING", item.detection!!.reviewStatus)
        assertFalse(item.detection!!.writeLedger)
        assertEquals(LedgerAction.REVIEW_ONLY, item.detection!!.parsed.action)
        assertTrue(item.detection!!.parsed.reviewReason!!.isNotBlank())
    }

    @Test
    fun autoModeQueuesUnclearTypeWithAmountInsteadOfGuessing() {
        // Financial wording with an amount but no recognisable direction: it
        // must never be auto-posted, only offered for review.
        val body = "Money movement of BDT 500.00 has been processed today. Contact branch for detail."
        val plan = SmsIngestPlanner.plan(listOf(msg(body, "HBL Bank")), context(SmsImportMode.AUTO))
        assertEquals(1, plan.items.size)
        val detection = plan.items[0].detection
        assertNotNull(detection)
        assertEquals("PENDING", detection!!.reviewStatus)
        assertFalse(detection.writeLedger)
        assertEquals(LedgerAction.REVIEW_ONLY, detection.parsed.action)
    }

    // ---- deduplication -----------------------------------------------------

    @Test
    fun knownFingerprintIsNeverProcessedTwice() {
        val fingerprint = SmsParser.parse(
            "bKash", SmsFixtures.BKASH_SENT, now
        ).let { (it as SmsParseResult.Financial).transaction.fingerprint }

        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_SENT, "bKash")),
            context(SmsImportMode.AUTO, known = setOf(fingerprint))
        )
        assertEquals(0, plan.items.size)
        assertEquals(1, plan.duplicateCount)
        assertEquals(0, plan.recordedCount)
    }

    @Test
    fun duplicateWithinSameBatchCountsOnce() {
        val plan = SmsIngestPlanner.plan(
            listOf(
                msg(SmsFixtures.BKASH_SENT, "bKash"),
                msg(SmsFixtures.BKASH_SENT, "bKash")   // redelivered copy
            ),
            context(SmsImportMode.AUTO)
        )
        assertEquals(1, plan.items.size)
        assertEquals(1, plan.duplicateCount)
    }

    @Test
    fun sameAmountDifferentReferenceIsNeverMerged() {
        val altered = SmsFixtures.BKASH_SENT.replace("bK6P9Q2R", "bK6P9Q9Z")
        val plan = SmsIngestPlanner.plan(
            listOf(
                msg(SmsFixtures.BKASH_SENT, "bKash"),
                msg(altered, "bKash")
            ),
            context(SmsImportMode.AUTO)
        )
        assertEquals("different refs are different transactions", 2, plan.items.size)
        assertEquals(0, plan.duplicateCount)
        assertEquals(
            "fingerprints must differ",
            plan.items[0].fingerprint,
            plan.items[0].fingerprint
        )
        assertTrue(plan.items[0].fingerprint != plan.items[1].fingerprint)
    }

    // ---- privacy by omission ----------------------------------------------

    @Test
    fun nonFinancialMessagesLeaveNoRows() {
        val plan = SmsIngestPlanner.plan(
            listOf(
                msg(SmsFixtures.BKASH_PROMO, "bKash"),
                msg(SmsFixtures.BKASH_OTP, "bKash"),
                msg("Are we still meeting tomorrow?", "Someone")
            ),
            context(SmsImportMode.AUTO)
        )
        assertEquals(0, plan.items.size)
        assertEquals(3, plan.ignoredCount)
        assertEquals(0, plan.duplicateCount)
    }

    // ---- balance-only notices ---------------------------------------------

    @Test
    fun balanceInquiryWithoutMatchingAccountStillQueuesForAccountChoice() {
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BANK_BALANCE_INQUIRY, "HBL Bank")),
            context(SmsImportMode.AUTO)
        )
        val item = plan.items[0]
        val detection = item.detection!!
        // Nothing to record, but a balance worth attaching to an account.
        assertFalse(detection.writeLedger)
        assertEquals("PENDING", detection.reviewStatus)
        assertEquals("QUEUED", item.metaOutcome)
        assertEquals(5_312_090L, detection.parsed.reportedBalanceCents)
        assertEquals(LedgerAction.IGNORE, detection.parsed.action)
    }

    @Test
    fun balanceInquiryAttachesToAccountWhenNoteMatches() {
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BANK_BALANCE_INQUIRY, "HBL Bank")),
            context(
                SmsImportMode.AUTO,
                hints = mapOf("hbl a/c ***4512" to "acc_bank")
            )
        )
        val item = plan.items[0]
        val detection = item.detection!!
        assertEquals("APPROVED", detection.reviewStatus)
        assertEquals("acc_bank", detection.resolvedAccountId)
        assertFalse(detection.writeLedger)   // still never a ledger row
        assertEquals("IGNORED", item.metaOutcome)
    }

    @Test
    fun cardLimitNoticeProducesMetaOnlyNoDetection() {
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.CARD_BALANCE_ONLY, "VISA Card")),
            context(SmsImportMode.AUTO)
        )
        assertEquals(1, plan.items.size)
        assertNull(plan.items[0].detection)
        assertEquals("IGNORED", plan.items[0].metaOutcome)
    }

    // ---- automatic classification -----------------------------------------

    @Test
    fun autoModeRecordsCashOutAsTransferNotExpense() {
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BKASH_CASHOUT, "bKash")),
            context(SmsImportMode.AUTO)
        )
        val detection = plan.items[0].detection!!
        assertEquals(LedgerAction.RECORD_TRANSFER, detection.parsed.action)
        assertTrue(detection.writeLedger)
        assertEquals("APPROVED", detection.reviewStatus)
    }

    @Test
    fun autoModeRecordsStandaloneBankFeeWithFeeOnly() {
        val plan = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.BANK_FEE, "HBL Bank")),
            context(SmsImportMode.AUTO)
        )
        val detection = plan.items[0].detection!!
        assertEquals(LedgerAction.RECORD_FEE, detection.parsed.action)
        assertTrue(detection.writeLedger)
        assertNull(detection.parsed.amountCents)
        assertEquals(12_000L, detection.parsed.feeCents)
    }

    @Test
    fun refundQueuesInReviewModeAndRecordsInAutoMode() {
        val review = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.CARD_REFUND, "VISA Card")),
            context(SmsImportMode.REVIEW)
        )
        assertEquals("PENDING", review.items[0].detection!!.reviewStatus)

        val auto = SmsIngestPlanner.plan(
            listOf(msg(SmsFixtures.CARD_REFUND, "VISA Card")),
            context(SmsImportMode.AUTO)
        )
        val detection = auto.items[0].detection!!
        assertEquals(LedgerAction.RECORD_INCOME, detection.parsed.action)
        assertTrue(detection.writeLedger)
        assertEquals(115_000L, detection.parsed.amountCents)
    }

    // ---- account resolution ------------------------------------------------

    @Test
    fun accountHintMatchesByDigitsNotExactFormatting() {
        val tx = parseFinancial(SmsFixtures.BANK_SALARY_CREDIT, "HBL Bank")
        assertEquals("***4512", tx.accountHint)
        val resolved = SmsIngestPlanner.resolveAccountId(
            tx,
            context(SmsImportMode.AUTO, hints = mapOf("hbl a/c ***4512" to "acc_bank"))
        )
        assertEquals("acc_bank", resolved)
    }

    @Test
    fun providerNameMatchesAccountNameCaseInsensitively() {
        val tx = parseFinancial(SmsFixtures.BKASH_SENT, "bKash")
        val resolved = SmsIngestPlanner.resolveAccountId(
            tx,
            context(SmsImportMode.AUTO, names = mapOf("Bkash" to "acc_wallet"))
        )
        assertEquals("acc_wallet", resolved)
    }

    @Test
    fun noMatchResolvesToBlankNeverAGuess() {
        val tx = parseFinancial(SmsFixtures.BKASH_SENT, "bKash")
        val resolved = SmsIngestPlanner.resolveAccountId(tx, context(SmsImportMode.AUTO))
        assertEquals("", resolved)
    }

    // ---- plan summary ------------------------------------------------------

    @Test
    fun planCountsMatchItems() {
        val plan = SmsIngestPlanner.plan(
            listOf(
                msg(SmsFixtures.BKASH_SENT, "bKash"),          // auto-record
                msg(SmsFixtures.BKASH_PROMO, "bKash"),          // ignored
                msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank"), // queued
                msg(SmsFixtures.BKASH_SENT, "bKash")            // duplicate
            ),
            context(SmsImportMode.AUTO)
        )
        // One recorded + one queued = items; the promo leaves no row at all.
        assertEquals(2, plan.items.size)
        assertEquals(1, plan.duplicateCount)
        assertEquals(1, plan.ignoredCount)
        assertEquals(1, plan.recordedCount)
        assertEquals(1, plan.pendingCount)
        assertEquals(batch, plan.batchId)
    }

    private fun parseFinancial(body: String, sender: String): ParsedSmsTransaction {
        val result = SmsParser.parse(sender, body, now)
        assertTrue(result is SmsParseResult.Financial)
        return (result as SmsParseResult.Financial).transaction
    }
}
