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

    const val PERMISSION_READ_SMS = "android.permission.READ_SMS"
    const val PERMISSION_RECEIVE_SMS = "android.permission.RECEIVE_SMS"
    const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** Live processing gate used by [com.kanso.spending.sms.SmsBroadcastReceiver]. */
    fun shouldProcessIncoming(smsEnabled: Boolean, hasReceiveSmsPermission: Boolean): Boolean =
        smsEnabled && hasReceiveSmsPermission

    /**
     * True only when it is safe to fire a permission request for the FIRST time.
     * A permission the user has already been asked for is never auto-requested
     * again — refusing a prompt must not lead to a repeat prompt.
     */
    fun shouldRequestPermission(granted: Boolean, previouslyAttempted: Boolean): Boolean =
        !granted && !previouslyAttempted

    /**
     * True when Android will no longer show the system dialog for this
     * permission ("don't ask again", or a prior denial on API 30+). Such a
     * permission must never be re-requested programmatically; the UI should
     * point the user at Android Settings instead.
     */
    fun isPermanentlyDenied(
        granted: Boolean,
        previouslyAttempted: Boolean,
        canShowRequestRationale: Boolean
    ): Boolean = previouslyAttempted && !granted && !canShowRequestRationale

    /**
     * The next optional permission the first-launch setup chain should request,
     * or null when the chain is complete. RECEIVE_SMS follows the user's
     * explicit tap; POST_NOTIFICATIONS is optional and only on API 33+. Each
     * permission is requested at most once (guarded by its attempted flag), so a
     * denial is never re-prompted automatically.
     */
    fun nextSetupPermission(
        notificationsSupported: Boolean,
        receiveGranted: Boolean,
        receiveAttempted: Boolean,
        notificationsGranted: Boolean,
        notificationsAttempted: Boolean
    ): String? {
        if (shouldRequestPermission(receiveGranted, receiveAttempted)) {
            return PERMISSION_RECEIVE_SMS
        }
        if (notificationsSupported &&
            shouldRequestPermission(notificationsGranted, notificationsAttempted)
        ) {
            return PERMISSION_POST_NOTIFICATIONS
        }
        return null
    }

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
