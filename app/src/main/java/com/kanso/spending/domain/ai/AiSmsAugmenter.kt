package com.kanso.spending.domain.ai

import com.kanso.spending.domain.sms.RawInboundSms
import com.kanso.spending.domain.sms.SmsParseResult
import com.kanso.spending.domain.sms.SmsParser

/**
 * Decides *which* messages, if any, are worth an external AI call, and builds
 * the override map [com.kanso.spending.domain.sms.SmsIngestPlanner] consumes.
 *
 * The rules, in order:
 *
 *  1. **Filter locally first.** The deterministic parser decides. Messages it
 *     reads as not-financial are dropped here and never transmitted — the
 *     inbox is never uploaded, only the subset the local rules already flag as
 *     possibly-financial-but-unclear.
 *  2. **A confident local parse is never overridden.** If the local parser
 *     already resolved the message with confidence, AI is not consulted at all:
 *     this keeps cost bounded, keeps the deterministic parser authoritative,
 *     and means an AI outage cannot degrade messages that already worked.
 *  3. **Only genuine ambiguity goes out.** Just the `Ambiguous` case is sent,
 *     redacted by [AiPromptBuilder] before it leaves the device.
 *  4. **Failure degrades, never blocks.** If the AI path returns anything other
 *     than a validated extraction, the local ambiguous result stands and the
 *     message reaches the review queue exactly as it would have offline.
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
        /** Local parser was already confident — AI never asked. */
        LOCAL_CONFIDENT,
        /** Filtered as non-financial locally — never transmitted. */
        LOCAL_NOT_FINANCIAL,
        /** AI was consulted and produced a validated extraction. */
        AI_RESOLVED,
        /** AI was consulted but could not resolve it — local result stands. */
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
        /** Messages actually transmitted for AI help (excludes the disabled path). */
        val aiConsulted: Int get() = routes.count { it == Route.AI_RESOLVED || it == Route.AI_FALLBACK }
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
            when (local) {
                // Filtered locally — never transmitted.
                is SmsParseResult.NotFinancial -> routes += Route.LOCAL_NOT_FINANCIAL

                // Local parser already confident: AI is neither needed nor asked.
                is SmsParseResult.Financial -> routes += Route.LOCAL_CONFIDENT

                is SmsParseResult.Ambiguous -> {
                    if (!active) {
                        // AI disabled: identical to today's offline behaviour,
                        // and — importantly — no request is ever made.
                        routes += Route.AI_DISABLED
                        continue
                    }
                    val outcome = runCatching { consultAi(message) }
                        .getOrDefault(AiSmsInterpreter.Outcome.Failed("AI consultation threw."))
                    when (outcome) {
                        is AiSmsInterpreter.Outcome.Extracted -> {
                            // Re-validate through the same result the parser
                            // would have produced, so the planner sees a normal
                            // SmsParseResult and every downstream rule applies.
                            overrides[index] = SmsParseResult.Financial(outcome.transaction)
                            routes += Route.AI_RESOLVED
                        }
                        // Not-financial, failed, or unavailable → local stands.
                        else -> routes += Route.AI_FALLBACK
                    }
                }
            }
        }
        return Result(overrides, routes)
    }
}
