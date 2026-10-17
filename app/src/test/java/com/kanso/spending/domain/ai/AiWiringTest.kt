package com.kanso.spending.domain.ai

import com.kanso.spending.data.model.SpendingCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the three wiring components added to connect the real API to
 * categorisation, insights and the on-device diagnostic.
 *
 * All transport access is faked — no credential, no network, no paid request —
 * exactly as required for unit tests. The production path calls the real
 * provider; these tests prove the logic around it is correct and safe.
 */
class AiWiringTest {

    /** A fake transport that records what it was asked to send and replies once. */
    private class FakeTransport(
        private val reply: AiTransportResult,
        private val onSend: (String, String) -> Unit = { _, _ -> }
    ) : AiTransport {
        override fun sendChat(config: AiConfig, systemPrompt: String, userMessage: String): AiTransportResult {
            onSend(systemPrompt, userMessage)
            return reply
        }
    }

    private fun config() = AiConfig(modelId = "z-ai/glm-5.3-flash", baseUrl = "https://integrate.api.nvidia.com/v1", apiKey = "nvapi-test")

    // ------------------------------------------------- AiConnectionProbe

    @Test
    fun probeNotConfiguredWithoutKey() {
        val cfg = AiConfig(modelId = "m", baseUrl = "https://x", apiKey = null)
        assertEquals(AiConnectionProbe.Result.NotConfigured, AiConnectionProbe.probe(FakeTransport(AiTransportResult.Success("OK")), cfg))
    }

    @Test
    fun probeSuccessReportsModelAndNeverKey() {
        val result = AiConnectionProbe.probe(FakeTransport(AiTransportResult.Success("OK")), config())
        assertTrue(result is AiConnectionProbe.Result.Success)
        val described = AiConnectionProbe.describe(result)
        assertTrue(described.contains("z-ai/glm-5.3-flash"))
        assertTrue("description must not leak the key", !described.contains("nvapi-test"))
    }

    @Test
    fun probeMapsEveryTransportFailureToATypedResult() {
        val cfg = config()
        assertEquals(AiConnectionProbe.Result.Unauthorized, AiConnectionProbe.probe(FakeTransport(AiTransportResult.Unauthorized), cfg))
        assertEquals(AiConnectionProbe.Result.RateLimited, AiConnectionProbe.probe(FakeTransport(AiTransportResult.RateLimited), cfg))
        assertEquals(AiConnectionProbe.Result.NetworkError, AiConnectionProbe.probe(FakeTransport(AiTransportResult.NetworkError), cfg))
        assertEquals(AiConnectionProbe.Result.Malformed, AiConnectionProbe.probe(FakeTransport(AiTransportResult.Malformed), cfg))
        assertEquals(AiConnectionProbe.Result.HttpError(500), AiConnectionProbe.probe(FakeTransport(AiTransportResult.HttpError(500)), cfg))
    }

    @Test
    fun probeSendsOnlyTheTrivialPingPrompt() {
        var seenSystem: String? = null
        var seenUser: String? = null
        AiConnectionProbe.probe(
            FakeTransport(AiTransportResult.Success("OK")) { s, u -> seenSystem = s; seenUser = u },
            config()
        )
        // The diagnostic must not carry any SMS or ledger content.
        assertTrue(seenSystem!!.contains("OK"))
        assertEquals("ping", seenUser)
    }

    // ------------------------------------------------- AiCategoryMapper

    @Test
    fun mapperResolvesRealSuggestionsToRealCategories() {
        assertEquals(SpendingCategory.DINING.id, AiCategoryMapper.toCategoryId("dining"))
        assertEquals(SpendingCategory.DINING.id, AiCategoryMapper.toCategoryId("restaurant"))
        assertEquals(SpendingCategory.GROCERIES.id, AiCategoryMapper.toCategoryId("grocery shopping"))
        assertEquals(SpendingCategory.TRANSIT.id, AiCategoryMapper.toCategoryId("transport"))
        assertEquals(SpendingCategory.WELLNESS.id, AiCategoryMapper.toCategoryId("pharmacy"))
        assertEquals(SpendingCategory.LEISURE.id, AiCategoryMapper.toCategoryId("entertainment"))
    }

    @Test
    fun mapperRejectsNonCategoryAndBlankSuggestions() {
        // A P2P transfer is not a spending category — must never be forced into one.
        assertNull(AiCategoryMapper.toCategoryId("p2p_transfer"))
        assertNull(AiCategoryMapper.toCategoryId("transfer"))
        assertNull(AiCategoryMapper.toCategoryId("salary"))
        assertNull(AiCategoryMapper.toCategoryId("gift"))
        assertNull(AiCategoryMapper.toCategoryId("cryptocurrency"))
        assertNull(AiCategoryMapper.toCategoryId(""))
        assertNull(AiCategoryMapper.toCategoryId(null))
    }

