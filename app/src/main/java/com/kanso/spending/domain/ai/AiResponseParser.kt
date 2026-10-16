package com.kanso.spending.domain.ai

import org.json.JSONObject

/**
 * Parses the model's raw reply into an [AiTransactionProposal].
 *
 * The model is untrusted: it may wrap JSON in markdown fences, prepend prose,
 * return nulls, or emit something entirely unusable. This parser is deliberately
 * defensive and never throws — an unparseable reply becomes [ParseOutcome.Malformed]
 * so the caller falls back to local parsing rather than crashing or guessing.
 *
 * Only the documented OpenAI chat-completion envelope is understood:
 * `choices[0].message.content`. Anything else is malformed.
 */
object AiResponseParser {

    /** Largest reply we will even attempt to parse, as a cheap size guard. */
    const val MAX_REPLY_CHARS = 8_000

    sealed class ParseOutcome {
        data class Parsed(val proposal: AiTransactionProposal) : ParseOutcome()
        data object Malformed : ParseOutcome()
    }

    fun parse(replyText: String?): ParseOutcome {
        if (replyText.isNullOrBlank()) return ParseOutcome.Malformed
        if (replyText.length > MAX_REPLY_CHARS) return ParseOutcome.Malformed

        val trimmed = replyText.trim()
        val isEnvelope = runCatching { JSONObject(trimmed).has("choices") }.getOrDefault(false)

        val source = if (isEnvelope) {
            // The OpenAI envelope carries the model's JSON as a *string* at
            // choices[0].message.content. A null/blank content — which happens
            // when a reasoning model exhausts its budget on the hidden reasoning
            // trace, or the reply is truncated — is unusable and must NEVER be
            // silently reinterpreted as "not financial".
            extractContentString(trimmed) ?: return ParseOutcome.Malformed
        } else {
            // Not an envelope: treat the whole reply as a bare proposal object.
            trimmed
        }

        val payload = extractJsonObject(source) ?: return ParseOutcome.Malformed
        return ParseOutcome.Parsed(toProposal(payload))
    }

    /**
     * Pull the assistant's message text out of the OpenAI chat-completion
     * envelope. Returns null when the reply is not an envelope (e.g. bare JSON),
     * so the caller can fall back to parsing the reply directly.
     */
    private fun extractContentString(replyText: String): String? {
        val envelope = runCatching { JSONObject(replyText.trim()) }.getOrNull() ?: return null
        val choices = envelope.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        // content is a string in the real API; tolerate an embedded object too.
        val embedded = message.optJSONObject("content")
        return when {
            message.isNull("content") -> null
            embedded != null -> embedded.toString()
            else -> message.optString("content").takeIf { it.isNotBlank() }
        }
    }

    /**
     * Locate the JSON object inside a reply. Tolerates a markdown ```json fence
     * and surrounding prose by scanning for the first balanced top-level object.
     */
    private fun extractJsonObject(text: String): JSONObject? {
        val unfenced = text
            .replace(Regex("```(?:json)?"), "")
            .trim()
        // Fast path: the whole reply is the object.
        runCatching { return JSONObject(unfenced) }
        // Slow path: first balanced {...} span.
        val start = unfenced.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until unfenced.length) {
            val c = unfenced[i]
            if (escaped) { escaped = false; continue }
            when {
                c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = unfenced.substring(start, i + 1)
                        return runCatching { JSONObject(candidate) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun toProposal(o: JSONObject): AiTransactionProposal = AiTransactionProposal(
        isFinancial = o.optBoolean("isFinancial", false),
        provider = o.optStringOrNull("provider"),
        type = o.optStringOrNull("type"),
        amount = o.optStringOrNull("amount"),
        currency = o.optStringOrNull("currency"),
        timestamp = o.optStringOrNull("timestamp"),
        reference = o.optStringOrNull("reference"),
        fee = o.optStringOrNull("fee"),
        merchant = o.optStringOrNull("merchant"),
        accountHint = o.optStringOrNull("accountHint"),
        reportedBalance = o.optStringOrNull("reportedBalance"),
        direction = o.optStringOrNull("direction"),
        confidence = if (o.has("confidence") && !o.isNull("confidence")) o.optDouble("confidence", 0.0) else null,
        categorySuggestion = o.optStringOrNull("categorySuggestion"),
        warnings = o.optJSONArray("warnings")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }
    )

    /** JSONObject returns "" for JSON null — treat blank/absent as a real null. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}
