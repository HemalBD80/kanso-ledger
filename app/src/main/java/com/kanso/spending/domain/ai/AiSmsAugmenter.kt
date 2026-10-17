package com.kanso.spending.domain.ai

import com.kanso.spending.domain.sms.RawInboundSms
import com.kanso.spending.domain.sms.SmsParseResult
import com.kanso.spending.domain.sms.SmsParser

/**
 * Decides *which* messages, if any, are worth an external AI call, and builds
 * the override map [com.kanso.spending.domain.sms.SmsIngestPlanner] consumes.
 *
 * v2.4 treats the configured model as the PRIMARY interpreter for potentially
 * financial messages, because Bangladeshi bank / wallet / recharge / bill texts
 * vary far beyond any template list. The rules, in order:
 *
 *  1. **Filter locally first.** The deterministic parser drops obvious OTPs,
 *     marketing and unrelated text here — those messages are NEVER transmitted.
 *     Only messages the local rules read as financial/possibly-financial leave
 *     the device, redacted by [AiPromptBuilder] first.
 *  2. **AI interprets eligible messages.** When AI is enabled, every eligible
 *     message is sent for interpretation and a validated extraction overrides
 *     the local guess, so a new message format works without a new template.
 *  3. **Failure degrades, never blocks.** If the model errors, returns unusable
 *     JSON, or fails validation, the local result stands: a confident local parse
 *     is kept, and an unresolved one still reaches the review queue exactly as it
 *     would offline. Nothing is dropped, guessed, or crashed by an AI outage.
 *  4. **A confident local parse is never silently discarded.** If the model calls
 *     a message non-financial but the deterministic parser was confident it is
 *     financial, the local result is kept (losing real money is worse than a
 *     review item); only a locally-*ambiguous* message is dropped on the model's
 *     non-financial verdict.
 *
 * The AI call is injected as a function so the whole pipeline is unit-testable
 * with no network and no credential.
 */
class AiSmsAugmenter(
    /** Consults the AI for one message. Injected so tests can fake it. */
    private val consultAi: suspend (RawInboundSms) -> AiSmsInterpreter.Outcome,
    /** Master switch — when false, zero messages are consulted. */
    private val enabled: () -> Boolean
) {

    /** What happened to one message, for reporting/observability. */
    enum class Route {
        /** Local parser was already confident and AI was OFF — AI never asked. */
        LOCAL_CONFIDENT,
        /** Filtered as non-financial locally — never transmitted. */
        LOCAL_NOT_FINANCIAL,
        /** AI was consulted and produced a validated extraction (used). */
        AI_RESOLVED,
        /** AI called a locally-ambiguous message non-financial — dropped. */
        AI_NOT_FINANCIAL,
        /** AI called a confident local parse non-financial — local kept. */
        AI_DECLINED,
        /** AI was consulted but failed/unusable — local result stands. */
        AI_FALLBACK,
        /** AI not consulted at all because the master switch is off. */
        AI_DISABLED
    }

    data class Result(
        /** Index → AI-supplied parse, handed to the planner as overrides. */
        val overrides: Map<Int, SmsParseResult>,
        /** Per-message routing, in input order. */
        val routes: List<Route>
    ) {
        /** Messages actually transmitted for AI help. */
        val aiConsulted: Int
            get() = routes.count {
                it == Route.AI_RESOLVED || it == Route.AI_FALLBACK ||
                    it == Route.AI_NOT_FINANCIAL || it == Route.AI_DECLINED
            }
        val aiResolved: Int get() = routes.count { it == Route.AI_RESOLVED }
    }

    /**
     * Build the override map for one batch. Never throws: every AI call is
     * individually guarded so a single bad message cannot abort an import.
     */
    suspend fun buildOverrides(messages: List<RawInboundSms>): Result {
        val overrides = HashMap<Int, SmsParseResult>()
        val routes = ArrayList<Route>(messages.size)

        val active = enabled()
        for ((index, message) in messages.withIndex()) {
            val local = SmsParser.parse(message.sender, message.body, message.receivedAtMillis)

            // 1. Locally non-financial — never transmitted, never overridden.
            if (local is SmsParseResult.NotFinancial) {
                routes += Route.LOCAL_NOT_FINANCIAL
                continue
            }

            // 2. AI off: byte-for-byte the offline behaviour, no request made.
            if (!active) {
                routes += if (local is SmsParseResult.Financial) Route.LOCAL_CONFIDENT else Route.AI_DISABLED
                continue
            }

            // 3. AI on: interpret every eligible message.
            val outcome = runCatching { consultAi(message) }
                .getOrDefault(AiSmsInterpreter.Outcome.Failed("AI consultation threw."))
            when (outcome) {
                is AiSmsInterpreter.Outcome.Extracted -> {
                    overrides[index] = SmsParseResult.Financial(outcome.transaction)
                    routes += Route.AI_RESOLVED
                }
                is AiSmsInterpreter.Outcome.NotFinancial -> {
                    if (local is SmsParseResult.Ambiguous) {
                        // Local was unsure and the model says it is not financial:
                        // drop it (no ledger row, no review noise).
                        overrides[index] = SmsParseResult.NotFinancial(outcome.reason)
                        routes += Route.AI_NOT_FINANCIAL
                    } else {
                        // Local was confident about money — never discard it on the
                        // model's say-so. Keep the local result.
                        routes += Route.AI_DECLINED
                    }
                }
                is AiSmsInterpreter.Outcome.Failed -> routes += Route.AI_FALLBACK
            }
        }
        return Result(overrides, routes)
    }
}
