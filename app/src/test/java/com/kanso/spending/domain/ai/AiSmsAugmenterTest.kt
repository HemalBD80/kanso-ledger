package com.kanso.spending.domain.ai

import com.kanso.spending.domain.sms.IngestContext
import com.kanso.spending.domain.sms.RawInboundSms
import com.kanso.spending.domain.sms.SmsFixtures
import com.kanso.spending.domain.sms.SmsImportMode
import com.kanso.spending.domain.sms.SmsIngestPlanner
import com.kanso.spending.domain.sms.SmsParseResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rules for the AI-assist seam — tested with a fake AI, so no network
 * and no credential. These pin the properties the ledger and the user's wallet
 * depend on:
 *
 *  - non-financial messages are filtered locally and never transmitted;
 *  - a confident local parse is never overridden and never costs an API call;
 *  - only genuine ambiguity reaches the AI;
 *  - every AI failure (error, unusable reply, thrown exception) degrades to the
 *    local result rather than blocking, dropping, or guessing a transaction;
 *  - with AI disabled the pipeline is byte-for-byte the offline behaviour;
 *  - a resolved AI result flows through the unchanged planner, so dedup and
 *    review rules apply to it exactly as they do to local parses.
 */
class AiSmsAugmenterTest {

    private val now = 1_760_000_000_000L

    private fun msg(body: String, sender: String = "bKash") =
        RawInboundSms(sender = sender, body = body, receivedAtMillis = now)

    /**
     * [AiSmsAugmenter.buildOverrides] is suspend because the injected AI call
     * is async; tests run it synchronously so no real coroutine dispatcher or
     * network is involved.
     */
    private fun AiSmsAugmenter.buildOverridesSync(messages: List<RawInboundSms>): AiSmsAugmenter.Result =
        runBlocking { buildOverrides(messages) }

    /** Builds an augmenter whose fake AI records consultations and returns [reply]. */
    private fun augmenter(
        enabled: Boolean = true,
        reply: (RawInboundSms) -> AiSmsInterpreter.Outcome
    ): Pair<AiSmsAugmenter, MutableList<RawInboundSms>> {
        val consulted = mutableListOf<RawInboundSms>()
        val a = AiSmsAugmenter(
            consultAi = { m -> consulted += m; reply(m) },
            enabled = { enabled }
        )
        return a to consulted
    }

    private fun extracted(amount: String = "1250.00") =
        AiSmsInterpreter.Outcome.Extracted(
            SmsFixtures.parse(SmsFixtures.BKASH_SENT).let {
                (it as SmsParseResult.Financial).transaction
            }.copy(amountCents = (amount.toDouble() * 100).toLong())
        )

    // ------------------------------------------------------- local-first filter

    @Test
    fun nonFinancialMessagesAreFilteredLocallyAndNeverSent() {
        val (aug, consulted) = augmenter { extracted() }
        val result = aug.buildOverridesSync(
            listOf(msg(SmsFixtures.BKASH_PROMO), msg(SmsFixtures.BKASH_OTP))
        )
        assertTrue("nothing transmitted", consulted.isEmpty())
        assertEquals(0, result.aiConsulted)
        assertEquals(
            listOf(
                AiSmsAugmenter.Route.LOCAL_NOT_FINANCIAL,
                AiSmsAugmenter.Route.LOCAL_NOT_FINANCIAL
            ),
            result.routes
        )
        assertTrue("no overrides", result.overrides.isEmpty())
    }

    @Test
    fun confidentLocalParseIsNeverOverriddenAndCostsNoApiCall() {
        val (aug, consulted) = augmenter { extracted() }
        val result = aug.buildOverridesSync(
            listOf(
                msg(SmsFixtures.BKASH_SENT),
                msg(SmsFixtures.BANK_SALARY_CREDIT, "HBL Bank"),
                msg(SmsFixtures.CARD_PURCHASE, "City Bank")
            )
        )
        assertEquals("deterministic parser stays authoritative — no spend", 0, consulted.size)
        assertTrue(result.overrides.isEmpty())
        assertEquals(3, result.routes.count { it == AiSmsAugmenter.Route.LOCAL_CONFIDENT })
    }

    @Test
    fun onlyGenuinelyAmbiguousMessagesReachTheAi() {
        val (aug, consulted) = augmenter { extracted() }
        aug.buildOverridesSync(
            listOf(
                msg(SmsFixtures.BKASH_SENT),                       // confident
                msg(SmsFixtures.BKASH_PROMO),                       // not financial
                msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank"),   // ambiguous → AI
                msg(SmsFixtures.BKASH_OTP)                          // not financial
            )
        )
        assertEquals(1, consulted.size)
        assertEquals(SmsFixtures.AMBIGUOUS_UNFAMILIAR, consulted.single().body)
    }

    // --------------------------------------------------------- resolution paths

