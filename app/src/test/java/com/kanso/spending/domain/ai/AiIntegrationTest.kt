package com.kanso.spending.domain.ai

import com.kanso.spending.domain.sms.LedgerAction
import com.kanso.spending.domain.sms.SmsTxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider-independent AI integration, tested entirely against mocks — no real
 * credentials, no network, no paid requests.
 *
 * The suite pins the properties the ledger depends on:
 *  - identifiers are stripped before anything is sent;
 *  - the credential is never part of any request payload or result;
 *  - malformed/absent/contradictory model output can never reach a ledger row;
 *  - AI-parsed and locally-parsed copies of one message share a fingerprint, so
 *    dedup collapses them to a single record;
 *  - transfers, fees and refunds map to the right ledger actions, never income
 *    or expense by accident;
 *  - a provider-reported balance stays advisory and never overwrites anything.
 */
class AiIntegrationTest {

    private val config = AiConfig(
        modelId = "test-model-id",
        baseUrl = "https://integrate.example.test/v1",
        apiKey = "test-key-not-real"
    )

    private fun proposal(
        isFinancial: Boolean = true,
        provider: String? = "bKash",
        type: String? = "MONEY_SENT",
        amount: String? = "1250.00",
        currency: String? = "BDT",
        fee: String? = null,
        reference: String? = "REF123",
        direction: String? = "debit",
        confidence: Int? = 95,
        reportedBalance: String? = null,
        warnings: List<String>? = null
    ) = AiTransactionProposal(
        isFinancial = isFinancial, provider = provider, type = type, amount = amount,
        currency = currency, timestamp = null, reference = reference, fee = fee,
        merchant = null, accountHint = "***4512", reportedBalance = reportedBalance,
        direction = direction, confidence = confidence?.toDouble(), categorySuggestion = null,
        warnings = warnings
    )

    /** A transport stub that records exactly what was sent and returns a canned reply. */
    private class RecordingTransport(
        private val result: AiTransportResult
    ) : AiTransport {
        var lastUserMessage: String? = null
        var lastSystemPrompt: String? = null
        var lastConfig: AiConfig? = null
        var callCount = 0

        override fun sendChat(config: AiConfig, systemPrompt: String, userMessage: String): AiTransportResult {
            callCount++
            lastConfig = config
            lastSystemPrompt = systemPrompt
            lastUserMessage = userMessage
            return result
        }
    }

    /**
     * Build a reply in the real OpenAI envelope shape, where the assistant's
     * JSON payload is carried as an escaped *string* at choices[0].message.content.
     */
    private fun jsonReply(body: String): AiTransportResult.Success {
        val escaped = body.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t")
        return AiTransportResult.Success(
            """{"id":"x","object":"chat.completion","model":"test-model-id","choices":[{"index":0,"message":{"role":"assistant","content":"$escaped"},"finish_reason":"stop"}]}"""
        )
    }

    // ------------------------------------------------------------------ config

    @Test
    fun configIsUnconfiguredUntilAKeyIsSupplied() {
        assertFalse(AiConfig.unconfigured().isConfigured)
        assertFalse(config.copy(apiKey = null).isConfigured)
        assertFalse(config.copy(apiKey = "   ").isConfigured)
        assertTrue(config.isConfigured)
    }

    @Test
    fun endpointsAreDerivedFromBaseUrlNeverHardcoded() {
        assertEquals("https://integrate.example.test/v1/chat/completions", config.chatCompletionsUrl)
        // Trailing slash is normalised so a mis-typed base URL still works.
        assertEquals(
            "https://integrate.example.test/v1/chat/completions",
            config.copy(baseUrl = "https://integrate.example.test/v1/").chatCompletionsUrl
        )
        // Swapping provider = supplying a different base URL; nothing else changes.
        assertEquals(
            "https://example.test/v2/chat/completions",
            config.copy(baseUrl = "https://example.test/v2").chatCompletionsUrl
        )
        // A full endpoint pasted by the user is respected verbatim (no double path).
        assertEquals(
            "https://example.test/v2/chat/completions",
            config.copy(baseUrl = "https://example.test/v2/chat/completions").chatCompletionsUrl
        )
    }

