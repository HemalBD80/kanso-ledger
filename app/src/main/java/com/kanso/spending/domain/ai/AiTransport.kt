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
     * Send one chat completion and return the model's raw text reply.
     *
     * Implementations own the mechanical concerns — HTTP, auth header, timeouts,
     * bounded retries, rate-limit (429) backoff, and response-size limits — and
     * surface them as a typed [AiTransportResult] rather than throwing, so the
     * caller can never crash on a network failure.
     */
    fun sendChat(
        config: AiConfig,
        systemPrompt: String,
        userMessage: String
    ): AiTransportResult
}

/** Typed outcome of a transport call. Never carries the credential. */
sealed class AiTransportResult {
    /** The model returned a non-empty reply. */
    data class Success(val replyText: String) : AiTransportResult()

    /** The feature is unusable because no key/base/model was configured. */
    data object NotConfigured : AiTransportResult()

    /** Provider said the credential was rejected (401/403). */
    data object Unauthorized : AiTransportResult()

    /** Provider throttled us (429), including after the bounded retries. */
    data object RateLimited : AiTransportResult()

    /** Any other non-success HTTP status. The status code is carried for display. */
    data class HttpError(val code: Int) : AiTransportResult()

    /** Timeout, DNS, or connection failure. */
    data object NetworkError : AiTransportResult()

    /** The reply exceeded the size limit, or was not usable text. */
    data object Malformed : AiTransportResult()
}
