package com.kanso.spending.domain.ai

import com.kanso.spending.domain.sms.ParsedSmsTransaction

/**
 * Orchestrates one message through the AI path: redact → transport → parse →
 * validate. Pure and provider-agnostic — the [AiTransport] is injected, so the
 * whole pipeline is testable with a fake and never touches the network in tests.
 *
 * Guarantees:
 *  - identifiers are stripped *before* any text is sent ([AiPromptBuilder.redact]);
 *  - a transport, parse, or validation failure degrades to [Outcome.Failed] — the
 *    caller falls back to local parsing, and nothing crashes or is guessed;
 *  - the only way to reach a [ParsedSmsTransaction] is through
 *    [AiResponseValidator], so the model can never bypass the ledger's rules.
 */
class AiSmsInterpreter(private val transport: AiTransport) {

    /**
     * Why an AI consultation failed, as a bounded, credential-free category.
     * Callers aggregate these into import diagnostics so a user can tell a
     * connectivity problem from a bad model ID, a malformed reply, or a
     * classification the validator refused.
     */
    enum class FailureKind {
        /** No key/base/model configured. */
        NOT_CONFIGURED,
        /** Nothing survived identifier redaction — nothing was sent. */
        EMPTY_AFTER_REDACTION,
        /** Could not reach the endpoint (timeout, DNS, socket). */
        NETWORK,
        /** The provider rejected the credential (401/403). */
        AUTH,
        /** The provider throttled the request (429). */
        RATE_LIMITED,
        /** The provider rejected the request and the model id looks wrong. */
        INVALID_MODEL,
        /** The endpoint/path/format was not found (404/405) — a config, not a model, problem. */
        UNSUPPORTED_ENDPOINT,
        /** Any other non-2xx HTTP status. */
        PROVIDER_HTTP,
        /** A 2xx reply whose message field was empty (e.g. token budget exhausted). */
        EMPTY_REPLY,
        /** A reply arrived but was not a usable completion / not parseable JSON. */
        MALFORMED_REPLY,
        /** The model's proposal was parsed but the ledger's validator refused it. */
        VALIDATION_REJECTED,
        /** Anything else (e.g. the consultation threw). */
        OTHER
    }

    /** Short, human-readable label for a failure category (no secrets). */
    fun describeFailure(kind: FailureKind): String = when (kind) {
        FailureKind.NOT_CONFIGURED -> "AI is not configured"
        FailureKind.EMPTY_AFTER_REDACTION -> "nothing left to send after redaction"
        FailureKind.NETWORK -> "could not reach the provider"
        FailureKind.AUTH -> "the provider rejected the API key"
        FailureKind.RATE_LIMITED -> "the provider rate-limited the request"
        FailureKind.INVALID_MODEL -> "the model ID looks invalid for this provider"
        FailureKind.UNSUPPORTED_ENDPOINT -> "the endpoint was not found (check the base URL / API format)"
        FailureKind.PROVIDER_HTTP -> "the provider returned an error"
        FailureKind.EMPTY_REPLY -> "the provider returned an empty answer"
        FailureKind.MALFORMED_REPLY -> "the provider reply was unusable"
        FailureKind.VALIDATION_REJECTED -> "the classification failed validation"
        FailureKind.OTHER -> "the AI call failed"
    }

    sealed class Outcome {
        /** A validated transaction, ready for the existing ingest planner. */
        data class Extracted(val transaction: ParsedSmsTransaction) : Outcome()

        /** The model said this is not financial — nothing to record. */
        data class NotFinancial(val reason: String) : Outcome()

        /**
         * The AI path could not produce a trustworthy result. The caller MUST
         * fall back to local parsing; this is never a silent success.
         */
        data class Failed(
            val reason: String,
            val kind: FailureKind = FailureKind.OTHER
        ) : Outcome()
    }

    fun interpret(config: AiConfig, sender: String, body: String, receivedAtMillis: Long): Outcome =
        scrubSecrets(interpretInternal(config, sender, body, receivedAtMillis), config.apiKey)

    /**
     * Belt-and-braces privacy guard: even if a transport surfaced a provider
     * message that echoed the credential, the reason returned to the ledger can
     * never contain it. The transport already sanitises; this makes the
     * guarantee independent of any transport implementation.
     */
    private fun scrubSecrets(outcome: Outcome, apiKey: String?): Outcome {
        if (apiKey.isNullOrBlank()) return outcome
        return when (outcome) {
            is Outcome.Failed -> outcome.copy(reason = outcome.reason.replace(apiKey, "***"))
            is Outcome.NotFinancial -> outcome.copy(reason = outcome.reason.replace(apiKey, "***"))
            else -> outcome
        }
    }