    @Test
    fun mapperResolvesMobileAndInternetToTheBillsBucket() {
        // "Mobile & Internet" resolves to the ledger's Bills & Utilities bucket.
        assertEquals("bills", AiCategoryMapper.toCategoryId("Mobile & Internet"))
        assertEquals("bills", AiCategoryMapper.toCategoryId("mobile recharge"))
        assertEquals("bills", AiCategoryMapper.toCategoryId("internet bill"))
    }

    @Test
    fun mapperOnlyEverReturnsARealBundledId() {
        val allowed = SpendingCategory.entries.map { it.id }.toSet() + "bills"
        val inputs = listOf("dining", "random noise", "subscription", " rent ", "xyzzy", "book", "mobile recharge")
        for (input in inputs) {
            val mapped = AiCategoryMapper.toCategoryId(input)
            if (mapped != null) {
                assertTrue("'$input' mapped to '$mapped' which is not a real category", mapped in allowed)
            }
        }
    }

    // ------------------------------------------------- AiPaymentMethodMapper

    @Test
    fun paymentMethodMapperResolvesRealSuggestionsOnly() {
        assertEquals("wallet", AiPaymentMethodMapper.toMethodId("bKash wallet"))
        assertEquals("wallet", AiPaymentMethodMapper.toMethodId("mobile wallet"))
        assertEquals("debit", AiPaymentMethodMapper.toMethodId("Debit Card"))
        assertEquals("credit", AiPaymentMethodMapper.toMethodId("credit card"))
        assertEquals("transfer", AiPaymentMethodMapper.toMethodId("bank transfer"))
        assertEquals("cash", AiPaymentMethodMapper.toMethodId("cash"))
        // Unknown / non-method text must resolve to null (caller keeps its default).
        assertNull(AiPaymentMethodMapper.toMethodId("p2p_transfer"))
        assertNull(AiPaymentMethodMapper.toMethodId("something else"))
        assertNull(AiPaymentMethodMapper.toMethodId(""))
        assertNull(AiPaymentMethodMapper.toMethodId(null))
    }

    // ------------------------------------------------- AiInsightsNarrator

    @Test
    fun narratorReturnsNullWhenUnconfigured() {
        val cfg = AiConfig(modelId = "m", baseUrl = "https://x", apiKey = null)
        val out = AiInsightsNarrator.narrate(FakeTransport(AiTransportResult.Success("ok")), cfg, 0L, 0L, emptyList())
        assertNull(out)
    }

    @Test
    fun narratorReturnsNullOnEveryTransportFailure() {
        val cfg = config()
        for (failure in listOf<AiTransportResult>(
            AiTransportResult.Unauthorized, AiTransportResult.RateLimited,
            AiTransportResult.NetworkError, AiTransportResult.Malformed, AiTransportResult.HttpError(500)
        )) {
            assertNull(AiInsightsNarrator.narrate(FakeTransport(failure), cfg, 100L, 200L, emptyList()))
        }
    }

    @Test
    fun narratorSendsOnlyAggregateFiguresNeverSms() {
        var seenUser: String? = null
        AiInsightsNarrator.narrate(
            FakeTransport(AiTransportResult.Success("You spent carefully.")) { _, u -> seenUser = u },
            config(),
            incomeCents = 500_000L,   // ৳5,000.00
            expenseCents = 320_000L,   // ৳3,200.00
            topCategories = listOf("Dining" to 120_000L, "Transit" to 40_000L)
        )
        val prompt = seenUser!!
        assertTrue(prompt.contains("৳5,000.00"))
        assertTrue(prompt.contains("৳3,200.00"))
        assertTrue(prompt.contains("Dining"))
        // No raw message text or identifiers can be present — only totals.
        assertTrue(!prompt.contains("017") && !prompt.contains("@"))
    }

    @Test
    fun narratorStripsMarkdownFencesAndCapsLength() {
        val cfg = config()
        val fenced = AiInsightsNarrator.narrate(
            FakeTransport(AiTransportResult.Success("```json\nYou spent ৳100.\n```")),
            cfg, 0L, 0L, emptyList()
        )
        assertEquals("You spent ৳100.", fenced)

        val long = "x".repeat(1000)
        val capped = AiInsightsNarrator.narrate(FakeTransport(AiTransportResult.Success(long)), cfg, 0L, 0L, emptyList())!!
        assertTrue("narrative must be length-capped", capped.length <= 481)
    }

    @Test
    fun narratorReturnsNullForEmptyReply() {
        assertNull(AiInsightsNarrator.narrate(FakeTransport(AiTransportResult.Success("   ")), config(), 0L, 0L, emptyList()))
    }
}
