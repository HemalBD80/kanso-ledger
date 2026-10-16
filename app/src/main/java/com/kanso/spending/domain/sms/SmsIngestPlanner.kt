package com.kanso.spending.domain.sms

/**
 * How a batch of recognised SMS transactions is committed to the ledger.
 *
 * Neither mode ever writes an ambiguous or review-flagged message: guessing is
 * never allowed, only the threshold for automatic commits differs.
 */
enum class SmsImportMode {
    /** Every recognised transaction waits in the review queue first. */
    REVIEW,

    /** High-confidence transactions are written immediately; ambiguous ones still wait. */
    AUTO
}

/** One inbound message handed to the planner. The body never leaves this call. */
data class RawInboundSms(
    val sender: String,
    val body: String,
    val receivedAtMillis: Long
)

/** Facts shared by every message in one import batch. */
data class IngestContext(
    /** Fingerprints already stored (sms_meta + ledger rows) — never re-recorded. */
    val knownFingerprints: Set<String>,
    val mode: SmsImportMode,
    val batchId: String,
    val nowMillis: Long,
    /** Lowercased account hint (e.g. "***4512") → account id. */
    val hintToAccountId: Map<String, String> = emptyMap(),
    /** Lowercased account name (e.g. "bkash") → account id, matched on provider. */
    val nameToAccountId: Map<String, String> = emptyMap()
)

/** One detection row the repository must persist (plus optional ledger write). */
data class PlannedDetection(
    val parsed: ParsedSmsTransaction,
    val sender: String,
    val receivedAtMillis: Long,
    /** ReviewStatus name: PENDING or APPROVED (never REJECTED at import time). */
    val reviewStatus: String,
    /** True → materialise the ledger row immediately (AUTO mode, confident). */
    val writeLedger: Boolean,
    /** Hint/name match result; blank → resolve from the payment method at write time. */
    val resolvedAccountId: String
)

/** Everything persisted for one message: idempotency meta plus optional detection. */
data class PlannedSms(
    val fingerprint: String,
    val sender: String,
    val provider: String,
    /** SmsTxType name (informational). */
    val txType: String,
    val receivedAtMillis: Long,
    /** SmsMetaEntity outcome: RECORDED | QUEUED | IGNORED | AMBIGUOUS. */
    val metaOutcome: String,
    /** Null → meta row only (nothing worth reviewing). */
    val detection: PlannedDetection?
)

/** A skipped duplicate in structured form — message content is never included. */
data class DuplicateNotice(
    val provider: String,
    val amountCents: Long?,
    val timestampMillis: Long,
    val referenceId: String?
)

data class IngestPlan(
    val items: List<PlannedSms>,
    val duplicateCount: Int,
    val ignoredCount: Int,
    val batchId: String,
    /** Skipped duplicates (already known fingerprints), for preview/inspection. */
    val duplicateItems: List<DuplicateNotice> = emptyList()
) {
    val pendingCount: Int
        get() = items.count { it.detection?.reviewStatus == "PENDING" }

    val recordedCount: Int
        get() = items.count { it.detection?.writeLedger == true }
}

/**
 * Deterministic, on-device decision layer between [SmsParser] and persistence.
 *
 * Guarantees:
 *  - a known fingerprint is never processed twice (idempotent imports);
 *  - non-financial messages leave no trace at all (privacy by omission);
 *  - balance-only notices become meta rows, and detections only when there is
 *    a provider-reported balance worth reconciling;
 *  - REVIEW mode queues everything financial; AUTO mode writes only what the
 *    parser marked confident — ambiguous input always queues.
 */
object SmsIngestPlanner {

