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

    sealed class Outcome {
        /** A validated transaction, ready for the existing ingest planner. */
        data class Extracted(val transaction: ParsedSmsTransaction) : Outcome()

        /** The model said this is not financial — nothing to record. */
        data class NotFinancial(val reason: String) : Outcome()

        /**
         * The AI path could not produce a trustworthy result. The caller MUST
         * fall back to local parsing; this is never a silent success.
         */
        data class Failed(val reason: String) : Outcome()
    }

    fun interpret(config: AiConfig, sender: String, body: String, receivedAtMillis: Long): Outcome {
        if (!config.isConfigured) {
            return Outcome.Failed("AI is not configured.")
        }

        val redacted = AiPromptBuilder.redact(body)
        if (redacted.isBlank()) {
            return Outcome.Failed("Nothing left to send after removing identifiers.")
        }

        val result = transport.sendChat(
            config = config,
            systemPrompt = AiPromptBuilder.SYSTEM_PROMPT,
            userMessage = AiPromptBuilder.buildUserMessage(redacted)
        )

        val replyText = when (result) {
            is AiTransportResult.Success -> result.replyText
            AiTransportResult.NotConfigured -> return Outcome.Failed("AI is not configured.")
            AiTransportResult.Unauthorized -> return Outcome.Failed("The AI credential was rejected. Check your API key.")
            AiTransportResult.RateLimited -> return Outcome.Failed("The AI service is rate-limiting requests. Try again shortly.")
            is AiTransportResult.HttpError -> return Outcome.Failed("The AI service returned an error (HTTP ${result.code}).")
            AiTransportResult.NetworkError -> return Outcome.Failed("Could not reach the AI service. Check your connection.")
            AiTransportResult.Malformed -> return Outcome.Failed("The AI service returned an unusable reply.")
        }

        return when (val parsed = AiResponseParser.parse(replyText)) {
            AiResponseParser.ParseOutcome.Malformed ->
                Outcome.Failed("The AI reply could not be parsed.")
            is AiResponseParser.ParseOutcome.Parsed ->
                when (val validated = AiResponseValidator.validate(parsed.proposal, sender, receivedAtMillis)) {
                    is AiValidationResult.Accepted -> Outcome.Extracted(validated.transaction)
                    is AiValidationResult.NotFinancial -> Outcome.NotFinancial(validated.reason)
                    is AiValidationResult.Rejected -> Outcome.Failed(validated.reason)
                }
        }
    }
}
