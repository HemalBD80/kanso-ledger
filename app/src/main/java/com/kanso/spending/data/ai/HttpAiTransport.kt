package com.kanso.spending.data.ai

import com.kanso.spending.domain.ai.AiConfig
import com.kanso.spending.domain.ai.AiTransport
import com.kanso.spending.domain.ai.AiTransportResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one real network call in the app: an OpenAI-compatible chat completion.
 *
 * Uses [HttpURLConnection] (built into Android) rather than adding a dependency,
 * and is deliberately conservative so a misbehaving provider can never crash the
 * ledger or spin forever:
 *  - connect/read timeouts bound every request;
 *  - 429 and 5xx get a small bounded retry with backoff; other 4xx do not;
 *  - the response body is read up to a hard size cap and truncated beyond it;
 *  - every failure maps to a typed [AiTransportResult] — this never throws.
 *
 * The credential is sent only in the Authorization header, never in the body,
 * never logged, and never returned to the caller.
 */
class HttpAiTransport : AiTransport {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_RETRIES = 2
        const val BACKOFF_BASE_MS = 700L
        const val MAX_REPLY_BYTES = 256 * 1024
    }

    override fun sendChat(config: AiConfig, systemPrompt: String, userMessage: String): AiTransportResult {
        if (!config.isConfigured) return AiTransportResult.NotConfigured

        val body = JSONObject()
            .put("model", config.modelId)
            .put("temperature", 0.0)
            .put("max_tokens", 512)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userMessage))
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        var attempt = 0
        var lastResult: AiTransportResult = AiTransportResult.NetworkError
        while (attempt <= MAX_RETRIES) {
            val result = attemptOnce(config, body)
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

    private fun attemptOnce(config: AiConfig, body: ByteArray): AiTransportResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(config.chatCompletionsUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                // The credential lives here and nowhere else.
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            connection.outputStream.use { it.write(body) }

            when (val code = connection.responseCode) {
                in 200..299 -> readSuccess(connection)
                401, 403 -> AiTransportResult.Unauthorized
                429 -> AiTransportResult.RateLimited
                in RETRYABLE -> AiTransportResult.HttpError(code)
                else -> AiTransportResult.HttpError(code)
            }
        } catch (timeout: java.net.SocketTimeoutException) {
            AiTransportResult.NetworkError
        } catch (io: IOException) {
            AiTransportResult.NetworkError
        } catch (ignored: Exception) {
            // A malformed/unexpected payload must degrade, never crash the app.
            AiTransportResult.Malformed
        } finally {
            connection?.disconnect()
        }
    }

    private fun readSuccess(connection: HttpURLConnection): AiTransportResult {
        val stream = try {
            connection.inputStream
        } catch (e: IOException) {
            return AiTransportResult.Malformed
        }
        val text = stream.use { s ->
            val buffer = ByteArray(8 * 1024)
            val out = StringBuilder()
            var read: Int
            var total = 0
            while (s.read(buffer).also { read = it } != -1) {
                total += read
                // Hard size cap: stop collecting once the cap is passed.
                if (total > MAX_REPLY_BYTES) break
                out.append(String(buffer, 0, read, Charsets.UTF_8))
            }
            out.toString()
        }
        val content = runCatching {
            JSONObject(text).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        }.getOrNull()
        return if (content.isNullOrBlank()) {
            AiTransportResult.Malformed
        } else {
            AiTransportResult.Success(text)
        }
    }

    private val RETRYABLE = setOf(500, 502, 503, 504)
}
