package com.kanso.spending.domain.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider-independence suite.
 *
 * These tests exist because the original integration assumed exactly one
 * provider's wire format and reported every other reply as `MALFORMED_REPLY`.
 * Here we pin the two halves of the fix:
 *
 *  - [AiApiFormat] builds a correct request (URL, auth header, body) for each
 *    supported format, and never smuggles the key into the body;
 *  - [AiReplyExtractor] reads the assistant's answer out of every common
 *    response envelope — OpenAI chat (string/array/object content), OpenAI
 *    legacy text completions, OpenAI Responses, Anthropic, Gemini, Ollama,
 *    a bare proposal object, a deeply nested proposal, and SSE — while
 *    distinguishing empty, non-JSON and genuinely unrecognised bodies.
 *
 * All fixtures are synthetic and built with [JSONObject]/[JSONArray] so the
 * escaping is exactly what a real provider would send; no real key, endpoint or
 * message is used.
 */
class AiProviderIndependenceTest {

    private val key = "sk-test-not-a-real-key"

    private fun config(
        format: AiApiFormat,
        model: String = "my-model",
        base: String = "https://host.test/v1"
    ) = AiConfig(modelId = model, baseUrl = base, apiKey = key, apiFormat = format)

    private val proposalJson =
        """{"is_financial":true,"provider":"bKash","transaction_type":"expense","amount_bdt":"1250.00","direction":"debit","confidence":0.95}"""

    private fun obj(json: String) = JSONObject(json)

    /** Assert the extracted text is the proposal, independent of key order. */
    private fun assertIsProposal(text: String?) {
        val parsed = obj(text!!)
        assertEquals("bKash", parsed.getString("provider"))
        assertEquals("1250.00", parsed.getString("amount_bdt"))
        assertEquals(true, parsed.getBoolean("is_financial"))
    }

    private class FakeTransport(private val result: AiTransportResult) : AiTransport {
        override fun sendChat(config: AiConfig, systemPrompt: String, userMessage: String) = result
    }

    private fun interpret(result: AiTransportResult): AiSmsInterpreter.Outcome =
        AiSmsInterpreter(FakeTransport(result))
            .interpret(config(AiApiFormat.OPENAI_CHAT), "bKash", "You have sent BDT 1250.00 TrxID AA11", 0L)

    // ------------------------------------------------------- request building

