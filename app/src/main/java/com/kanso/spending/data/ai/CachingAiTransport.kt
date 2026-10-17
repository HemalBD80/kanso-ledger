package com.kanso.spending.data.ai

import com.kanso.spending.domain.ai.AiConfig
import com.kanso.spending.domain.ai.AiPromptBuilder
import com.kanso.spending.domain.ai.AiTransport
import com.kanso.spending.domain.ai.AiTransportResult
import java.security.MessageDigest
import java.util.Locale

/**
 * A transparent read-through cache in front of the real transport.
 *
 * The key is a SHA-256 of (API format, base URL, model id, system prompt,
 * redacted user message) — the credential is deliberately excluded, and the
 * message is already redacted by the interpreter before it gets here. Only
 * successful replies are cached, so an error is never mistaken for a result and
 * never replayed.
 *
 * This is the "local caching" step of the v2.4 import workflow: a repeated
 * broadcast, a re-import, or an app restart does not re-send a message the model
 * has already interpreted. A cache miss simply delegates to the real transport.
 */
class CachingAiTransport(
    private val delegate: AiTransport,
    private val cache: AiDecisionCache
) : AiTransport {

    override fun sendChat(
        config: AiConfig,
        systemPrompt: String,
        userMessage: String
    ): AiTransportResult {
        val key = keyFor(config, systemPrompt, userMessage)
        cache.get(key)?.let { return AiTransportResult.Success(it) }

        val result = delegate.sendChat(config, systemPrompt, userMessage)
        if (result is AiTransportResult.Success) {
            cache.put(key, result.replyText)
        }
        return result
    }

    private fun keyFor(config: AiConfig, systemPrompt: String, userMessage: String): String {
        val raw = listOf(
            config.apiFormat.id,
            config.baseUrl.trim(),
            config.modelId.trim(),
            systemPrompt,
            userMessage
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.lowercase(Locale.ROOT)
    }
}
