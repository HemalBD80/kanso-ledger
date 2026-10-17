package com.kanso.spending.domain.ai

import com.kanso.spending.domain.sms.RawInboundSms
import com.kanso.spending.domain.sms.SmsFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics suite for the AI-assisted import path.
 *
 * A successful "OK" ping can coexist with zero imports, so these tests pin the
 * pieces that turn that symptom into an actionable message:
 *  - every transport failure maps to a specific, credential-free category;
 *  - an invalid model ID is called out explicitly;
 *  - the reply parser tolerates a large reasoning envelope and array-style
 *    content, but still fails closed on a blank/truncated completion;
 *  - the classification probe reports success / non-financial / failure
 *    distinctly, using only a fictional fixture.
 */
class AiDiagnosticsTest {

    private val config = AiConfig(
        modelId = "test-model-id",
        baseUrl = "https://integrate.example.test/v1",
        apiKey = "test-key-not-real"
    )

    private class FakeTransport(private val result: AiTransportResult) : AiTransport {
        var callCount = 0
        override fun sendChat(config: AiConfig, systemPrompt: String, userMessage: String): AiTransportResult {
            callCount++
            return result
        }
    }

    /** Build an OpenAI envelope with the assistant JSON as an escaped string. */
    private fun envelope(content: String, messageExtra: String = ""): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t")
        return """{"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"$escaped"$messageExtra},"finish_reason":"stop"}]}"""
    }

    private val expenseProposal =
        """{"is_financial":true,"provider":"bKash","transaction_type":"expense","amount_bdt":"1250.00","direction":"debit","confidence":0.95}"""

    // ------------------------------------------------------ failure categories

    @Test
    fun everyTransportFailureMapsToASpecificCategory() {
        val cases = mapOf(
            (AiTransportResult.NotConfigured as AiTransportResult) to AiSmsInterpreter.FailureKind.NOT_CONFIGURED,
            AiTransportResult.Unauthorized to AiSmsInterpreter.FailureKind.AUTH,
            AiTransportResult.RateLimited to AiSmsInterpreter.FailureKind.RATE_LIMITED,
            AiTransportResult.HttpError(500) to AiSmsInterpreter.FailureKind.PROVIDER_HTTP,
            AiTransportResult.NetworkError to AiSmsInterpreter.FailureKind.NETWORK,
            AiTransportResult.Malformed() to AiSmsInterpreter.FailureKind.MALFORMED_REPLY,
            AiTransportResult.Malformed("unknown shape") to AiSmsInterpreter.FailureKind.MALFORMED_REPLY,
            AiTransportResult.EmptyReply("empty") to AiSmsInterpreter.FailureKind.EMPTY_REPLY,
            AiTransportResult.ProviderError("model not found") to AiSmsInterpreter.FailureKind.INVALID_MODEL
        )
        for ((transport, expected) in cases) {
            val out = AiSmsInterpreter(FakeTransport(transport))
                .interpret(config, "bKash", "You have sent BDT 100.00 TrxID AA11", 0L)
            assertTrue("$transport must fail", out is AiSmsInterpreter.Outcome.Failed)
            assertEquals("$transport", expected, (out as AiSmsInterpreter.Outcome.Failed).kind)
        }
    }

    @Test
    fun httpStatusIsMappedToTheActionableCategory() {
        // A 404 with no model hint is a wrong endpoint/format, not a model problem.
        val endpoint = AiSmsInterpreter(FakeTransport(AiTransportResult.HttpError(404)))
            .interpret(config, "bKash", "You have sent BDT 100.00 TrxID AA11", 0L)
        assertEquals(
            AiSmsInterpreter.FailureKind.UNSUPPORTED_ENDPOINT,
            (endpoint as AiSmsInterpreter.Outcome.Failed).kind
        )
        assertTrue(endpoint.reason.contains("API format"))

        // A 404/400/422 that names the model is an invalid-model problem.
        for (code in listOf(400, 404, 422)) {
            val invalid = AiSmsInterpreter(
                FakeTransport(AiTransportResult.HttpError(code, "model 'foo' does not exist"))
            ).interpret(config, "bKash", "You have sent BDT 100.00 TrxID AA11", 0L)
            val failed = invalid as AiSmsInterpreter.Outcome.Failed
            assertEquals(AiSmsInterpreter.FailureKind.INVALID_MODEL, failed.kind)
            assertTrue("HTTP $code must hint at the model ID", failed.reason.contains("model ID"))
        }

        // A 400 that does not name the model is a generic provider error.
        val generic = AiSmsInterpreter(FakeTransport(AiTransportResult.HttpError(400, "bad request")))
            .interpret(config, "bKash", "You have sent BDT 100.00 TrxID AA11", 0L)
        assertEquals(
            AiSmsInterpreter.FailureKind.PROVIDER_HTTP,
            (generic as AiSmsInterpreter.Outcome.Failed).kind
        )
    }

    @Test
    fun malformedReplyAndRejectedClassificationAreDistinctCategories() {
        val malformed = AiSmsInterpreter(FakeTransport(AiTransportResult.Success(envelope("not json"))))
            .interpret(config, "bKash", "You have sent BDT 100.00 TrxID AA11", 0L)
        assertEquals(
            AiSmsInterpreter.FailureKind.MALFORMED_REPLY,
            (malformed as AiSmsInterpreter.Outcome.Failed).kind
        )

        val rejected = AiSmsInterpreter(
            FakeTransport(AiTransportResult.Success(envelope("""{"is_financial":true,"transaction_type":"expense","amount_bdt":null}""")))
        ).interpret(config, "bKash", "You have sent BDT 100.00 TrxID AA11", 0L)
        assertEquals(
            AiSmsInterpreter.FailureKind.VALIDATION_REJECTED,
            (rejected as AiSmsInterpreter.Outcome.Failed).kind
        )
    }

    @Test
    fun blankBodyIsNotSentAndIsCategorised() {
        val transport = FakeTransport(AiTransportResult.Success(envelope(expenseProposal)))
        val out = AiSmsInterpreter(transport).interpret(config, "bKash", "    ", 0L)
        assertEquals(0, transport.callCount)
        assertEquals(
            AiSmsInterpreter.FailureKind.EMPTY_AFTER_REDACTION,
            (out as AiSmsInterpreter.Outcome.Failed).kind
        )
    }

    @Test
    fun theAugmenterAggregatesFailureKindsForTheImportDiagnostic() {
        val kinds = listOf(
            AiSmsInterpreter.FailureKind.NETWORK,
            AiSmsInterpreter.FailureKind.MALFORMED_REPLY,
            AiSmsInterpreter.FailureKind.NETWORK
        )
        var i = 0
        val augmenter = AiSmsAugmenter(
            consultAi = {
                val k = kinds[i++]
                AiSmsInterpreter.Outcome.Failed("boom", k)
            },
            enabled = { true }
        )
        val messages = List(3) {
            RawInboundSms("HBL Bank", "Money movement of BDT for transaction $it processed. Ref: R$it", 1_760_000_000_000L + it)
        }
        val result = runBlocking { augmenter.buildOverrides(messages) }
        assertEquals(3, result.aiFailed)
        assertEquals(2, result.failuresByKind[AiSmsInterpreter.FailureKind.NETWORK])
        assertEquals(1, result.failuresByKind[AiSmsInterpreter.FailureKind.MALFORMED_REPLY])
    }

    // ------------------------------------------------------ parser robustness

    @Test
    fun aLargeReasoningEnvelopeWithASmallValidContentStillParses() {
        val reasoning = "thinking ".repeat(4_000) // ~36 KB, well over MAX_REPLY_CHARS
        val reply = envelope(expenseProposal, messageExtra = ""","reasoning_content":"$reasoning"""")
        assertTrue("envelope is genuinely large", reply.length > AiResponseParser.MAX_REPLY_CHARS)
        val parsed = AiResponseParser.parse(reply)
        assertTrue("content must still be read", parsed is AiResponseParser.ParseOutcome.Parsed)
        assertEquals(
            "bKash",
            (parsed as AiResponseParser.ParseOutcome.Parsed).proposal.provider
        )
    }

    @Test
    fun arrayStyleContentIsUnderstood() {
        val reply =
            """{"choices":[{"message":{"content":[{"type":"text","text":"{\"is_financial\":true,\"provider\":\"Nagad\",\"transaction_type\":\"income\",\"amount_bdt\":\"500.00\",\"confidence\":0.9}"}]}}]}"""
        val parsed = AiResponseParser.parse(reply)
        assertTrue(parsed is AiResponseParser.ParseOutcome.Parsed)
        assertEquals("Nagad", (parsed as AiResponseParser.ParseOutcome.Parsed).proposal.provider)
    }

    @Test
    fun aBlankOrNullContentStillFailsClosed() {
        assertEquals(
            AiResponseParser.ParseOutcome.Malformed,
            AiResponseParser.parse("""{"choices":[{"message":{"content":null,"reasoning_content":"hmm"}}]}""")
        )
        assertEquals(
            AiResponseParser.ParseOutcome.Malformed,
            AiResponseParser.parse("""{"choices":[{"message":{"content":[]}}]}""")
        )
    }

    // --------------------------------------------------- classification probe

    @Test
    fun classificationProbeReportsSuccessForAValidStructuredResult() {
        val result = AiConnectionProbe.classify(
            FakeTransport(AiTransportResult.Success(envelope(expenseProposal))),
            config
        )
        assertTrue(result is AiConnectionProbe.ClassificationResult.Success)
        val success = result as AiConnectionProbe.ClassificationResult.Success
        assertEquals("PURCHASE", success.txType)
        assertEquals(125_000L, success.amountCents)
        assertTrue(AiConnectionProbe.describe(result).contains("Classification OK"))
    }

    @Test
    fun classificationProbeFlagsAModelThatCannotClassify() {
        val result = AiConnectionProbe.classify(
            FakeTransport(AiTransportResult.Success(envelope("""{"is_financial":false}"""))),
            config
        )
        assertTrue(result is AiConnectionProbe.ClassificationResult.NotFinancial)
        assertTrue(AiConnectionProbe.describe(result).contains("non-financial"))
    }

    @Test
    fun classificationProbeSurfacesAFailureCategory() {
        val result = AiConnectionProbe.classify(FakeTransport(AiTransportResult.Unauthorized), config)
        assertTrue(result is AiConnectionProbe.ClassificationResult.Failed)
        assertEquals(
            AiSmsInterpreter.FailureKind.AUTH,
            (result as AiConnectionProbe.ClassificationResult.Failed).kind
        )
    }

    @Test
    fun classificationProbeSendsOnlyTheFictionalFixtureAndNeverAKey() {
        val transport = FakeTransport(AiTransportResult.Success(envelope(expenseProposal)))
        AiConnectionProbe.classify(transport, config)
        assertEquals(1, transport.callCount)
        // The probe's fixture is the only body; it is fictional by construction.
        assertTrue(AiConnectionProbe.FIXTURE_BODY.contains("SELF-TEST"))
        assertTrue(AiConnectionProbe.FIXTURE_BODY.contains("TESTREF0001"))
    }

    @Test
    fun classificationProbeIsNotConfiguredWithoutAKey() {
        val transport = FakeTransport(AiTransportResult.Success(envelope(expenseProposal)))
        val result = AiConnectionProbe.classify(transport, AiConfig.unconfigured())
        assertTrue(result is AiConnectionProbe.ClassificationResult.NotConfigured)
        assertEquals("nothing sent without a key", 0, transport.callCount)
        assertFalse(AiConnectionProbe.describe(result).contains("nvapi"))
    }

    // Keep the fixture aligned with the SMS fixtures used elsewhere.
    @Test
    fun fictionalFixtureIsIndependentOfRealFixtures() {
        assertFalse(AiConnectionProbe.FIXTURE_BODY == SmsFixtures.BKASH_SENT)
    }
}
