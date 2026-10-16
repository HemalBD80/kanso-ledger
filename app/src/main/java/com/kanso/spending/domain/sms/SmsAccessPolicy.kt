package com.kanso.spending.domain.sms

/**
 * Pure decision layer for SMS access, shared by the broadcast receiver and
 * the UI so permission-denied and permission-revoked behaviour is explicit,
 * testable on the JVM, and never silent.
 *
 * Rules:
 *  - incoming messages are processed only when the feature switch is ON *and*
 *    RECEIVE_SMS is actually granted (defence in depth — the system already
 *    withholds broadcasts otherwise);
 *  - history import is attempted only when READ_SMS is granted; denial is an
 *    expected outcome with a documented fallback (paste/share import and the
 *    whole manual tracker keep working);
 *  - denial is never worked around, elevated, or retried in a loop.
 */
object SmsAccessPolicy {

    /** Live processing gate used by [com.kanso.spending.sms.SmsBroadcastReceiver]. */
    fun shouldProcessIncoming(smsEnabled: Boolean, hasReceiveSmsPermission: Boolean): Boolean =
        smsEnabled && hasReceiveSmsPermission

    /** History reads happen only with an explicit, still-valid READ_SMS grant. */
    fun canReadHistory(hasReadSmsPermission: Boolean): Boolean = hasReadSmsPermission

    /**
     * User-facing text after a READ_SMS denial.
     * [permanentlyDenied] = Android will no longer show the system dialog
     * ("don't ask again" state or a prior denial on API 30+).
     */
    fun denialMessage(permanentlyDenied: Boolean): String = if (permanentlyDenied) {
        "SMS access is blocked for this app. You can allow it later in Android Settings " +
            "→ Apps → Kanso Ledger → Permissions. Until then, use paste/share import or " +
            "manual tracking — nothing is lost."
    } else {
        "SMS access declined. Smart Tracking stays quiet; paste or share a message any " +
            "time, or track manually — the ledger works fully without SMS access."
    }

    /** Shown when the feature is ON but the grant disappeared (revoked in Settings). */
    fun revocationMessage(): String =
        "SMS permission was revoked. Live tracking is paused — no messages are read. " +
            "Your recorded transactions, imports and settings are untouched. Re-grant " +
            "SMS permission in Android Settings to resume."
}
