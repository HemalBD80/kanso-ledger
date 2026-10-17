package com.kanso.spending.domain.ai

/**
 * Provider-agnostic transport for one chat-completion request.
 *
 * Business logic depends only on this interface, never on a concrete HTTP
 * stack, so the provider can be swapped (or the whole feature disabled) without
 * touching the ledger. Automated tests supply a fake implementation — no real
 * credentials and no paid requests are ever needed.
 */
interface AiTransport {
    /**
     * Send one chat completion and return the model's text reply.
     *
     * Implementations own the mechanical concerns — HTTP, auth header, timeouts,
     * bounded retries, rate-limit (429) backoff, response-size limits and
     * provider-specific response shapes — and surface them as a typed
     * [AiTransportResult] rather than throwing, so the caller can never crash on
     * a network failure.
     */
    fun sendChat(
        config: AiConfig,
        systemPrompt: String,
        userMessage: String
    ): AiTransportResult
}

/**
 * Typed outcome of a transport call. Never carries the credential.
 *
 * The failure cases are deliberately fine-grained so the diagnostic can tell an
 * authentication problem from a bad model id, an unsupported endpoint, an
 * unusable response, and an empty reply — rather than collapsing them all into
 * "malformed".
 */
sealed class AiTransportResult {
    /**
     * The model returned usable assistant text. [replyText] is the *extracted*
     * message content (a JSON payload or plain prose), never a secret.
     */
    data class Success(val replyText: String) : AiTransportResult()

    /** The feature is unusable because no key/base/model was configured. */
    data object NotConfigured : AiTransportResult()

    /** Provider said the credential was rejected (401/403). */
    data object Unauthorized : AiTransportResult()

    /** Provider throttled us (429), including after the bounded retries. */
    data object RateLimited : AiTransportResult()

    /**
     * Any other non-success HTTP status. [code] is carried for display and
     * [providerMessage] is a short, sanitised hint from the provider's error
     * body (e.g. "model not found"), with no credential and no message text.
     */
    data class HttpError(val code: Int, val providerMessage: String? = null) : AiTransportResult()

    /** Timeout, DNS, or connection failure — the endpoint could not be reached. */
    data object NetworkError : AiTransportResult()

    /**
     * A 2xx reply whose message field was present but empty (e.g. a reasoning
     * model that spent its whole budget on the hidden trace). Distinct from a
     * malformed body so it can be reported precisely.
     */
    data class EmptyReply(val reason: String? = null) : AiTransportResult()

    /**
     * A reply that arrived but could not be read as a completion (unknown
     * response shape, or a body that was not usable text). [reason] is a short,
     * credential-free explanation.
     */
    data class Malformed(val reason: String? = null) : AiTransportResult()

    /**
     * The provider reported an error *inside* the body — some gateways do this
     * with an HTTP 200. [message] is the sanitised provider message.
     */
    data class ProviderError(val message: String?) : AiTransportResult()
}
