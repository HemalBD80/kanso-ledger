package com.kanso.spending.domain.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structured-reply suite.
 *
 * The v2.4 integration bug was not just "wrong envelope": even after the
 * envelope was understood, a reply that carried a valid JSON answer could still
 * surface as "non-JSON plain text" — because a hidden reasoning/thinking trace
 * was folded into (or mistaken for) the answer, and because the model was never
 * asked for machine-readable output.
 *
 * These tests pin the real fixes:
 *  - the prompt demands exactly one bare JSON object;
 *  - each documented provider JSON-mode is requested (and is optional);
 *  - the extractor NEVER lets reasoning/thinking text become the answer;
 *  - a single complete proposal object is recovered from fences/prose, while
 *    arbitrary JSON is still refused;
 *  - empty, reasoning-only, refusal, malformed and unsupported replies are
 *    distinguishable and never fabricate a transaction.
 *
 * Every fixture is synthetic; no real key, endpoint or message is used.
 */
class AiStructuredReplyTest {

    private val key = "sk-test-not-a-real-key"
    private val proposal =
        """{"is_financial":true,"provider":"bKash","transaction_type":"expense","amount_bdt":"1250.00","direction":"debit","confidence":0.95}"""

    private fun config(format: AiApiFormat = AiApiFormat.OPENAI_CHAT) =
        AiConfig(modelId = "my-model", baseUrl = "https://host.test/v1", apiKey = key, apiFormat = format)

    private class FakeTransport(private val result: AiTransportResult) : AiTransport {
        override fun sendChat(config: AiConfig, systemPrompt: String, userMessage: String) = result
    }

    private fun interpret(result: AiTransportResult): AiSmsInterpreter.Outcome =
        AiSmsInterpreter(FakeTransport(result))
            .interpret(config(), "bKash", "You have sent BDT 1250.00 TrxID AA11", 0L)

    private fun failedKind(outcome: AiSmsInterpreter.Outcome): AiSmsInterpreter.FailureKind =
        (outcome as AiSmsInterpreter.Outcome.Failed).kind

    private fun extractedText(body: String): String {
        val extraction = AiReplyExtractor.extract(body)
        assertTrue("expected text, got $extraction", extraction is AiReplyExtractor.Extraction.Text)
        return (extraction as AiReplyExtractor.Extraction.Text).text
    }

    // ------------------------------------------------------- prompt contract

    @Test
    fun thePromptDemandsExactlyOneBareJsonObject() {
        val prompt = AiPromptBuilder.SYSTEM_PROMPT.lowercase()
        assertTrue(prompt.contains("exactly one json object"))
        assertTrue(prompt.contains("markdown"))
        assertTrue("must forbid prose", prompt.contains("do not return prose"))
        assertTrue(AiPromptBuilder.buildUserMessage("x").lowercase().contains("no markdown fences"))
    }

    // ---------------------------------------------------------- JSON mode

    @Test
    fun openAiChatRequestsJsonModeAndCanOmitIt() {
        val cfg = config(AiApiFormat.OPENAI_CHAT)
        val withMode = JSONObject(cfg.apiFormat.buildRequest(cfg, "S", "U").body)
        assertEquals("json_object", withMode.getJSONObject("response_format").getString("type"))
        val without = JSONObject(cfg.apiFormat.buildRequest(cfg, "S", "U", jsonMode = false).body)
        assertFalse(without.has("response_format"))
    }

    @Test
    fun openAiResponsesRequestsJsonMode() {
        val cfg = config(AiApiFormat.OPENAI_RESPONSES)
        val body = JSONObject(cfg.apiFormat.buildRequest(cfg, "S", "U").body)
        assertEquals("json_object", body.getJSONObject("text").getJSONObject("format").getString("type"))
    }

    @Test
    fun geminiRequestsJsonMode() {
        val cfg = config(AiApiFormat.GOOGLE_GEMINI)
        val body = JSONObject(cfg.apiFormat.buildRequest(cfg, "S", "U").body)
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
    }

    @Test
    fun ollamaRequestsJsonMode() {
        val cfg = config(AiApiFormat.OLLAMA_CHAT)
        val body = JSONObject(cfg.apiFormat.buildRequest(cfg, "S", "U").body)
        assertEquals("json", body.getString("format"))
    }

    @Test
    fun anthropicHasNoNativeJsonModeAndIsNotAssumedTo() {
        val cfg = config(AiApiFormat.ANTHROPIC_MESSAGES)
        assertFalse(AiApiFormat.ANTHROPIC_MESSAGES.supportsJsonMode)
        val body = JSONObject(cfg.apiFormat.buildRequest(cfg, "S", "U").body)
        assertFalse(body.has("response_format"))
        assertFalse(body.has("text"))
    }

    // ------------------------------------------------ safe JSON recovery

    @Test
    fun bareJsonObjectIsParsed() {
        assertTrue(AiResponseParser.parse(proposal) is AiResponseParser.ParseOutcome.Parsed)
    }

    @Test
    fun markdownFencedJsonIsParsed() {
        val reply = "```json\n$proposal\n```"
        assertTrue(AiResponseParser.parse(reply) is AiResponseParser.ParseOutcome.Parsed)
    }

    @Test
    fun explanatoryTextSurroundingJsonIsParsed() {
        val reply = "Sure — here is the extracted transaction:\n$proposal\nLet me know if you need more."
        val parsed = AiResponseParser.parse(reply)
        assertTrue(parsed is AiResponseParser.ParseOutcome.Parsed)
        assertEquals("bKash", (parsed as AiResponseParser.ParseOutcome.Parsed).proposal.provider)
    }

