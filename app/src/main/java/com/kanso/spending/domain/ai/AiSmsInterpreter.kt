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
        /** Any other non-2xx HTTP status — e.g. a bad model ID or endpoint. */
        PROVIDER_HTTP,
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
        FailureKind.PROVIDER_HTTP -> "the provider returned an error (check model ID/endpoint)"
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

    fun interpret(config: AiConfig, sender: String, body: String, receivedAtMillis: Long): Outcome {
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
                "The AI credential was rejected. Check your API key.",
                FailureKind.AUTH
            )
            AiTransportResult.RateLimited -> return Outcome.Failed(
                "The AI service is rate-limiting requests. Try again shortly.",
                FailureKind.RATE_LIMITED
            )
            is AiTransportResult.HttpError -> return Outcome.Failed(
                "The AI service returned an error (HTTP ${result.code})." +
                    if (result.code == 400 || result.code == 404 || result.code == 422) {
                        " The model ID may be invalid for this provider."
                    } else {
                        ""
                    },
                FailureKind.PROVIDER_HTTP
            )
            AiTransportResult.NetworkError -> return Outcome.Failed(
                "Could not reach the AI service. Check your connection.",
                FailureKind.NETWORK
            )
            AiTransportResult.Malformed -> return Outcome.Failed(
                "The AI service returned an unusable reply.",
                FailureKind.MALFORMED_REPLY
            )
        }

        return when (val parsed = AiResponseParser.parse(replyText)) {
            AiResponseParser.ParseOutcome.Malformed ->
                Outcome.Failed("The AI reply could not be parsed.", FailureKind.MALFORMED_REPLY)
            is AiResponseParser.ParseOutcome.Parsed ->
                when (val validated = AiResponseValidator.validate(parsed.proposal, sender, receivedAtMillis)) {
                    is AiValidationResult.Accepted -> Outcome.Extracted(validated.transaction)
                    is AiValidationResult.NotFinancial -> Outcome.NotFinancial(validated.reason)
                    is AiValidationResult.Rejected ->
                        Outcome.Failed(validated.reason, FailureKind.VALIDATION_REJECTED)
                }
        }
    }
}
