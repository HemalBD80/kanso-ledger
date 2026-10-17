package com.kanso.spending.data.ai

import com.kanso.spending.domain.ai.AiApiFormat
import com.kanso.spending.domain.ai.AiConfig
import com.kanso.spending.domain.ai.AiHttpRequest
import com.kanso.spending.domain.ai.AiReplyExtractor
import com.kanso.spending.domain.ai.AiTransport
import com.kanso.spending.domain.ai.AiTransportResult
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one real network call in the app: one chat-completion-style request built
 * for the API format the user selected ([AiApiFormat]).
 *
 * Uses [HttpURLConnection] (built into Android) rather than adding any provider
 * SDK or dependency, and is deliberately conservative so a misbehaving provider
 * can never crash the ledger or spin forever:
 *  - connect/read timeouts bound every request;
 *  - 429 and transient 5xx get a small bounded retry with backoff; other 4xx do not;
 *  - the response body is read up to a hard size cap and truncated beyond it;
 *  - the reply is read with the provider-tolerant [AiReplyExtractor], so a
 *    non-OpenAI envelope is understood rather than reported as malformed;
 *  - every failure maps to a typed [AiTransportResult] — this never throws.
 *
 * The credential is sent only in the format's auth header, never in the body,
 * never logged, and never returned to the caller (provider error hints are
 * sanitised to strip the key).
 */
class HttpAiTransport : AiTransport {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RETRIES = 2
        const val BACKOFF_BASE_MS = 700L
        const val MAX_REPLY_BYTES = 256 * 1024
        const val MAX_HINT_CHARS = 200
    }

    override fun sendChat(config: AiConfig, systemPrompt: String, userMessage: String): AiTransportResult {
        if (!config.isConfigured) return AiTransportResult.NotConfigured

        val request = config.apiFormat.buildRequest(config, systemPrompt, userMessage)
        if (request.url.isBlank()) return AiTransportResult.NotConfigured
        val body = request.body.toByteArray(Charsets.UTF_8)

        var attempt = 0
        var lastResult: AiTransportResult = AiTransportResult.NetworkError
        while (attempt <= MAX_RETRIES) {
            val result = attemptOnce(request, body, config.apiKey.orEmpty())
            when (result) {
                // Transient classes worth one more try, with backoff.
                AiTransportResult.RateLimited,
                AiTransportResult.NetworkError,
                is AiTransportResult.HttpError -> {
                    if (result is AiTransportResult.HttpError && result.code !in RETRYABLE) {
                        return result
                    }
                    lastResult = result
                }
                else -> return result
            }
            if (attempt == MAX_RETRIES) break
            try {
                Thread.sleep(BACKOFF_BASE_MS * (1L shl attempt))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return AiTransportResult.NetworkError
            }
            attempt++
        }
        return lastResult
    }

    private fun attemptOnce(
        request: AiHttpRequest,
        body: ByteArray,
        apiKey: String
    ): AiTransportResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                for ((name, value) in request.headers) setRequestProperty(name, value)
            }
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            if (code in 200..299) readSuccess(connection) else readFailure(code, connection, apiKey)
        } catch (timeout: java.net.SocketTimeoutException) {
            AiTransportResult.NetworkError
        } catch (io: IOException) {
            AiTransportResult.NetworkError
        } catch (ignored: Exception) {
            // A malformed/unexpected payload must degrade, never crash the app.
            AiTransportResult.Malformed("the request could not be completed")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Map a non-2xx status to a precise category. The provider's error body (if
     * any) gives a short, sanitised hint so a bad model id, a wrong endpoint and
     * a genuine auth failure are distinguishable.
     */
    private fun readFailure(code: Int, connection: HttpURLConnection, apiKey: String): AiTransportResult =
        when (code) {
            401, 403 -> AiTransportResult.Unauthorized
            429 -> AiTransportResult.RateLimited
            else -> AiTransportResult.HttpError(code, readErrorHint(connection, apiKey))
        }

    private fun readSuccess(connection: HttpURLConnection): AiTransportResult {
        val text = try {
            readBody(connection.inputStream)
        } catch (io: IOException) {
            return AiTransportResult.Malformed("the response body could not be read")
        }
        if (text.isBlank()) return AiTransportResult.EmptyReply("the provider returned an empty body")

        // Some gateways report an error inside a 200 body.
        AiReplyExtractor.providerError(text)?.let {
            return AiTransportResult.ProviderError(sanitise(it, null))
        }

        return when (val extraction = AiReplyExtractor.extract(text)) {
            is AiReplyExtractor.Extraction.Text -> {
                val content = extraction.text.trim()
                if (content.isEmpty()) {
                    AiTransportResult.EmptyReply(
                        if (extraction.finishReason == "length") {
                            "the model hit its token limit before answering"
                        } else {
                            "the provider returned an empty completion (" +
                                AiReplyExtractor.describeShape(text) + ")"
                        }
                    )
                } else {
                    AiTransportResult.Success(content)
                }
            }
            AiReplyExtractor.Extraction.Empty ->
                AiTransportResult.EmptyReply(
                    "the provider returned an empty completion (" +
                        AiReplyExtractor.describeShape(text) + ")"
                )
            AiReplyExtractor.Extraction.Unrecognised ->
                AiTransportResult.Malformed(
                    "the response did not contain a recognised message field (" +
                        AiReplyExtractor.describeShape(text) +
                        ") — check the API format"
                )
            // A plain-text body can still be a real answer (a connectivity probe
            // succeeds), but markup — an HTML/XML page — is a wrong endpoint, not
            // a completion. Reporting it as malformed is what turns a confusing
            // "MALFORMED_REPLY" into an actionable "wrong base URL / API format".
            AiReplyExtractor.Extraction.NotJson ->
                if (AiReplyExtractor.looksLikeMarkup(text)) {
                    AiTransportResult.Malformed(
                        "the endpoint returned a non-JSON page, not an API reply " +
                            "(check the base URL and API format)"
                    )
                } else {
                    AiTransportResult.Success(text.trim())
                }
        }
    }

    /** Read a short, sanitised error hint from a failed response body. */
    private fun readErrorHint(connection: HttpURLConnection, apiKey: String): String? {
        val stream: InputStream = try {
            connection.errorStream ?: return null
        } catch (ignored: Exception) {
            return null
        }
        val text = try {
            readBody(stream)
        } catch (io: IOException) {
            return null
        }
        val hint = AiReplyExtractor.providerError(text)
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return hint?.let { sanitise(it, apiKey) }
    }

    /** Bounded read: never buffers more than [MAX_REPLY_BYTES]. */
    private fun readBody(stream: InputStream): String = stream.use { input ->
        val buffer = ByteArray(8 * 1024)
        val out = StringBuilder()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_REPLY_BYTES) break
            out.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        out.toString()
    }

    /** Collapse whitespace, strip the credential, and cap the length. */
    private fun sanitise(message: String, apiKey: String?): String {
        var clean = message.replace(Regex("\\s+"), " ").trim()
        if (!apiKey.isNullOrBlank()) clean = clean.replace(apiKey, "***")
        return if (clean.length > MAX_HINT_CHARS) clean.take(MAX_HINT_CHARS) + "…" else clean
    }

    private val RETRYABLE = setOf(408, 409, 500, 502, 503, 504)
}