    @Test
    fun resolvedAmbiguityProducesAnOverrideThatPlannerAccepts() {
        val (aug, _) = augmenter { extracted("990.00") }
        val messages = listOf(msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank"))
        val result = aug.buildOverridesSync(messages)

        assertEquals(AiSmsAugmenter.Route.AI_RESOLVED, result.routes.single())
        assertTrue(result.overrides.containsKey(0))
        assertTrue(result.overrides[0] is SmsParseResult.Financial)
        assertEquals(99_000L, (result.overrides[0] as SmsParseResult.Financial).transaction.amountCents)

        // The override must actually take effect downstream in the real planner.
        val plan = SmsIngestPlanner.plan(messages, context(), result.overrides)
        assertEquals("resolved message is now planned", 1, plan.items.size)
        assertEquals(1, plan.recordedCount + plan.pendingCount)
    }

    @Test
    fun failedAiDegradesToTheLocalAmbiguousResultNeverDropsTheMessage() {
        val failures = listOf(
            AiSmsInterpreter.Outcome.Failed("rate limited"),
            AiSmsInterpreter.Outcome.NotFinancial("model said no"),
            AiSmsInterpreter.Outcome.Failed("unparseable")
        )
        for (f in failures) {
            val (aug, _) = augmenter { f }
            val messages = listOf(msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank"))
            val result = aug.buildOverridesSync(messages)
            assertEquals(AiSmsAugmenter.Route.AI_FALLBACK, result.routes.single())
            assertTrue("no override on failure", result.overrides.isEmpty())

            // The local ambiguous outcome still reaches the review queue.
            val plan = SmsIngestPlanner.plan(messages, context(), result.overrides)
            assertEquals("message is never lost", 1, plan.items.size)
            assertEquals(1, plan.pendingCount)
        }
    }

    @Test
    fun aThrowingAiCallIsCaughtPerMessageAndCannotAbortTheBatch() {
        val (aug, _) = augmenter { error("network exploded") }
        val result = aug.buildOverridesSync(
            listOf(
                msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank"),
                msg(SmsFixtures.BKASH_SENT) // still handled normally
            )
        )
        assertEquals(AiSmsAugmenter.Route.AI_FALLBACK, result.routes[0])
        assertEquals(AiSmsAugmenter.Route.LOCAL_CONFIDENT, result.routes[1])
        assertTrue(result.overrides.isEmpty())
    }

    // ------------------------------------------------------------- master switch

    @Test
    fun disabledAiConsultsNothingAndMatchesOfflineBehaviour() {
        val (aug, consulted) = augmenter(enabled = false) { extracted() }
        val messages = listOf(msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank"))
        val result = aug.buildOverridesSync(messages)

        assertTrue("zero calls when disabled", consulted.isEmpty())
        assertEquals(0, result.aiConsulted)
        assertEquals(AiSmsAugmenter.Route.AI_DISABLED, result.routes.single())
        assertTrue(result.overrides.isEmpty())

        // Identical to planning with no overrides at all.
        val withAi = SmsIngestPlanner.plan(messages, context(), result.overrides)
        val offline = SmsIngestPlanner.plan(messages, context())
        assertEquals(offline.items.size, withAi.items.size)
        assertEquals(offline.pendingCount, withAi.pendingCount)
    }

    @Test
    fun disabledAiStillRoutesLocalClassificationUnchanged() {
        val (aug, _) = augmenter(enabled = false) { extracted() }
        val result = aug.buildOverridesSync(
            listOf(msg(SmsFixtures.BKASH_SENT), msg(SmsFixtures.BKASH_PROMO))
        )
        assertEquals(
            listOf(
                AiSmsAugmenter.Route.LOCAL_CONFIDENT,
                AiSmsAugmenter.Route.LOCAL_NOT_FINANCIAL
            ),
            result.routes
        )
    }

    // -------------------------------------------------------------- accounting

    @Test
    fun routeCountsReportWhatActuallyHappened() {
        val (aug, _) = augmenter { m ->
            // Resolve the first ambiguous message, fail on any later one.
            if (augConsultedCount++ == 0) extracted() else AiSmsInterpreter.Outcome.Failed("x")
        }
        augConsultedCount = 0
        val result = aug.buildOverridesSync(
            listOf(
                msg(SmsFixtures.BKASH_SENT),
                msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank"),
                msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "UBL Bank"),
                msg(SmsFixtures.BKASH_PROMO)
            )
        )
        assertEquals(4, result.routes.size)
        assertEquals(2, result.aiConsulted)
        assertEquals(1, result.aiResolved)
        assertEquals(1, result.overrides.size)
    }

    @Test
    fun aiNeverBypassesReviewWhenTheExtractedTransactionIsFlagged() {
        // An AI extraction that is itself uncertain (needsReview) must still be
        // queued — auto-commit is decided by validation, not by AI preference.
        val uncertain = AiSmsInterpreter.Outcome.Extracted(
            (SmsFixtures.parse(SmsFixtures.BKASH_SENT) as SmsParseResult.Financial)
                .transaction.copy(needsReview = true)
        )
        val (aug, _) = augmenter { uncertain }
        val messages = listOf(msg(SmsFixtures.AMBIGUOUS_UNFAMILIAR, "HBL Bank"))
        val result = aug.buildOverridesSync(messages)
        val plan = SmsIngestPlanner.plan(messages, context(), result.overrides)

        assertEquals("uncertain result queues, never auto-posts", 1, plan.pendingCount)
        assertEquals(0, plan.recordedCount)
        assertFalse(plan.items.isEmpty())
    }

    private fun context(mode: SmsImportMode = SmsImportMode.AUTO) = IngestContext(
        knownFingerprints = emptySet(),
        mode = mode,
        batchId = "test",
        nowMillis = now,
        hintToAccountId = emptyMap(),
        nameToAccountId = emptyMap()
    )

    /** Mutable counter used by the fake AI to vary its reply per call. */
    private var augConsultedCount = 0
}
