package com.kanso.spending.domain.ai

/**
 * Provider-agnostic AI configuration.
 *
 * The user supplies **all four** values at runtime: the model id, the API base
 * URL, the credential, and the API format (see [AiApiFormat]). Nothing here
 * names, defaults to, or requires a particular vendor — the defaults are blank
 * on purpose so a user cannot accidentally send anything to a service they did
 * not choose. Only the non-secret values are ever persisted; the key is held in
 * app-private storage and is never defaulted, hardcoded, committed, or logged.
 *
 * Changing the provider means supplying a different [AiConfig]; nothing in
 * [com.kanso.spending.domain.sms] or the repository needs to change.
 */
data class AiConfig(
    /** The provider's model identifier, exactly as the provider documents it. */
    val modelId: String,
    /** The API base URL, e.g. "https://your-provider.example/v1". */
    val baseUrl: String,
    /** Bearer/API credential. Null until the user supplies one. */
    val apiKey: String?,
    /** The wire format to speak. Defaults to OpenAI-compatible, not to a vendor. */
    val apiFormat: AiApiFormat = AiApiFormat.DEFAULT
) {
    /** True only when the user has supplied a key, a base URL and a model. */
    val isConfigured: Boolean
        get() = !apiKey.isNullOrBlank() && baseUrl.isNotBlank() && modelId.isNotBlank()

    /** The exact URL the request will use, for the selected [apiFormat]. */
    val requestUrl: String
        get() = apiFormat.resolveUrl(baseUrl, modelId)

    /**
     * Retained name for callers that only ever spoke the OpenAI-ish chat path.
     * Uses the base URL as-is and appends `/chat/completions` unless it is
     * already a full endpoint.
     */
    val chatCompletionsUrl: String
        get() = AiApiFormat.OPENAI_CHAT.resolveUrl(baseUrl, modelId)

    companion object {
        // No vendor, model or endpoint is baked in. The user supplies all of them.
        const val DEFAULT_MODEL_ID = ""
        const val DEFAULT_BASE_URL = ""

        /** An unconfigured instance: usable for UI, never for a network call. */
        fun unconfigured(): AiConfig = AiConfig(
            modelId = DEFAULT_MODEL_ID,
            baseUrl = DEFAULT_BASE_URL,
            apiKey = null,
            apiFormat = AiApiFormat.DEFAULT
        )
    }
}
