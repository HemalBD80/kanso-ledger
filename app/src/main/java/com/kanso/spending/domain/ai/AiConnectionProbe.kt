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

        /**
         * Any other non-2xx HTTP status. [providerHint] is a short, sanitised
         * message from the provider (no key, no message text).
         */
        data class HttpError(val code: Int, val providerHint: String? = null) : Result()

        /** Timeout, DNS or connection failure — could not reach the endpoint. */
        data object NetworkError : Result()

        /** A 2xx reply whose message field was empty. */
        data class EmptyReply(val reason: String? = null) : Result()

        /** A reply that arrived but was not a usable completion. */
        data class Malformed(val reason: String? = null) : Result()

        /** The provider reported an error inside the body. */
        data class ProviderError(val message: String?) : Result()
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
            is AiTransportResult.HttpError -> Result.HttpError(outcome.code, outcome.providerMessage)
            AiTransportResult.NetworkError -> Result.NetworkError
            is AiTransportResult.EmptyReply -> Result.EmptyReply(outcome.reason)
            is AiTransportResult.Malformed -> Result.Malformed(outcome.reason)
            is AiTransportResult.ProviderError -> Result.ProviderError(outcome.message)
        }
    }

    /** Human-readable, credential-free message for a [Result]. */
    fun describe(result: Result): String = when (result) {
        is Result.Success ->
            "Connected. Model '${result.modelId}' replied in ${result.latencyMs} ms."
        Result.NotConfigured ->
            "Not configured. Add your API key, base URL and model ID first."
        Result.Unauthorized ->
            "The provider rejected the API key (401/403). Check the key."
        Result.RateLimited ->
            "The provider is rate-limiting requests (429). Try again shortly."
        is Result.HttpError ->
            "The provider returned HTTP ${result.code}." + hintSuffix(result.providerHint) +
                " If this is 404/405, check the base URL and the API format."
        Result.NetworkError ->
            "Could not reach the provider. Check the base URL and your connection."
        is Result.EmptyReply ->
            "The provider replied but returned nothing" + reasonSuffix(result.reason) +
                ". The model may have hit its token limit."
        is Result.Malformed ->
            "The provider replied, but not in a recognised format" + reasonSuffix(result.reason) +
                ". Try a different API format."
        is Result.ProviderError ->
            "The provider reported an error" + reasonSuffix(result.message) + "."
    }

    private fun reasonSuffix(value: String?): String =
        value?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    private fun hintSuffix(hint: String?): String =
        hint?.takeIf { it.isNotBlank() }?.let { " Provider said: $it" }.orEmpty()

    // --------------------------------------------------------------- classification

    /**
     * Outcome of the stronger [classify] diagnostic.
     */
    sealed class ClassificationResult {
        /** The model returned a valid, structured classification. */
        data class Success(
            val txType: String,
            val amountCents: Long?,
            val direction: String?
        ) : ClassificationResult()

        /** The model read the (clearly financial) fixture as non-financial. */
        data class NotFinancial(val reason: String) : ClassificationResult()

        /** No usable classification — carries the failure category. */
        data class Failed(
            val reason: String,
            val kind: AiSmsInterpreter.FailureKind
        ) : ClassificationResult()

        /** No key/base/model configured — nothing was sent. */
        data object NotConfigured : ClassificationResult()
    }

    /**
     * A fabricated, clearly-labelled bank SMS used ONLY by [classify]. It is
     * explicitly marked as a self-test and contains a placeholder account and
     * reference — no real SMS, account number or ledger value is ever sent.
     */
    const val FIXTURE_SENDER = "KANSO-SELFTEST"
    const val FIXTURE_BODY =
        "KANSO SELF-TEST (fictional message; not a real transaction). " +
            "Your A/C ***0000 has been debited by BDT 1,250.00 on 01/01/2026. " +
            "Ref: TESTREF0001. Available Balance BDT 5,000.00."
    private const val FIXTURE_TIME_MILLIS = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    /**
     * Stronger diagnostic than [probe]: run the fixed fictional message through
     * the SAME interpret → parse → validate pipeline used for real imports, and
     * report whether a valid structured classification came back.
     *
     * A trivial "OK" ping can succeed while real classification does not — the
     * model ID may be wrong for the provider, the model may ignore the JSON
     * contract, or the reply may be truncated/unparseable. This turns the
     * "connection test passes but nothing imports" symptom into a specific,
     * actionable message. Never throws; never carries the credential.
     */
    fun classify(transport: AiTransport, config: AiConfig): ClassificationResult {
        if (!config.isConfigured) return ClassificationResult.NotConfigured
        val outcome = AiSmsInterpreter(transport)
            .interpret(config, FIXTURE_SENDER, FIXTURE_BODY, FIXTURE_TIME_MILLIS)
        return when (outcome) {
            is AiSmsInterpreter.Outcome.Extracted -> ClassificationResult.Success(
                txType = outcome.transaction.type.name,
                amountCents = outcome.transaction.amountCents,
                direction = outcome.transaction.reportedDirection
            )
            is AiSmsInterpreter.Outcome.NotFinancial ->
                ClassificationResult.NotFinancial(outcome.reason)
            is AiSmsInterpreter.Outcome.Failed ->
                ClassificationResult.Failed(outcome.reason, outcome.kind)
        }
    }

    /** Human-readable, credential-free message for a [ClassificationResult]. */
    fun describe(result: ClassificationResult): String = when (result) {
        ClassificationResult.NotConfigured ->
            "Not configured. Add your API key first."
        is ClassificationResult.Success ->
            "Classification OK: the model read the test message as ${result.txType}" +
                if (result.amountCents != null) " with an amount." else " with no amount."
        is ClassificationResult.NotFinancial ->
            "The model read the test bank SMS as non-financial, so it cannot classify this " +
                "domain — try a capable model ID. Reason: ${result.reason}"
        is ClassificationResult.Failed ->
            "Classification failed: ${result.reason} (${result.kind.name})."
    }
}
