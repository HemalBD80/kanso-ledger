package com.kanso.spending.domain.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulls the assistant's answer out of **any** of the common provider response
 * envelopes — no vendor is assumed.
 *
 * This is the fix for `MALFORMED_REPLY`: the old code only understood OpenAI's
 * `choices[0].message.content`, so a perfectly good reply from any other
 * provider was discarded. Here every documented shape is tried in turn and, if
 * none match, a bounded tree scan looks for the first proposal-looking object or
 * string anywhere in the body. Only a genuinely unusable body is reported as
 * unrecognised — and that is surfaced distinctly from empty or non-JSON.
 *
 * Pure and dependency-light ([org.json] only), so it is fully unit-testable with
 * no network.
 */
object AiReplyExtractor {

    /** What a response body turned out to contain. */
    sealed class Extraction {
        /** Usable assistant text (a JSON payload or plain prose). */
        data class Text(val text: String, val finishReason: String? = null) : Extraction()

        /** A recognised envelope whose message field was empty/null. */
        data object Empty : Extraction()

        /** A JSON body with none of the known message fields. */
        data object Unrecognised : Extraction()

        /** A non-JSON body (plain text, HTML, …). */
        data object NotJson : Extraction()
    }

    /**
     * If the body is a provider-level error object (`{"error": {...}}`), return
     * a short human-readable message; else null. Some gateways return these with
     * an HTTP 200, so this is checked before treating the body as a reply.
     */
    fun providerError(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = runCatching { JSONObject(ssePayload(text) ?: text) }.getOrNull() ?: return null
        val error = parsed.opt("error") ?: return null
        val message = when (error) {
            is JSONObject -> error.firstNonBlank("message", "detail", "reason", "type", "code")
            is String -> error.takeIf { it.isNotBlank() }
            else -> error.toString().takeIf { it.isNotBlank() }
        }
        return message
    }

    /**
     * True when a body is markup — an HTML/XML page (e.g. a proxy login page or
     * a 200-wrapped gateway error) rather than an API reply. Used so that "200 OK
     * with an HTML body" is reported as a wrong endpoint, not a model problem.
     */
    fun looksLikeMarkup(text: String): Boolean {
        val head = text.trimStart()
        return head.startsWith("<!") ||
            head.startsWith("<html", ignoreCase = true) ||
            head.startsWith("<?xml", ignoreCase = true) ||
            head.startsWith("<error", ignoreCase = true)
    }

    /**
     * A **value-free** description of a response body's shape, for diagnostics.
     *
     * It reports only structure (JSON object + top-level *key names*, array
     * length, or "non-JSON plain text / page") — never the message text and never
     * the credential — so a user can report exactly what their provider returned
     * without leaking any SMS content.
     */
    fun describeShape(body: String?): String {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return "an empty body"
        val payload = ssePayload(text) ?: text

        runCatching { JSONObject(payload) }.getOrNull()?.let { obj ->
            val keys = obj.keys().asSequence().sorted().take(16).joinToString(", ")
            return "a JSON object with top-level keys [$keys]"
        }
        runCatching { JSONArray(payload) }.getOrNull()?.let { arr ->
            return "a JSON array of ${arr.length()} item(s)"
        }
        return when {
            looksLikeMarkup(text) -> "a non-JSON page (looks like HTML/XML)"
            text.startsWith("data:") -> "an SSE stream with no usable payload"
            else -> "non-JSON plain text"
        }
    }

    /** Extract the assistant text from a raw HTTP response body. */
    fun extract(body: String?): Extraction {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty()) return Extraction.Empty

        val text = ssePayload(raw) ?: raw
        if (text.isBlank()) return Extraction.Empty

        runCatching { JSONObject(text) }.getOrNull()?.let { return fromObject(it) }

        runCatching { JSONArray(text) }.getOrNull()?.let { array ->
            for (i in 0 until array.length()) {
                val element = array.optJSONObject(i) ?: continue
                val result = fromObject(element)
                if (result is Extraction.Text) return result
            }
            return Extraction.Unrecognised
        }