    @Test
    fun aLeadingJunkObjectIsSkippedInFavourOfTheProposal() {
        // The narrow extractor must not stop at the first balanced `{...}`.
        val reply = """{"status":"ok","request_id":"abc"} and then the result: $proposal"""
        val parsed = AiResponseParser.parse(reply)
        assertTrue(parsed is AiResponseParser.ParseOutcome.Parsed)
        assertEquals("1250.00", (parsed as AiResponseParser.ParseOutcome.Parsed).proposal.amount)
    }

    @Test
    fun arbitraryJsonWithoutContractKeysIsRefused() {
        // A status/error/blob object is never a classification.
        assertEquals(
            AiResponseParser.ParseOutcome.Malformed,
            AiResponseParser.parse("""{"status":"ok","value":3}""")
        )
    }

    @Test
    fun proseWithNoJsonIsMalformed() {
        assertEquals(
            AiResponseParser.ParseOutcome.Malformed,
            AiResponseParser.parse("The message appears to be a purchase, but I am unsure.")
        )
    }

    // ------------------------------------------- reasoning must not leak

    @Test
    fun reasoningOnlyOpenAiChatReplyIsEmptyNotTheTrace() {
        val body = """{"choices":[{"message":{"content":"","reasoning_content":"the user sent 1250 taka so is_financial is true"}}]}"""
        val extraction = AiReplyExtractor.extract(body)
        assertTrue(extraction is AiReplyExtractor.Extraction.Empty)
        assertEquals(
            AiReplyExtractor.REASONING_ONLY_REASON,
            (extraction as AiReplyExtractor.Extraction.Empty).reason
        )
    }

    @Test
    fun reasoningOnlyReplyIsReportedAsEmptyReplyNotMalformed() {
        val out = interpret(AiTransportResult.EmptyReply(AiReplyExtractor.REASONING_ONLY_REASON))
        assertEquals(AiSmsInterpreter.FailureKind.EMPTY_REPLY, failedKind(out))
    }

    @Test
    fun openAiResponsesAnswerIsUsedAndTheReasoningItemIsNot() {
        val body = JSONObject().put(
            "output",
            JSONArray()
                .put(
                    JSONObject().put("type", "reasoning").put(
                        "content",
                        JSONArray().put(
                            JSONObject().put("type", "reasoning_text")
                                .put("text", "reasoning: I think is_financial is true and amount 9999")
                        )
                    )
                )
                .put(
                    JSONObject().put("type", "message").put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "output_text").put("text", proposal))
                    )
                )
        ).toString()
        val text = extractedText(body)
        assertTrue("the answer must be the message item", text.contains("\"amount_bdt\":\"1250.00\""))
        assertFalse("the reasoning trace must be absent", text.contains("9999"))
    }

    @Test
    fun anthropicThinkingBlockIsExcludedFromTheAnswer() {
        val body = JSONObject().put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "hidden chain mentioning 9999"))
                .put(JSONObject().put("type", "text").put("text", proposal))
        ).toString()
        val text = extractedText(body)
        assertTrue(text.contains("\"amount_bdt\":\"1250.00\""))
        assertFalse(text.contains("9999"))
    }

    @Test
    fun geminiThoughtPartIsExcludedFromTheAnswer() {
        val body = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("thought", true).put("text", "hidden 9999"))
                            .put(JSONObject().put("text", proposal))
                    )
                )
            )
        ).toString()
        val text = extractedText(body)
        assertTrue(text.contains("\"amount_bdt\":\"1250.00\""))
        assertFalse(text.contains("9999"))
    }

    // ------------------------------------------------------- failure kinds

    @Test
    fun aRefusalIsItsOwnCategoryAndItsNotMalformed() {
        val out = interpret(AiTransportResult.Success("I'm sorry, but I can't help with that."))
        assertEquals(AiSmsInterpreter.FailureKind.REFUSAL, failedKind(out))
    }

    @Test
    fun describeShapeRecognisesARefusal() {
        assertTrue(AiReplyExtractor.looksLikeRefusal("I cannot assist with this request."))
        assertTrue(AiReplyExtractor.describeShape("I'm sorry, but I am unable to help.").contains("refusal"))
        assertFalse("a normal answer is not a refusal", AiReplyExtractor.looksLikeRefusal(proposal))
    }

    @Test
    fun emptyReplyIsTheEmptyCategory() {
        assertEquals(
            AiSmsInterpreter.FailureKind.EMPTY_REPLY,
            failedKind(interpret(AiTransportResult.EmptyReply("token budget exhausted")))
        )
    }

    @Test
    fun malformedJsonIsTheMalformedCategory() {
        assertEquals(
            AiSmsInterpreter.FailureKind.MALFORMED_REPLY,
            failedKind(interpret(AiTransportResult.Success("{ this is not json")))
        )
    }

    @Test
    fun anUnsupportedResponseFormatIsMalformedNotASuccess() {
        // A JSON body with no known message field must not be read as a reply.
        assertEquals(
            AiSmsInterpreter.FailureKind.MALFORMED_REPLY,
            failedKind(interpret(AiTransportResult.Success("""{"unexpected":"shape"}""")))
        )
    }

    // ------------------------------------------------------- happy path

    @Test
    fun markdownWrappedValidProposalStillReachesAValidatedTransaction() {
        val out = interpret(AiTransportResult.Success("```json\n$proposal\n```"))
        assertTrue("expected a validated extraction, got $out", out is AiSmsInterpreter.Outcome.Extracted)
    }

    @Test
    fun proseSurroundedValidProposalStillReachesAValidatedTransaction() {
        val out = interpret(
            AiTransportResult.Success("Here you go:\n$proposal\nDone.")
        )
        assertTrue(out is AiSmsInterpreter.Outcome.Extracted)
    }
}
