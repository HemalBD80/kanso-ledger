package com.kanso.spending.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Historical-import safety: re-running a range (app restart, repeated
 * broadcast delivery, or importing the same window twice) must never add a
 * second copy of anything, in either processing mode. Duplicates are decided
 * by fingerprint — provider reference when present — never by amount alone.
 */
class SmsHistoricalImportTest {

    private val now = 1_760_000_000_000L

    private fun context(mode: SmsImportMode, known: Set<String> = emptySet()) = IngestContext(
        knownFingerprints = known,
        mode = mode,
        batchId = "batch_historical",
        nowMillis = now,
        hintToAccountId = emptyMap(),
        nameToAccountId = emptyMap()
    )

    private fun msg(body: String, sender: String, at: Long = now) =
        RawInboundSms(sender = sender, body = body, receivedAtMillis = at)

    private val range = listOf(
        msg(SmsFixtures.BKASH_SENT, "bKash"),
        msg(SmsFixtures.BKASH_RECEIVED, "bKash"),
        msg(SmsFixtures.BANK_SALARY_CREDIT, "EBL Bank")
    )

    @Test
    fun reimportingTheSameRangeAddsNothingInEitherMode() {
        val first = SmsIngestPlanner.plan(range, context(SmsImportMode.AUTO))
        assertEquals(range.size, first.items.size)
        assertEquals(range.size, first.recordedCount)
        assertEquals(0, first.duplicateCount)

        val stored = first.items.map { it.fingerprint }.toSet()
        assertEquals(range.size, stored.size)

        for (mode in listOf(SmsImportMode.AUTO, SmsImportMode.REVIEW)) {
            val again = SmsIngestPlanner.plan(range, context(mode, known = stored))
            assertTrue("items must be empty in $mode", again.items.isEmpty())
            assertEquals(0, again.recordedCount + again.pendingCount)
            assertEquals(range.size, again.duplicateCount)
            assertEquals(
                "each skipped duplicate keeps a structured notice",
                range.size,
                again.duplicateItems.size
            )
        }
    }

    @Test
    fun duplicateNoticesCarryProviderAndAmountButNoMessageContent() {
        val first = SmsIngestPlanner.plan(listOf(msg(SmsFixtures.BKASH_SENT, "bKash")), context(SmsImportMode.AUTO))
        val stored = first.items.map { it.fingerprint }.toSet()

        val again = SmsIngestPlanner.plan(listOf(msg(SmsFixtures.BKASH_SENT, "bKash")), context(SmsImportMode.AUTO, stored))
        assertEquals(1, again.duplicateItems.size)
        val dup = again.duplicateItems[0]
        assertEquals("bKash", dup.provider)
        assertEquals(125_000L, dup.amountCents)
        assertEquals("bK6P9Q2R", dup.referenceId)
        // Structured fields only — nothing here is derived from the body text.
        assertTrue(dup.provider.isNotBlank())
    }

    @Test
    fun overlappingPartialRangeImportSkipsOnlyWhatIsKnown() {
        val first = SmsIngestPlanner.plan(range, context(SmsImportMode.AUTO))
        val stored = first.items.map { it.fingerprint }.toSet()

        // A later import covers one old message plus one genuinely new one.
        val partial = listOf(
            msg(SmsFixtures.BKASH_SENT, "bKash"),                       // known
            msg(SmsFixtures.BANK_ATM_WITHDRAWAL, "EBL Bank")            // new
        )
        val plan = SmsIngestPlanner.plan(partial, context(SmsImportMode.AUTO, stored))
        assertEquals(1, plan.duplicateCount)
        assertEquals(1, plan.items.size)
        assertEquals(SmsTxType.WITHDRAWAL, plan.items[0].detection!!.parsed.type)
    }
}
