package com.kanso.spending.domain.ai

/**
 * On-demand connectivity diagnostic for the configured provider.
 *
 * This performs ONE real chat-completion request against the user's configured
 * endpoint/model using a deliberately trivial prompt (the model is asked to
 * reply with the single word "OK"). It carries no SMS text, no ledger data and
 * no identifier — its only purpose is to prove the HTTP round-trip, the auth
 * header, and the response envelope all work with the supplied credentials.
 *
 * It exists so a user can verify their own key + base URL + model ID from
 * inside the app (requirement: "a diagnostic action that performs a real API
 * request ... verify the HTTP status, response structure, and model response
 * without exposing the API key or private SMS contents").
 *
 * The result is a typed value that never carries the credential.
 */
object AiConnectionProbe {

    /** Structured outcome. Never contains the API key. */
    sealed class Result {
        /** The provider accepted the credential and returned a usable reply. */
        data class Success(val modelId: String, val latencyMs: Long) : Result()

        /** No key / base / model was configured — nothing was sent. */
        data object NotConfigured : Result()

        /** The provider rejected the credential (401/403). */
        data object Unauthorized : Result()

        /** The provider throttled the probe (429). */
        data object RateLimited : Result()

        /** Any other non-2xx HTTP status; the code is carried for display. */
        data class HttpError(val code: Int) : Result()

        /** Timeout, DNS or connection failure — could not reach the endpoint. */
        data object NetworkError : Result()

        /** A 2xx reply whose body was not a usable completion. */
        data object Malformed : Result()
    }

    /**
     * Minimal prompt: instructs the model to answer with exactly "OK" so the
     * reply is tiny, contains no data, and is trivially verifiable.
     */
    private const val PROBE_SYSTEM =
        "You are a connectivity check. Reply with exactly the single word: OK"
    private const val PROBE_USER = "ping"

    /**
     * Run the probe against [config] using the injected [transport].
     *
     * Blocking by design (the transport is synchronous); callers should run it
     * on a background dispatcher. Never throws — every transport outcome maps
     * to a typed [Result].
     */
    fun probe(transport: AiTransport, config: AiConfig): Result {
        if (!config.isConfigured) return Result.NotConfigured

        val started = System.currentTimeMillis()
        val outcome = transport.sendChat(config, PROBE_SYSTEM, PROBE_USER)
        val latency = System.currentTimeMillis() - started

        return when (outcome) {
            // A non-empty reply means the HTTP round-trip, auth and envelope
            // all succeeded. The body is discarded — we never display it.
            is AiTransportResult.Success -> Result.Success(config.modelId, latency)

            AiTransportResult.NotConfigured -> Result.NotConfigured
            AiTransportResult.Unauthorized -> Result.Unauthorized
            AiTransportResult.RateLimited -> Result.RateLimited
            is AiTransportResult.HttpError -> Result.HttpError(outcome.code)
            AiTransportResult.NetworkError -> Result.NetworkError
            AiTransportResult.Malformed -> Result.Malformed
        }
    }

    /** Human-readable, credential-free message for a [Result]. */
    fun describe(result: Result): String = when (result) {
        is Result.Success ->
            "Connected. Model '${result.modelId}' replied in ${result.latencyMs} ms."
        Result.NotConfigured ->
            "Not configured. Add your API key first."
        Result.Unauthorized ->
            "The provider rejected the API key (401/403). Check the key."
        Result.RateLimited ->
            "The provider is rate-limiting requests (429). Try again shortly."
        is Result.HttpError ->
            "The provider returned an error (HTTP ${result.code})."
        Result.NetworkError ->
            "Could not reach the provider. Check the base URL and your connection."
        Result.Malformed ->
            "The provider replied, but not with a usable completion."
    }
}