    @Test
    fun openAiChatRequestIsWellFormed() {
        val cfg = config(AiApiFormat.OPENAI_CHAT)
        val req = cfg.apiFormat.buildRequest(cfg, "SYS", "USER")
        assertEquals("https://host.test/v1/chat/completions", req.url)
        assertEquals("Bearer $key", req.headers["Authorization"])
        val body = obj(req.body)
        assertEquals("my-model", body.getString("model"))
        assertEquals("SYS", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("USER", body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun anthropicRequestUsesXApiKeyAndTopLevelSystem() {
        val cfg = config(AiApiFormat.ANTHROPIC_MESSAGES, base = "https://api.anthropic.com/v1")
        val req = cfg.apiFormat.buildRequest(cfg, "SYS", "USER")
        assertEquals("https://api.anthropic.com/v1/messages", req.url)
        assertEquals(key, req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertNull(req.headers["Authorization"])
        val body = obj(req.body)
        assertEquals("SYS", body.getString("system"))
        assertEquals("USER", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun geminiRequestUsesApiKeyHeaderAndModelPath() {
        val cfg = config(
            AiApiFormat.GOOGLE_GEMINI,
            model = "gemini-x",
            base = "https://generativelanguage.googleapis.com/v1beta"
        )
        val req = cfg.apiFormat.buildRequest(cfg, "SYS", "USER")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent", req.url)
        assertEquals(key, req.headers["x-goog-api-key"])
        val body = obj(req.body)
        assertEquals(
            "USER",
            body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        )
    }

    @Test
    fun ollamaRequestUsesNativeChatPath() {
        val cfg = config(AiApiFormat.OLLAMA_CHAT, base = "http://127.0.0.1:11434")
        val req = cfg.apiFormat.buildRequest(cfg, "SYS", "USER")
        assertEquals("http://127.0.0.1:11434/api/chat", req.url)
        assertFalse(obj(req.body).getBoolean("stream"))
    }

    @Test
    fun responsesRequestUsesInstructionsAndInput() {
        val cfg = config(AiApiFormat.OPENAI_RESPONSES)
        val req = cfg.apiFormat.buildRequest(cfg, "SYS", "USER")
        assertEquals("https://host.test/v1/responses", req.url)
        val body = obj(req.body)
        assertEquals("SYS", body.getString("instructions"))
        assertEquals("USER", body.getString("input"))
    }

    @Test
    fun noRequestEverPutsTheKeyInTheBody() {
        for (format in AiApiFormat.ALL) {
            val cfg = config(format)
            val req = cfg.apiFormat.buildRequest(cfg, "SYS", "USER")
            assertFalse("$format leaked the key into the body", req.body.contains(key))
            assertTrue("$format must carry the key in a header", req.headers.values.any { it.contains(key) })
        }
    }

    @Test
    fun unknownFormatIdFallsBackToTheDefault() {
        assertEquals(AiApiFormat.OPENAI_CHAT, AiApiFormat.fromId("does-not-exist"))
        assertEquals(AiApiFormat.OPENAI_CHAT, AiApiFormat.fromId(null))
        assertEquals(AiApiFormat.ANTHROPIC_MESSAGES, AiApiFormat.fromId("anthropic_messages"))
    }

    // ----------------------------------------------------- response extraction

    @Test
    fun extractReadsOpenAiChatStringContent() {
        val body = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put("message", JSONObject().put("role", "assistant").put("content", proposalJson))
                    .put("finish_reason", "stop")
            )
        ).toString()
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsOpenAiChatArrayContent() {
        val body = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", proposalJson))
                    )
                )
            )
        ).toString()
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsOpenAiChatObjectContent() {
        val body = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("message", JSONObject().put("content", JSONObject(proposalJson))))
        ).toString()
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsOpenAiLegacyTextCompletion() {
        val body = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("text", proposalJson))
        ).toString()
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsAnthropicContentBlocks() {
        val body = JSONObject().put("id", "m").put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", proposalJson))
        ).put("stop_reason", "end_turn").toString()
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsGeminiCandidates() {
        val body = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", proposalJson))
                    ).put("role", "model")
                ).put("finishReason", "STOP")
            )
        ).toString()
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsOllamaMessageContent() {
        val body = JSONObject().put("model", "my-model").put(
            "message",
            JSONObject().put("role", "assistant").put("content", proposalJson)
        ).put("done", true).toString()
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsOpenAiResponsesOutputText() {
        val a = AiReplyExtractor.extract(JSONObject().put("output_text", proposalJson).toString())
        assertIsProposal((a as AiReplyExtractor.Extraction.Text).text)

        val b = AiReplyExtractor.extract(
            JSONObject().put(
                "output",
                JSONArray().put(
                    JSONObject().put("type", "message").put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "output_text").put("text", proposalJson))
                    )
                )
            ).toString()
        )
        assertIsProposal((b as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsTheBodyThatIsItselfAProposal() {
        assertIsProposal((AiReplyExtractor.extract(proposalJson) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractDeepScansForANestedProposal() {
        val body = JSONObject().put(
            "result",
            JSONObject().put(
                "data",
                JSONObject().put("payload", JSONObject().put("answer", proposalJson))
            )
        ).toString()
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractReadsAnSseStreamPayload() {
        val payload = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("message", JSONObject().put("content", proposalJson)))
        ).toString()
        val body = "event: message\ndata: $payload\n\ndata: [DONE]\n"
        assertIsProposal((AiReplyExtractor.extract(body) as AiReplyExtractor.Extraction.Text).text)
    }

    @Test
    fun extractDistinguishesEmptyFromUnknownFromPlainText() {
        assertEquals(AiReplyExtractor.Extraction.Empty, AiReplyExtractor.extract(null))
        assertEquals(AiReplyExtractor.Extraction.Empty, AiReplyExtractor.extract("   "))
        // A recognised envelope with a null/empty message is EMPTY, not unknown.
        assertEquals(
            AiReplyExtractor.Extraction.Empty,
            AiReplyExtractor.extract("""{"choices":[{"message":{"content":null}}]}""")
        )
        assertEquals(
            AiReplyExtractor.Extraction.Empty,
            AiReplyExtractor.extract("""{"choices":[{"message":{"content":[]}}]}""")
        )
        // A JSON body with no known field is "unrecognised", not "malformed text".
        assertEquals(
            AiReplyExtractor.Extraction.Unrecognised,
            AiReplyExtractor.extract("""{"some":"unexpected","shape":true}""")
        )
        // Plain, non-JSON prose is reported as such (still usable as a reply).
        assertEquals(AiReplyExtractor.Extraction.NotJson, AiReplyExtractor.extract("OK"))
    }

    @Test
    fun providerErrorIsDetectedInAnErrorEnvelope() {
        assertEquals("model not found", AiReplyExtractor.providerError("""{"error":{"message":"model not found"}}"""))
        assertEquals("bad key", AiReplyExtractor.providerError("""{"error":"bad key"}"""))
        assertNull(AiReplyExtractor.providerError("""{"choices":[{"message":{"content":"hi"}}]}"""))
        assertNull(AiReplyExtractor.providerError(null))
    }

    // ---------------------------------------- extractor → parser → validator

    /** For each provider's envelope, the full chain must reach a valid transaction. */
    @Test
    fun everyProviderEnvelopeReachesAValidatedTransaction() {
        val envelopes = listOf(
            JSONObject().put(
                "choices",
                JSONArray().put(JSONObject().put("message", JSONObject().put("content", proposalJson)))
            ).toString(),
            JSONObject().put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", proposalJson))
            ).toString(),
            JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", proposalJson)))
                    )
                )
            ).toString(),
            JSONObject().put("message", JSONObject().put("content", proposalJson)).toString(),
            JSONObject().put("output_text", proposalJson).toString()
        )
        for (envelope in envelopes) {
            val extracted = (AiReplyExtractor.extract(envelope) as AiReplyExtractor.Extraction.Text).text
            val outcome = interpret(AiTransportResult.Success(extracted))
            assertTrue("envelope should yield a transaction: $envelope", outcome is AiSmsInterpreter.Outcome.Extracted)
            assertEquals(
                125_000L,
                (outcome as AiSmsInterpreter.Outcome.Extracted).transaction.amountCents
            )
        }
    }

    // --------------------------------------------------- failure diagnostics

    @Test
    fun anUnknownEnvelopeBecomesMalformedNeverACrash() {
        val outcome = interpret(AiTransportResult.Malformed("unrecognised shape"))
        assertTrue(outcome is AiSmsInterpreter.Outcome.Failed)
        assertEquals(
            AiSmsInterpreter.FailureKind.MALFORMED_REPLY,
            (outcome as AiSmsInterpreter.Outcome.Failed).kind
        )
    }

    @Test
    fun anEmptyCompletionIsItsOwnCategory() {
        val outcome = interpret(AiTransportResult.EmptyReply("token limit"))
        assertEquals(
            AiSmsInterpreter.FailureKind.EMPTY_REPLY,
            (outcome as AiSmsInterpreter.Outcome.Failed).kind
        )
    }

    @Test
    fun failureReasonsNeverContainTheCredential() {
        // Even a hostile transport that echoes the key back must not leak it.
        val outcome = AiSmsInterpreter(FakeTransport(AiTransportResult.ProviderError("bad key $key")))
            .interpret(config(AiApiFormat.OPENAI_CHAT), "bKash", "You have sent BDT 100.00 TrxID AA11", 0L)
        val reason = (outcome as AiSmsInterpreter.Outcome.Failed).reason
        assertFalse("reason leaked the key: $reason", reason.contains(key))
        assertTrue(reason.contains("***"))
    }

    @Test
    fun providerErrorNamingTheModelIsAnInvalidModel() {
        val outcome = interpret(AiTransportResult.ProviderError("The model `foo` does not exist"))
        assertEquals(
            AiSmsInterpreter.FailureKind.INVALID_MODEL,
            (outcome as AiSmsInterpreter.Outcome.Failed).kind
        )
    }
}
