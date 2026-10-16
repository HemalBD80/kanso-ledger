package com.kanso.spending.domain.ai

/**
 * Provider-agnostic AI configuration.
 *
 * The three developer-supplied values ([modelId], [baseUrl], [apiKey]) are kept
 * entirely out of business logic so the provider can be swapped later without
 * touching the ledger. Only the non-secret parts (model id + base URL) have
 * defaults; the key is NEVER defaulted, hardcoded, or committed — it is supplied
 * at runtime by the user and held in local storage.
 *
 * Changing the provider means supplying a different [AiConfig]; nothing in
 * [com.kanso.spending.domain.sms] or the repository needs to change.
 */
data class AiConfig(
    /** Provider model identifier, e.g. "z-ai/glm-5.3-flash". */
    val modelId: String,
    /** OpenAI-compatible base URL, e.g. "https://integrate.api.nvidia.com/v1". */
    val baseUrl: String,
    /** Bearer credential. Null when the user has not configured AI yet. */
    val apiKey: String?
) {
    /** True only when a key is present — the single gate for any network call. */
    val isConfigured: Boolean
        get() = !apiKey.isNullOrBlank() && baseUrl.isNotBlank() && modelId.isNotBlank()

    /** Endpoints are derived from the base URL; the full path is never hardcoded. */
    val chatCompletionsUrl: String
        get() = baseUrl.trimEnd('/') + "/chat/completions"

    companion object {
        /** Developer-supplied defaults — model id and base URL only, never a key. */
        const val DEFAULT_MODEL_ID = "z-ai/glm-5.3-flash"
        const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"

        /** An unconfigured instance: usable for UI, never for a network call. */
        fun unconfigured(): AiConfig = AiConfig(
            modelId = DEFAULT_MODEL_ID,
            baseUrl = DEFAULT_BASE_URL,
            apiKey = null
        )
    }
}