    private fun interpretInternal(
        config: AiConfig,
        sender: String,
        body: String,
        receivedAtMillis: Long
    ): Outcome {
        if (!config.isConfigured) {
            return Outcome.Failed("AI is not configured.", FailureKind.NOT_CONFIGURED)
        }

        val redacted = AiPromptBuilder.redact(body)
        if (redacted.isBlank()) {
            return Outcome.Failed(
                "Nothing left to send after removing identifiers.",
                FailureKind.EMPTY_AFTER_REDACTION
            )
        }

        val result = transport.sendChat(
            config = config,
            systemPrompt = AiPromptBuilder.SYSTEM_PROMPT,
            userMessage = AiPromptBuilder.buildUserMessage(redacted)
        )

        val replyText = when (result) {
            is AiTransportResult.Success -> result.replyText
            AiTransportResult.NotConfigured ->
                return Outcome.Failed("AI is not configured.", FailureKind.NOT_CONFIGURED)
            AiTransportResult.Unauthorized -> return Outcome.Failed(
                "The provider rejected the API key (401/403). Check the key.",
                FailureKind.AUTH
            )
            AiTransportResult.RateLimited -> return Outcome.Failed(
                "The provider is rate-limiting requests (429). Try again shortly.",
                FailureKind.RATE_LIMITED
            )
            AiTransportResult.NetworkError -> return Outcome.Failed(
                "Could not reach the provider. Check the base URL and your connection.",
                FailureKind.NETWORK
            )
            is AiTransportResult.EmptyReply -> return Outcome.Failed(
                "The provider returned no answer" + reasonSuffix(result.reason) + ".",
                FailureKind.EMPTY_REPLY
            )
            is AiTransportResult.ProviderError -> return providerErrorOutcome(result.message)
            is AiTransportResult.HttpError -> return httpOutcome(result.code, result.providerMessage)
            is AiTransportResult.Malformed -> return Outcome.Failed(
                "The provider reply was unusable" + reasonSuffix(result.reason) + ".",
                FailureKind.MALFORMED_REPLY
            )
        }

        return when (val parsed = AiResponseParser.parse(replyText)) {
            AiResponseParser.ParseOutcome.Malformed ->
                Outcome.Failed(
                    "The AI reply was not the required JSON object (" +
                        AiReplyExtractor.describeShape(replyText) + ").",
                    FailureKind.MALFORMED_REPLY
                )
            is AiResponseParser.ParseOutcome.Parsed ->
                when (val validated = AiResponseValidator.validate(parsed.proposal, sender, receivedAtMillis)) {
                    is AiValidationResult.Accepted -> Outcome.Extracted(validated.transaction)
                    is AiValidationResult.NotFinancial -> Outcome.NotFinancial(validated.reason)
                    is AiValidationResult.Rejected ->
                        Outcome.Failed(validated.reason, FailureKind.VALIDATION_REJECTED)
                }
        }
    }

    /** A short, sanitised "(…)" clause for a failure reason, or "" when absent. */
    private fun reasonSuffix(reason: String?): String =
        reason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    private fun providerErrorOutcome(message: String?): Outcome.Failed {
        val suffix = message?.takeIf { it.isNotBlank() }?.let { " Provider said: $it" }.orEmpty()
        return if (mentionsModel(message)) {
            Outcome.Failed("The provider reported a model error.$suffix", FailureKind.INVALID_MODEL)
        } else {
            Outcome.Failed("The provider reported an error.$suffix", FailureKind.PROVIDER_HTTP)
        }
    }

    /**
     * Map a non-2xx status to a precise category. A 404/405 is normally a wrong
     * endpoint or API format; a 400/404/422 that names the model is a model-id
     * problem. The distinction matters because the fix is different for each.
     */
    private fun httpOutcome(code: Int, message: String?): Outcome.Failed {
        val suffix = message?.takeIf { it.isNotBlank() }?.let { " Provider said: $it" }.orEmpty()
        return when {
            (code == 404 || code == 405) && mentionsModel(message) -> Outcome.Failed(
                "The provider rejected the request (HTTP $code) — the model ID looks invalid.$suffix",
                FailureKind.INVALID_MODEL
            )
            code == 404 || code == 405 -> Outcome.Failed(
                "The endpoint was not found (HTTP $code) — check the base URL and the API format.$suffix",
                FailureKind.UNSUPPORTED_ENDPOINT
            )
            (code == 400 || code == 422) && mentionsModel(message) -> Outcome.Failed(
                "The provider rejected the request (HTTP $code) — the model ID looks invalid.$suffix",
                FailureKind.INVALID_MODEL
            )
            else -> Outcome.Failed(
                "The provider returned an error (HTTP $code).$suffix",
                FailureKind.PROVIDER_HTTP
            )
        }
    }

    private fun mentionsModel(message: String?): Boolean =
        message?.lowercase()?.contains("model") == true
}
