package com.kanso.spending.domain.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * A fully-built HTTP request: the exact URL, the headers (auth included) and the
 * JSON body. Pure data so the whole request shape can be unit-tested with no
 * network and no credential.
 */
data class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

/**
 * The wire format used to talk to the user's chosen provider.
 *
 * The app ships **no provider SDK** and bakes in **no vendor**: the user brings
 * their own base URL, key and model, and picks the format that matches their
 * provider's documentation ([AiApiFormat]). `OPENAI_CHAT` is the default because
 * most hosted providers and local servers accept it, but it is a *default*, not
 * an assumption — Anthropic, Google Gemini and Ollama each get a first-class
 * adapter, and any unrecognised-but-JSON reply is still read by
 * [AiReplyExtractor] rather than being declared malformed.
 *
 * Request building lives here; response extraction is deliberately format-
 * agnostic (see [AiReplyExtractor]) so a proxy that reshapes the envelope still
 * works.
 */
enum class AiApiFormat(
    val id: String,
    val label: String,
    val hint: String
) {
    OPENAI_CHAT(
        id = "openai_chat",
        label = "OpenAI-compatible chat completions",
        hint = "POST {base}/chat/completions with Authorization: Bearer. Works with OpenAI and " +
            "the many compatible gateways/servers (OpenRouter, Groq, Together, DeepSeek, Mistral, " +
            "xAI, vLLM, llama.cpp, LM Studio, and Ollama's /v1 bridge)."
    ),
    OPENAI_RESPONSES(
        id = "openai_responses",
        label = "OpenAI Responses API",
        hint = "POST {base}/responses — OpenAI's newer /v1/responses endpoint."
    ),
    ANTHROPIC_MESSAGES(
        id = "anthropic_messages",
        label = "Anthropic Messages",
        hint = "POST {base}/messages with x-api-key + anthropic-version headers (base e.g. https://api.anthropic.com/v1)."
    ),
    GOOGLE_GEMINI(
        id = "google_gemini",
        label = "Google Gemini",
        hint = "POST {base}/models/{model}:generateContent with x-goog-api-key (base e.g. https://generativelanguage.googleapis.com/v1beta)."
    ),
    OLLAMA_CHAT(
        id = "ollama_chat",
        label = "Ollama (native API)",
        hint = "POST {base}/api/chat with {model, messages, stream:false}. A local server needs no real key — any placeholder works."
    );

    /** Build the complete request for one message. Pure and unit-tested. */
    fun buildRequest(config: AiConfig, systemPrompt: String, userMessage: String): AiHttpRequest =
        AiHttpRequest(
            url = resolveUrl(config.baseUrl, config.modelId),
            headers = headersFor(config.apiKey.orEmpty()),
            body = bodyFor(config.modelId, systemPrompt, userMessage)
        )

    /**
     * Resolve the exact endpoint. The base URL owns the host and any version
     * prefix (e.g. `/v1`); the format owns the path. If the user pasted a full
     * endpoint that already contains a known path, it is respected verbatim so a
     * wrongly-appended path can never cause a 404.
     */
    fun resolveUrl(baseUrl: String, modelId: String): String {
        val base = baseUrl.trim()
        if (base.isEmpty()) return ""
        val lower = base.lowercase()
        if (FULL_ENDPOINT_MARKERS.any { lower.contains(it) }) return base
        val trimmed = base.trimEnd('/')
        return when (this) {
            OPENAI_CHAT -> "$trimmed/chat/completions"
            OPENAI_RESPONSES -> "$trimmed/responses"
            ANTHROPIC_MESSAGES -> "$trimmed/messages"
            OLLAMA_CHAT -> "$trimmed/api/chat"
            GOOGLE_GEMINI -> {
                val model = modelId.trim().removePrefix("models/").trim('/')
                "$trimmed/models/$model:generateContent"
            }
        }
    }

    /** Auth header(s), mapped to each provider's documented scheme. */
    private fun headersFor(key: String): Map<String, String> {
        val base = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to "KansoLedger"
        )
        when (this) {
            ANTHROPIC_MESSAGES -> {
                base["x-api-key"] = key
                base["anthropic-version"] = "2023-06-01"
            }
            GOOGLE_GEMINI -> base["x-goog-api-key"] = key
            else -> base["Authorization"] = "Bearer $key"
        }
        return base
    }

    private fun bodyFor(modelId: String, systemPrompt: String, userMessage: String): String = when (this) {
        OPENAI_CHAT -> JSONObject()
            .put("model", modelId)
            .put("temperature", 0.0)
            .put("max_tokens", MAX_TOKENS)
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userMessage))
            )
            .toString()

        OPENAI_RESPONSES -> JSONObject()
            .put("model", modelId)
            .put("instructions", systemPrompt)
            .put("input", userMessage)
            .put("max_output_tokens", MAX_TOKENS)
            .toString()

        ANTHROPIC_MESSAGES -> JSONObject()
            .put("model", modelId)
            .put("max_tokens", MAX_TOKENS)
            .put("system", systemPrompt)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", userMessage))
            )
            .toString()

        GOOGLE_GEMINI -> JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                )
            )
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.0).put("maxOutputTokens", MAX_TOKENS)
            )
            .toString()

        OLLAMA_CHAT -> JSONObject()
            .put("model", modelId)
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userMessage))
            )
            .put("options", JSONObject().put("temperature", 0.0).put("num_predict", MAX_TOKENS))
            .toString()
    }

    companion object {
        /** The default is a *format*, not a provider: no vendor is required. */
        val DEFAULT: AiApiFormat = OPENAI_CHAT

        /** All formats, in the order shown in settings. */
        val ALL: List<AiApiFormat> = entries.toList()

        fun fromId(id: String?): AiApiFormat = entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * Token budget for a reply. A reasoning model may spend tokens on a
         * hidden trace before answering (verified live for one such model at
         * 512), so 4096 leaves room for both the trace and the final JSON.
         */
        const val MAX_TOKENS = 4096

        private val FULL_ENDPOINT_MARKERS = listOf(
            "/chat/completions", "/responses", "/messages", ":generatecontent", "/api/chat"
        )
    }
}
