package com.kanso.spending.domain.ai

import org.json.JSONArray
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

    /**
     * Upper bound on the raw transport envelope. A reasoning model may include a
     * long hidden trace in the envelope body, so this is generous; the tight
     * [MAX_REPLY_CHARS] limit is applied to the extracted assistant content, not
     * the envelope — otherwise a valid small JSON answer would be thrown away
     * just because the trace around it was large.
     */
    const val MAX_ENVELOPE_CHARS = 512_000

    sealed class ParseOutcome {
        data class Parsed(val proposal: AiTransactionProposal) : ParseOutcome()
        data object Malformed : ParseOutcome()
    }

    fun parse(replyText: String?): ParseOutcome {
        if (replyText.isNullOrBlank()) return ParseOutcome.Malformed
        if (replyText.length > MAX_ENVELOPE_CHARS) return ParseOutcome.Malformed

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

        if (source.length > MAX_REPLY_CHARS) return ParseOutcome.Malformed
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
        return when (val content = message.opt("content")) {
            null, JSONObject.NULL -> null
            // Some providers/proxies return content as a list of typed blocks
            // (e.g. [{"type":"text","text":"{...}"}]). Concatenate the text parts.
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val text = part.optString("text").ifBlank { part.optString("content") }
                    if (text.isNotBlank()) append(text)
                }
            }.takeIf { it.isNotBlank() }
            is JSONObject -> content.toString()
            else -> message.optString("content").takeIf { it.isNotBlank() }
        }
    }

    /**
     * Locate the JSON object inside a reply. Tolerates a markdown ```json fence
     * and surrounding prose by scanning for a complete, balanced object — but
     * only accepts one that actually looks like *this* app's proposal (it must
     * carry one of the contract keys). Arbitrary prose-embedded JSON (a status
     * object, an example, an error blob) is never mistaken for a classification.
     */
    private fun extractJsonObject(text: String): JSONObject? {
        val unfenced = text
            .replace(Regex("```(?:json)?"), "")
            .trim()
        // Fast path: the whole reply is the object — but only accept it when it
        // is actually one of *our* proposal objects. org.json happily parses a
        // leading object and ignores trailing text, so without this check a junk
        // `{...}` prefix would be accepted as a classification.
        runCatching { JSONObject(unfenced) }.getOrNull()
            ?.takeIf { it.looksLikeProposalObject() }
            ?.let { return it }

        var index = 0
        while (true) {
            val start = unfenced.indexOf('{', index)
            if (start < 0) return null
            val end = balancedObjectEnd(unfenced, start) ?: return null
            val candidate = unfenced.substring(start, end + 1)
            val parsed = runCatching { JSONObject(candidate) }.getOrNull()
            if (parsed != null && parsed.looksLikeProposalObject()) return parsed
            index = end + 1
        }
    }

    /** Index of the '}' that closes the '{' at [start], or null if unbalanced. */
    private fun balancedObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            when {
                c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** True when an object carries at least one of this app's contract keys. */
    private fun JSONObject.looksLikeProposalObject(): Boolean =
        has("is_financial") || has("isFinancial") || has("transaction_type")

    private fun toProposal(o: JSONObject): AiTransactionProposal = AiTransactionProposal(
        isFinancial = o.optBoolean("is_financial", o.optBoolean("isFinancial", false)),
        provider = o.firstString("provider"),
        type = o.firstString("transaction_type", "type"),
        amount = o.firstString("amount_bdt", "amount"),
        currency = o.firstString("currency"),
        timestamp = o.firstString("transaction_timestamp", "timestamp"),
        reference = o.firstString("transaction_reference", "reference"),
        fee = o.firstString("fee_bdt", "fee"),
        merchant = o.firstString("merchant_or_recipient", "merchant"),
        accountHint = o.firstString("account_hint", "accountHint"),
        reportedBalance = o.firstString("reported_balance_bdt", "reportedBalance"),
        direction = o.firstString("direction"),
        confidence = if (o.has("confidence") && !o.isNull("confidence")) o.optDouble("confidence", 0.0) else null,
        categorySuggestion = o.firstString("category_suggestion", "categorySuggestion"),
        paymentMethodSuggestion = o.firstString("payment_method_suggestion", "paymentMethodSuggestion"),
        evidence = o.firstString("evidence"),
        warnings = o.optJSONArray("warnings")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }
    )

    /** First non-blank string among the given keys (new snake_case, then legacy camelCase). */
    private fun JSONObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            if (has(key) && !isNull(key)) {
                optString(key).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    /** JSONObject returns "" for JSON null — treat blank/absent as a real null. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}