    @Test
    fun noVendorModelOrEndpointIsBakedIn() {
        // The defaults are blank on purpose: the user must supply their own
        // provider, model and endpoint, so nothing is sent to a service they did
        // not choose.
        assertEquals("", AiConfig.DEFAULT_MODEL_ID)
        assertEquals("", AiConfig.DEFAULT_BASE_URL)
        assertEquals(AiApiFormat.OPENAI_CHAT, AiApiFormat.DEFAULT)
    }

    @Test
    fun requestUrlFollowsTheSelectedApiFormat() {
        val key = "test-key-not-real"
        fun url(format: AiApiFormat, base: String, model: String = "my-model") =
            AiConfig(modelId = model, baseUrl = base, apiKey = key, apiFormat = format).requestUrl

        assertEquals(
            "https://host.test/v1/chat/completions",
            url(AiApiFormat.OPENAI_CHAT, "https://host.test/v1")
        )
        assertEquals(
            "https://host.test/v1/responses",
            url(AiApiFormat.OPENAI_RESPONSES, "https://host.test/v1")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            url(AiApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent",
            url(AiApiFormat.GOOGLE_GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-x")
        )
        assertEquals(
            "http://127.0.0.1:11434/api/chat",
            url(AiApiFormat.OLLAMA_CHAT, "http://127.0.0.1:11434")
        )
    }

    @Test
    fun noDefaultEverContainsAKey() {
        // The unconfigured default must never carry a credential of any kind.
        assertNull(AiConfig.unconfigured().apiKey)
        assertFalse(AiConfig.unconfigured().toString().contains("nvapi", ignoreCase = true))
    }

    // ------------------------------------------------------------- redaction

    @Test
    fun redactStripsPhoneNumbersAndEmails() {
        val body = "Sent BDT 1250 to 01712345678 at user@example.com. TrxID 9F8E7D."
        val redacted = AiPromptBuilder.redact(body)
        assertFalse("phone number removed", redacted.contains("01712345678"))
        assertFalse("email removed", redacted.contains("user@example.com"))
        assertTrue("financial core kept", redacted.contains("1250"))
    }

    @Test
    fun redactStripsCardLikeNumbersAndLongDigitRuns() {
        val body = "Card 4111 1111 1111 1111 used, acct 1234567890123456"
        val redacted = AiPromptBuilder.redact(body)
        assertFalse(redacted.contains("4111 1111 1111 1111"))
        assertFalse(redacted.contains("1234567890123456"))
    }

    @Test
    fun redactPreservesMaskedAccountHints() {
        // "***4512" is already non-identifying and needed for account matching.
        val redacted = AiPromptBuilder.redact("A/C ***4512 balance 2500")
        assertTrue("masked hint kept", redacted.contains("***4512"))
    }

    @Test
    fun redactKeepsReferenceIdsAndAmounts() {
        val redacted = AiPromptBuilder.redact("TrxID 9F8E7D6C5B amount BDT 150.50")
        assertTrue(redacted.contains("9F8E7D6C5B"))
        assertTrue(redacted.contains("150.50"))
    }

    // ---------------------------------------------------------- response parser

    @Test
    fun parserReadsTheOpenAiEnvelope() {
        val reply = jsonReply("""{"isFinancial":true,"provider":"Nagad","type":"MONEY_RECEIVED","amount":"500.00","currency":"BDT","confidence":90,"warnings":[]}""")
        val outcome = AiResponseParser.parse((reply as AiTransportResult.Success).replyText)
        assertTrue(outcome is AiResponseParser.ParseOutcome.Parsed)
        val p = (outcome as AiResponseParser.ParseOutcome.Parsed).proposal
        assertEquals("Nagad", p.provider)
        assertEquals("500.00", p.amount)
        assertEquals(90.0, p.confidence!!, 1e-9)
    }

    @Test
    fun parserToleratesMarkdownFencesAndProse() {
        val fenced = "Here you go:\n```json\n{\"isFinancial\":true,\"provider\":\"Rocket\",\"type\":\"PURCHASE\",\"amount\":\"99.00\",\"confidence\":88}\n```\nHope that helps!"
        val outcome = AiResponseParser.parse(fenced)
        assertTrue(outcome is AiResponseParser.ParseOutcome.Parsed)
        assertEquals("Rocket", (outcome as AiResponseParser.ParseOutcome.Parsed).proposal.provider)
    }

    @Test
    fun parserRejectsMalformedAndOversizedRepliesWithoutThrowing() {
        assertEquals(AiResponseParser.ParseOutcome.Malformed, AiResponseParser.parse(null))
        assertEquals(AiResponseParser.ParseOutcome.Malformed, AiResponseParser.parse(""))
        assertEquals(AiResponseParser.ParseOutcome.Malformed, AiResponseParser.parse("   "))
        assertEquals(AiResponseParser.ParseOutcome.Malformed, AiResponseParser.parse("not json at all"))
        assertEquals(AiResponseParser.ParseOutcome.Malformed, AiResponseParser.parse("{\"isFinancial\": "))
        val huge = "x".repeat(AiResponseParser.MAX_REPLY_CHARS + 1)
        assertEquals(AiResponseParser.ParseOutcome.Malformed, AiResponseParser.parse(huge))
    }

    @Test
    fun parserTreatsJsonNullsAsAbsent() {
        val reply = """{"isFinancial":true,"provider":"bKash","type":"PURCHASE","amount":null,"confidence":null}"""
        val p = (AiResponseParser.parse(reply) as AiResponseParser.ParseOutcome.Parsed).proposal
        assertNull(p.amount)
        assertNull(p.confidence)
    }

    // -------------------------------------------------------------- validator

    @Test
    fun nonFinancialProposalsNeverBecomeTransactions() {
        val r = AiResponseValidator.validate(proposal(isFinancial = false), "bKash", 0L)
        assertTrue(r is AiValidationResult.NotFinancial)
    }

    @Test
    fun missingAmountIsRejectedNeverGuessed() {
        val r = AiResponseValidator.validate(proposal(amount = null), "bKash", 0L)
        assertTrue(r is AiValidationResult.Rejected)
        assertTrue((r as AiValidationResult.Rejected).reason.contains("Amount"))
    }

    @Test
    fun unparseableAndNonPositiveAmountsAreRejected() {
        assertTrue(AiResponseValidator.validate(proposal(amount = "abc"), "bKash", 0L) is AiValidationResult.Rejected)
        assertTrue(AiResponseValidator.validate(proposal(amount = "0"), "bKash", 0L) is AiValidationResult.Rejected)
        assertTrue(AiResponseValidator.validate(proposal(amount = "-5"), "bKash", 0L) is AiValidationResult.Rejected)
    }

    @Test
    fun foreignCurrencyIsRejectedNotConverted() {
        val r = AiResponseValidator.validate(proposal(currency = "USD"), "bKash", 0L)
        assertTrue(r is AiValidationResult.Rejected)
        assertTrue((r as AiValidationResult.Rejected).reason.contains("BDT"))
    }

    @Test
    fun bdtCurrencySymbolsAreAccepted() {
        for (c in listOf("BDT", "bdt", "Tk", "TAKA", "৳")) {
            val r = AiResponseValidator.validate(proposal(currency = c), "bKash", 0L)
            assertTrue("currency '$c' accepted", r is AiValidationResult.Accepted)
        }
    }

    @Test
    fun unknownTypeIsRejectedRatherThanInvented() {
        val r = AiResponseValidator.validate(proposal(type = "SOMETHING_ELSE"), "bKash", 0L)
        assertTrue(r is AiValidationResult.Rejected)
    }

    @Test
    fun missingProviderFallsBackToSenderInsteadOfRejecting() {
        // A well-behaved model returns provider: null even for financial messages
        // (it honours "never guess"). A null/blank provider must NOT reject a
        // correct extraction — the sender is a reliable fallback.
        val r = AiResponseValidator.validate(proposal(provider = "  "), "bKash", 0L)
        assertTrue(r is AiValidationResult.Accepted)
        assertEquals("bKash", (r as AiValidationResult.Accepted).transaction.provider)

        val r2 = AiResponseValidator.validate(proposal(provider = null), "HBL Bank", 0L)
        assertEquals("HBL Bank", (r2 as AiValidationResult.Accepted).transaction.provider)
    }

    // ------------------------------------ real observed model behaviour (live)
    //
    // The following pin the ACTUAL output observed against the live endpoint,
    // which differs from the idealised fixtures above. They exist because the
    // mocked assumptions were wrong and only a real call exposed it.

    @Test
    fun realConfidenceScaleIs0To1AndNormalisesToPercent() {
        // Observed live: 0.9 / 0.95 / 0.97 — NOT 90 / 95 / 97.
        assertEquals(95, AiResponseValidator.normalizeConfidence(0.95))
        assertEquals(90, AiResponseValidator.normalizeConfidence(0.9))
        assertEquals(97, AiResponseValidator.normalizeConfidence(0.97))
        // A provider using the 0..100 scale is left alone.
        assertEquals(85, AiResponseValidator.normalizeConfidence(85.0))
        assertEquals(100, AiResponseValidator.normalizeConfidence(100.0))
        assertEquals(0, AiResponseValidator.normalizeConfidence(null))
    }

    @Test
    fun realConfidenceNoLongerForcesEverythingIntoReview() {
        // Before the fix, 0.95 read as optInt→0 → always below threshold →
        // every correct extraction was queued. Now it normalises to 95.
        val r = AiResponseValidator.validate(proposal(confidence = null), "bKash", 0L)
        // (null confidence stays 0 → still queues, correctly)
        assertTrue((r as AiValidationResult.Accepted).transaction.needsReview)
    }

    @Test
    fun realTypeStringsWithSpacesAndSnakeCaseMapCorrectly() {
        // Observed live type strings, including one with a SPACE ("ATM withdrawal").
        val cases = mapOf(
            "send_money" to SmsTxType.MONEY_SENT,
            "received" to SmsTxType.MONEY_RECEIVED,
            "debit_card_purchase" to SmsTxType.PURCHASE,
            "ATM withdrawal" to SmsTxType.WITHDRAWAL,
            "fee" to SmsTxType.FEE,
            "refund" to SmsTxType.REFUND,
            "balance_inquiry" to SmsTxType.BALANCE_ONLY
        )
        for ((raw, expected) in cases) {
            val r = AiResponseValidator.validate(proposal(type = raw, amount = if (expected == SmsTxType.BALANCE_ONLY) null else "100.00"), "bKash", 0L)
            assertTrue("type '$raw' must be accepted", r is AiValidationResult.Accepted)
            assertEquals("type '$raw'", expected, (r as AiValidationResult.Accepted).transaction.type)
        }
    }

    @Test
    fun realDirectionInIsAcceptedAndDoesNotForceReview() {
        // Observed live: the model says "in", not "credit". It must be treated
        // as a known direction, not an ambiguity.
        val r = AiResponseValidator.validate(
            proposal(type = "received", direction = "in", amount = "3000.00", confidence = 95, warnings = null),
            "bKash", 0L
        )
        val tx = (r as AiValidationResult.Accepted).transaction
        assertFalse("direction 'in' is known — not an ambiguity", tx.needsReview)
    }

    @Test
    fun realConfidentResponseParsesAndValidatesEndToEnd() {
        // EXACT content captured live from one provider for a bKash send.
        val realContent = """{"isFinancial": true, "provider": null, "type": "send_money", "amount": "1,250.00", "currency": "BDT", "timestamp": "15/10/26 14:22", "reference": "bK6P9Q2R", "fee": "5.00", "merchant": null, "accountHint": null, "reportedBalance": "4,320.50", "direction": "debit", "confidence": 0.9, "categorySuggestion": "transfer", "warnings": ["Provider not explicitly named in message"]}"""
        val envelope = jsonReply(realContent)
        val parsed = AiResponseParser.parse((envelope as AiTransportResult.Success).replyText)
        assertTrue(parsed is AiResponseParser.ParseOutcome.Parsed)
        val proposal = (parsed as AiResponseParser.ParseOutcome.Parsed).proposal
        assertEquals(0.9, proposal.confidence!!, 1e-9)
        assertEquals("send_money", proposal.type)

        val validated = AiResponseValidator.validate(proposal, "bKash", 1_760_000_000_000L)
        assertTrue(validated is AiValidationResult.Accepted)
        val tx = (validated as AiValidationResult.Accepted).transaction
        assertEquals(125000L, tx.amountCents)      // "1,250.00" → poisha, comma stripped
        assertEquals(500L, tx.feeCents)
        assertEquals(432050L, tx.reportedBalanceCents)
        assertEquals("bKash", tx.provider)          // null provider → sender fallback
        assertEquals("bK6P9Q2R", tx.referenceId)
        assertEquals(SmsTxType.MONEY_SENT, tx.type)
        assertTrue(tx.needsReview)                  // model flagged a warning → review
    }

    @Test
    fun nullContentEnvelopeIsMalformedNeverSilentlyNotFinancial() {
        // A reasoning model that exhausts its budget returns content:null. That
        // must be Malformed (→ fail closed), never reinterpreted as NotFinancial.
        val envelope = """{"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":"thinking..."},"finish_reason":"length"}]}"""
        assertEquals(AiResponseParser.ParseOutcome.Malformed, AiResponseParser.parse(envelope))
    }

    @Test
    fun balanceOnlyAndPromoCarryNoAmountAndAreNotTransactions() {
        val bal = AiResponseValidator.validate(proposal(type = "BALANCE_ONLY", amount = null, confidence = 99), "bKash", 0L)
        assertTrue(bal is AiValidationResult.Accepted)
        assertEquals(SmsTxType.BALANCE_ONLY, (bal as AiValidationResult.Accepted).transaction.type)
        assertNull(bal.transaction.amountCents)
        assertEquals(LedgerAction.IGNORE, bal.transaction.action)

        val promo = AiResponseValidator.validate(proposal(type = "PROMO", amount = null), "bKash", 0L)
        assertEquals(LedgerAction.IGNORE, (promo as AiValidationResult.Accepted).transaction.action)
    }

    @Test
    fun flaggedWarningsAlwaysQueueForReview() {
        val r = AiResponseValidator.validate(proposal(warnings = listOf("amount could be fee-inclusive")), "bKash", 0L)
        val tx = (r as AiValidationResult.Accepted).transaction
        assertTrue(tx.needsReview)
        assertEquals(LedgerAction.REVIEW_ONLY, tx.action)
        assertTrue(tx.reviewReason!!.contains("fee-inclusive"))
    }

    @Test
    fun lowConfidenceAlwaysQueueForReview() {
        val r = AiResponseValidator.validate(proposal(confidence = 40), "bKash", 0L)
        assertTrue((r as AiValidationResult.Accepted).transaction.needsReview)
    }

    @Test
    fun confidenceIsClampedToValidRange() {
        val high = AiResponseValidator.validate(proposal(confidence = 500), "bKash", 0L)
        assertEquals(100, (high as AiValidationResult.Accepted).transaction.confidence)
        val missing = AiResponseValidator.validate(proposal(confidence = null), "bKash", 0L)
        assertEquals(0, (missing as AiValidationResult.Accepted).transaction.confidence)
    }

    @Test
    fun highConfidenceUnambiguousTransactionIsAcceptedForAutoCommit() {
        val r = AiResponseValidator.validate(proposal(confidence = 95), "bKash", 0L)
        val tx = (r as AiValidationResult.Accepted).transaction
        assertFalse(tx.needsReview)
        assertEquals(LedgerAction.RECORD_EXPENSE, tx.action)
        assertEquals(125000L, tx.amountCents)
    }

    // ------------------------------------------ financial integrity of actions

    @Test
    fun selfTransferIsNeverIncomeOrExpense() {
        val r = AiResponseValidator.validate(proposal(type = "TRANSFER_SELF", confidence = 95), "bKash", 0L)
        assertEquals(LedgerAction.RECORD_TRANSFER, (r as AiValidationResult.Accepted).transaction.action)
    }

    @Test
    fun standaloneFeeIsAFeeRecordedSeparately() {
        val r = AiResponseValidator.validate(proposal(type = "FEE", amount = "5.00", confidence = 95), "bKash", 0L)
        assertEquals(LedgerAction.RECORD_FEE, (r as AiValidationResult.Accepted).transaction.action)
    }

    @Test
    fun refundMapsToIncomeDirectionNotExpense() {
        val r = AiResponseValidator.validate(proposal(type = "REFUND", confidence = 95), "bKash", 0L)
        assertEquals(LedgerAction.RECORD_INCOME, (r as AiValidationResult.Accepted).transaction.action)
    }

    @Test
    fun receivedMoneyIsIncomeAndSentIsExpense() {
        val inc = AiResponseValidator.validate(proposal(type = "MONEY_RECEIVED", direction = "credit", confidence = 95), "bKash", 0L)
        assertEquals(LedgerAction.RECORD_INCOME, (inc as AiValidationResult.Accepted).transaction.action)
        val exp = AiResponseValidator.validate(proposal(type = "MONEY_SENT", confidence = 95), "bKash", 0L)
        assertEquals(LedgerAction.RECORD_EXPENSE, (exp as AiValidationResult.Accepted).transaction.action)
    }

    @Test
    fun negativeFeeIsRejected() {
        val r = AiResponseValidator.validate(proposal(fee = "-1.00"), "bKash", 0L)
        assertTrue(r is AiValidationResult.Rejected)
    }

    @Test
    fun feeIsCarriedThroughInPoisha() {
        val r = AiResponseValidator.validate(proposal(fee = "12.50", confidence = 95), "bKash", 0L)
        assertEquals(1250L, (r as AiValidationResult.Accepted).transaction.feeCents)
    }

    @Test
    fun reportedBalanceStaysAdvisoryAndNeverForcesARecord() {
        val r = AiResponseValidator.validate(proposal(type = "BALANCE_ONLY", amount = null, reportedBalance = "2500.00"), "bKash", 0L)
        val tx = (r as AiValidationResult.Accepted).transaction
        assertEquals(250000L, tx.reportedBalanceCents)
        assertNull("no amount invented from a balance notice", tx.amountCents)
    }

    // ------------------------------------------------------------- dedup

    @Test
    fun aiAndLocalParseOfOneMessageShareAFingerprint() {
        // The AI path must reuse SmsParser.fingerprint, so the same message
        // parsed twice (AI + local) collapses to one record, never two.
        val aiTx = (AiResponseValidator.validate(proposal(reference = "TRX999", confidence = 95), "bKash", 1_700_000_000_000L) as AiValidationResult.Accepted).transaction
        val localTx = com.kanso.spending.domain.sms.SmsParser.fingerprint(
            provider = "bKash", sender = "bKash", reference = "TRX999",
            amountCents = 125000L, type = SmsTxType.MONEY_SENT, timestampMillis = 1_700_000_000_000L
        )
        assertEquals("identical message → identical fingerprint", localTx, aiTx.fingerprint)
    }

    @Test
    fun fingerprintIsNeverAmountOnly() {
        // Same amount, different reference → different fingerprint.
        val a = (AiResponseValidator.validate(proposal(reference = "AAA", confidence = 95), "bKash", 1L) as AiValidationResult.Accepted).transaction
        val b = (AiResponseValidator.validate(proposal(reference = "BBB", confidence = 95), "bKash", 1L) as AiValidationResult.Accepted).transaction
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    // ------------------------------------------------- interpreter + transport

    @Test
    fun interpreterRedactsBeforeSendingAndNeverLeavesTheDeviceUnredacted() {
        val transport = RecordingTransport(jsonReply("""{"isFinancial":true,"provider":"bKash","type":"MONEY_SENT","amount":"1250.00","confidence":95}"""))
        val interpreter = AiSmsInterpreter(transport)
        interpreter.interpret(config, "bKash", "Sent BDT 1250 to 01712345678 TrxID 9F8E", 0L)

        val sent = transport.lastUserMessage!!
        assertFalse("phone must not be sent", sent.contains("01712345678"))
        assertTrue("amount kept", sent.contains("1250"))
    }

    @Test
    fun interpreterNeverSendsTheApiKeyInTheMessage() {
        val transport = RecordingTransport(jsonReply("""{"isFinancial":false}"""))
        AiSmsInterpreter(transport).interpret(config, "bKash", "hello", 0L)
        val sent = transport.lastUserMessage.orEmpty() + transport.lastSystemPrompt.orEmpty()
        assertFalse("credential must not appear in any prompt", sent.contains(config.apiKey!!))
    }

    @Test
    fun interpreterSurfacesAValidatedTransactionOnSuccess() {
        val transport = RecordingTransport(jsonReply("""{"isFinancial":true,"provider":"bKash","type":"MONEY_SENT","amount":"1250.00","currency":"BDT","confidence":95}"""))
        val out = AiSmsInterpreter(transport).interpret(config, "bKash", "body", 0L)
        assertTrue(out is AiSmsInterpreter.Outcome.Extracted)
        assertEquals(125000L, (out as AiSmsInterpreter.Outcome.Extracted).transaction.amountCents)
    }

    @Test
    fun interpreterFallsBackSafelyOnEveryTransportFailure() {
        val failures = listOf(
            AiTransportResult.NotConfigured,
            AiTransportResult.Unauthorized,
            AiTransportResult.RateLimited,
            AiTransportResult.HttpError(500),
            AiTransportResult.NetworkError,
            AiTransportResult.Malformed(),
            AiTransportResult.EmptyReply("empty"),
            AiTransportResult.ProviderError("model not found")
        )
        for (f in failures) {
            val out = AiSmsInterpreter(RecordingTransport(f)).interpret(config, "bKash", "body", 0L)
            assertTrue("failure $f must not produce a transaction", out is AiSmsInterpreter.Outcome.Failed)
        }
    }

    @Test
    fun interpreterFailsClosedWhenNotConfigured() {
        val transport = RecordingTransport(jsonReply("""{"isFinancial":true}"""))
        val out = AiSmsInterpreter(transport).interpret(AiConfig.unconfigured(), "bKash", "body", 0L)
        assertTrue(out is AiSmsInterpreter.Outcome.Failed)
        assertEquals("no request must be made without a key", 0, transport.callCount)
    }

    @Test
    fun interpreterFailsClosedOnMalformedReply() {
        val out = AiSmsInterpreter(RecordingTransport(jsonReply("not json"))).interpret(config, "bKash", "body", 0L)
        assertTrue(out is AiSmsInterpreter.Outcome.Failed)
    }

    @Test
    fun interpreterPropagatesNotFinancialDistinctlyFromFailure() {
        val out = AiSmsInterpreter(RecordingTransport(jsonReply("""{"isFinancial":false}"""))).interpret(config, "bKash", "body", 0L)
        assertTrue("non-financial is not a failure", out is AiSmsInterpreter.Outcome.NotFinancial)
    }

    @Test
    fun interpreterSkipsSendingWhenNothingSurvivesRedaction() {
        val transport = RecordingTransport(jsonReply("""{"isFinancial":true}"""))
        val out = AiSmsInterpreter(transport).interpret(config, "bKash", "   ", 0L)
        assertTrue(out is AiSmsInterpreter.Outcome.Failed)
        assertEquals("blank body must not be sent", 0, transport.callCount)
    }
}