        // Not JSON. A bare proposal is still usable; other prose is returned as
        // plain text so a connectivity probe succeeds (the schema parser judges
        // it for a real classification).
        return if (looksLikeProposalText(text)) Extraction.Text(text) else Extraction.NotJson
    }

    private fun fromObject(obj: JSONObject): Extraction {
        // Whether this looks like a known chat/completion envelope at all. If it
        // does but carries no text, it is an *empty* reply (a token-budget
        // problem) rather than an unknown format.
        var recognised = false
        for (key in RECOGNISED_ENVELOPE_KEYS) {
            if (obj.has(key)) { recognised = true; break }
        }

        val finishReason = obj.optJSONArray("choices")?.optJSONObject(0)
            ?.optString("finish_reason")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONArray("candidates")?.optJSONObject(0)
                ?.optString("finishReason")?.takeIf { it.isNotBlank() }

        // 1. OpenAI-style chat: choices[].message.content (string, block array, object).
        obj.optJSONArray("choices")?.let { choices ->
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                contentOf(choice.optJSONObject("message")?.opt("content"))
                    ?.let { return Extraction.Text(it, finishReason) }
                choice.optString("text").takeIf { it.isNotBlank() }
                    ?.let { return Extraction.Text(it, finishReason) }
            }
        }

        // 2. Ollama / some proxies: {message: {content: "..."}}.
        obj.optJSONObject("message")?.opt("content")
            ?.let { contentOf(it)?.let { text -> return Extraction.Text(text, finishReason) } }

        // 3. OpenAI Responses: output_text, or output[].content[].text.
        obj.optString("output_text").takeIf { it.isNotBlank() }
            ?.let { return Extraction.Text(it, finishReason) }
        obj.optJSONArray("output")?.let { output ->
            val joined = StringBuilder()
            for (i in 0 until output.length()) {
                val parts = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until parts.length()) {
                    parts.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }
                        ?.let { joined.append(it) }
                }
            }
            if (joined.isNotBlank()) return Extraction.Text(joined.toString(), finishReason)
        }

        // 4. Google Gemini: candidates[].content.parts[].text.
        obj.optJSONArray("candidates")?.let { candidates ->
            val joined = StringBuilder()
            for (i in 0 until candidates.length()) {
                val parts = candidates.optJSONObject(i)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    parts.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }
                        ?.let { joined.append(it) }
                }
            }
            if (joined.isNotBlank()) return Extraction.Text(joined.toString(), finishReason)
        }

        // 5. Anthropic: content (string or blocks[] with .text).
        obj.opt("content")?.let { content ->
            contentOf(content)?.let { return Extraction.Text(it, finishReason) }
        }

        // 6. Miscellaneous single-field shapes used by self-hosted servers.
        for (key in MISC_TEXT_FIELDS) {
            if (!obj.has(key) || obj.isNull(key)) continue
            (obj.opt(key) as? String)?.takeIf { it.isNotBlank() }
                ?.let { return Extraction.Text(it, finishReason) }
        }

        // 7. The body itself is the proposal object.
        if (looksLikeProposalObject(obj)) return Extraction.Text(obj.toString(), finishReason)

        // 8. Bounded tree scan for a nested proposal (string or object).
        findProposalText(obj)?.let { return Extraction.Text(it, finishReason) }

        return if (recognised) Extraction.Empty else Extraction.Unrecognised
    }

    /** Extract text from a `content` value that may be a string, block array or object. */
    private fun contentOf(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.takeIf { it.isNotBlank() }
        is JSONArray -> {
            val joined = StringBuilder()
            for (i in 0 until value.length()) {
                when (val element = value.opt(i)) {
                    is String -> joined.append(element)
                    is JSONObject -> {
                        val part = element.optString("text").ifBlank { element.optString("content") }
                        if (part.isNotBlank()) joined.append(part)
                    }
                    else -> Unit
                }
            }
            joined.toString().takeIf { it.isNotBlank() }
        }
        is JSONObject -> {
            val text = value.optString("text").ifBlank { value.optString("content") }
            text.takeIf { it.isNotBlank() } ?: value.toString().takeIf { it.isNotBlank() }
        }
        else -> value.toString().takeIf { it.isNotBlank() }
    }

    private fun looksLikeProposalText(text: String): Boolean =
        text.contains("is_financial") || text.contains("isFinancial") ||
            (text.contains("transaction_type") && text.contains("amount"))

    private fun looksLikeProposalObject(obj: JSONObject): Boolean =
        obj.has("is_financial") || obj.has("isFinancial") ||
            (obj.has("transaction_type") && (obj.has("amount") || obj.has("amount_bdt")))

    /** First string anywhere in the tree that itself contains a proposal. */
    private fun findProposalText(node: Any?, depth: Int = 0): String? {
        if (depth > 8) return null
        when (node) {
            is JSONObject -> {
                if (looksLikeProposalObject(node)) return node.toString()
                for (key in node.keys()) {
                    val child = node.opt(key)
                    if (child is String && looksLikeProposalText(child)) return child
                    findProposalText(child, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findProposalText(node.opt(i), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    /** If the body is an SSE stream, return the last JSON `data:` payload. */
    private fun ssePayload(text: String): String? {
        if (!text.contains("data:")) return null
        val payload = text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .lastOrNull()
        return payload
    }

    private val MISC_TEXT_FIELDS = listOf(
        "response", "generated_text", "text", "completion", "result", "answer", "output"
    )

    /** Keys whose presence means "this is a completion envelope, just empty". */
    private val RECOGNISED_ENVELOPE_KEYS = listOf(
        "choices", "candidates", "message", "output", "output_text", "content"
    )

    private fun JSONObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = opt(key)
            if (value is String && value.isNotBlank()) return value
        }
        return null
    }
}