    fun plan(messages: List<RawInboundSms>, context: IngestContext): IngestPlan {
        val items = mutableListOf<PlannedSms>()
        val duplicateItems = mutableListOf<DuplicateNotice>()
        val seen = HashSet<String>(context.knownFingerprints)
        var duplicates = 0
        var ignored = 0

        for (message in messages) {
            when (val result = SmsParser.parse(message.sender, message.body, message.receivedAtMillis)) {
                is SmsParseResult.NotFinancial -> ignored++

                is SmsParseResult.Ambiguous -> {
                    if (!seen.add(result.fingerprint)) {
                        duplicates++
                        duplicateItems += DuplicateNotice(
                            provider = result.provider,
                            amountCents = null,
                            timestampMillis = message.receivedAtMillis,
                            referenceId = null
                        )
                        continue
                    }
                    val parsed = ParsedSmsTransaction(
                        provider = result.provider,
                        type = SmsTxType.UNKNOWN,
                        action = LedgerAction.REVIEW_ONLY,
                        amountCents = null,
                        feeCents = 0L,
                        timestampMillis = message.receivedAtMillis,
                        merchantOrCounterparty = "",
                        referenceId = null,
                        accountHint = null,
                        reportedBalanceCents = null,
                        reportedDirection = null,
                        confidence = 0,
                        needsReview = true,
                        reviewReason = result.reason,
                        fingerprint = result.fingerprint
                    )
                    items += PlannedSms(
                        fingerprint = result.fingerprint,
                        sender = message.sender,
                        provider = result.provider,
                        txType = SmsTxType.UNKNOWN.name,
                        receivedAtMillis = message.receivedAtMillis,
                        metaOutcome = "AMBIGUOUS",
                        detection = PlannedDetection(
                            parsed = parsed,
                            sender = message.sender,
                            receivedAtMillis = message.receivedAtMillis,
                            reviewStatus = "PENDING",
                            writeLedger = false,
                            resolvedAccountId = ""
                        )
                    )
                }

                is SmsParseResult.Financial -> {
                    val tx = result.transaction
                    if (!seen.add(tx.fingerprint)) {
                        duplicates++
                        duplicateItems += DuplicateNotice(
                            provider = tx.provider,
                            amountCents = tx.amountCents,
                            timestampMillis = tx.timestampMillis,
                            referenceId = tx.referenceId
                        )
                        continue
                    }
                    items += planFinancial(message, tx, context)
                }
            }
        }

        return IngestPlan(
            items = items,
            duplicateCount = duplicates,
            ignoredCount = ignored,
            batchId = context.batchId,
            duplicateItems = duplicateItems
        )
    }

    private fun planFinancial(
        message: RawInboundSms,
        tx: ParsedSmsTransaction,
        context: IngestContext
    ): PlannedSms {
        val resolved = resolveAccountId(tx, context)

        // Balance-only notice: meta row keeps idempotency; a detection exists
        // only when a provider balance can be reconciled against an account.
        if (tx.action == LedgerAction.IGNORE) {
            val hasBalance = tx.reportedBalanceCents != null
            val status = when {
                !hasBalance -> null                    // meta row only
                resolved.isNotEmpty() -> "APPROVED"    // already attachable
                else -> "PENDING"                      // ask which account this belongs to
            }
            return PlannedSms(
                fingerprint = tx.fingerprint,
                sender = message.sender,
                provider = tx.provider,
                txType = tx.type.name,
                receivedAtMillis = message.receivedAtMillis,
                metaOutcome = if (status == "PENDING") "QUEUED" else "IGNORED",
                detection = status?.let {
                    PlannedDetection(
                        parsed = tx,
                        sender = message.sender,
                        receivedAtMillis = message.receivedAtMillis,
                        reviewStatus = it,
                        writeLedger = false,
                        resolvedAccountId = resolved
                    )
                }
            )
        }

        val (status, write) = when {
            context.mode == SmsImportMode.REVIEW -> "PENDING" to false
            tx.needsReview -> "PENDING" to false
            tx.action == LedgerAction.REVIEW_ONLY -> "PENDING" to false
            tx.amountCents == null && tx.feeCents == 0L -> "PENDING" to false
            else -> "APPROVED" to true
        }

        return PlannedSms(
            fingerprint = tx.fingerprint,
            sender = message.sender,
            provider = tx.provider,
            txType = tx.type.name,
            receivedAtMillis = message.receivedAtMillis,
            metaOutcome = if (write) "RECORDED" else "QUEUED",
            detection = PlannedDetection(
                parsed = tx,
                sender = message.sender,
                receivedAtMillis = message.receivedAtMillis,
                reviewStatus = status,
                writeLedger = write,
                resolvedAccountId = resolved
            )
        )
    }

    /**
     * Match a message to an account: masked account hint first (digits only, so
     * "***4512" equals a note of "A/C ***4512"), then the account whose name
     * equals the provider ("bKash"). Blank when nothing genuinely matches —
     * callers then fall back to the payment-method mapping, never a guess.
     */
    fun resolveAccountId(tx: ParsedSmsTransaction, context: IngestContext): String {
        val hintDigits = tx.accountHint?.let(::digits).orEmpty()
        if (hintDigits.isNotEmpty()) {
            val hit = context.hintToAccountId.entries.firstOrNull { (note, _) ->
                digits(note) == hintDigits
            }
            if (hit != null) return hit.value
        }
        val providerLower = tx.provider.trim().lowercase()
        val providerHit = context.nameToAccountId.entries
            .firstOrNull { (name, _) -> name.trim().lowercase() == providerLower }
        if (providerHit != null) return providerHit.value
        return ""
    }

    private fun digits(value: String): String = value.filter { it.isDigit() }
}
