package com.kanso.spending.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permission-denied and permission-revoked behaviour, as a pure policy: the
 * same decisions the broadcast receiver and the UI apply. Denial must never be
 * worked around, and revocation must pause processing without touching any
 * recorded financial data. Manual tracking is not part of this gate at all.
 */
class SmsAccessPolicyTest {

    @Test
    fun incomingMessagesAreProcessedOnlyWhenEnabledAndPermitted() {
        assertTrue(SmsAccessPolicy.shouldProcessIncoming(smsEnabled = true, hasReceiveSmsPermission = true))
        // Revoked mid-session: feature on, grant gone → never touch messages.
        assertFalse(SmsAccessPolicy.shouldProcessIncoming(smsEnabled = true, hasReceiveSmsPermission = false))
        // Feature off: grant is irrelevant.
        assertFalse(SmsAccessPolicy.shouldProcessIncoming(smsEnabled = false, hasReceiveSmsPermission = true))
        assertFalse(SmsAccessPolicy.shouldProcessIncoming(smsEnabled = false, hasReceiveSmsPermission = false))
    }

    @Test
    fun historyReadRequiresAnActiveGrant() {
        assertTrue(SmsAccessPolicy.canReadHistory(hasReadSmsPermission = true))
        assertFalse(SmsAccessPolicy.canReadHistory(hasReadSmsPermission = false))
    }

    @Test
    fun denialMessagesPointToFallbacksAndDifferFromBlockedState() {
        val declined = SmsAccessPolicy.denialMessage(permanentlyDenied = false)
        val blocked = SmsAccessPolicy.denialMessage(permanentlyDenied = true)

        assertTrue("manual fallback promised", declined.contains("manually"))
        assertTrue("blocked state names Settings", blocked.contains("Settings"))
        assertTrue("blocked state keeps manual path", blocked.contains("manual"))
        assertNotEquals("distinct guidance for each state", declined, blocked)
    }

    @Test
    fun revocationMessagePausesTrackingButPromisesRecordsAreUntouched() {
        val message = SmsAccessPolicy.revocationMessage()
        assertTrue("says tracking paused", message.contains("paused"))
        assertTrue("promises records safe", message.contains("untouched"))
        assertTrue("points to Settings to resume", message.contains("Settings"))
        assertTrue("never suggests deleting data", !message.contains("delete"))
    }

    @Test
    fun denialNeverEnablesProcessingImplicitly() {
        // Denied read + denied receive → both gates closed; nothing sneaks through.
        val enabledButDenied = SmsAccessPolicy.shouldProcessIncoming(true, hasReceiveSmsPermission = false)
        val canRead = SmsAccessPolicy.canReadHistory(hasReadSmsPermission = false)
        assertFalse(enabledButDenied)
        assertFalse(canRead)
        assertEquals(false, enabledButDenied && canRead)
    }

    // ---------------------------------------------------------- request policy

    @Test
    fun aPermissionIsRequestedOnlyTheFirstTime() {
        // Never granted, never asked → safe to ask.
        assertTrue(SmsAccessPolicy.shouldRequestPermission(granted = false, previouslyAttempted = false))
        // Already granted → never re-ask.
        assertFalse(SmsAccessPolicy.shouldRequestPermission(granted = true, previouslyAttempted = false))
        // Previously attempted → never re-ask automatically (denial is respected).
        assertFalse(SmsAccessPolicy.shouldRequestPermission(granted = false, previouslyAttempted = true))
        assertFalse(SmsAccessPolicy.shouldRequestPermission(granted = true, previouslyAttempted = true))
    }

    @Test
    fun permanentDenialIsDetectedOnlyAfterAFailedAttempt() {
        // Attempted, not granted, and Android will no longer show the dialog.
        assertTrue(
            SmsAccessPolicy.isPermanentlyDenied(
                granted = false, previouslyAttempted = true, canShowRequestRationale = false
            )
        )
        // Attempted and denied, but the dialog can still be shown → not permanent.
        assertFalse(
            SmsAccessPolicy.isPermanentlyDenied(
                granted = false, previouslyAttempted = true, canShowRequestRationale = true
            )
        )
        // Granted → never permanent, regardless of rationale.
        assertFalse(
            SmsAccessPolicy.isPermanentlyDenied(
                granted = true, previouslyAttempted = true, canShowRequestRationale = false
            )
        )
        // Never attempted → cannot be permanently denied.
        assertFalse(
            SmsAccessPolicy.isPermanentlyDenied(
                granted = false, previouslyAttempted = false, canShowRequestRationale = false
            )
        )
    }

    // ------------------------------------------------------------- setup chain

    @Test
    fun setupChainAsksReceiveThenNotificationsThenStops() {
        // Nothing attempted yet → RECEIVE is next.
        assertEquals(
            SmsAccessPolicy.PERMISSION_RECEIVE_SMS,
            SmsAccessPolicy.nextSetupPermission(
                notificationsSupported = true,
                receiveGranted = false, receiveAttempted = false,
                notificationsGranted = false, notificationsAttempted = false
            )
        )
        // RECEIVE done → optional notifications next (API 33+).
        assertEquals(
            SmsAccessPolicy.PERMISSION_POST_NOTIFICATIONS,
            SmsAccessPolicy.nextSetupPermission(
                notificationsSupported = true,
                receiveGranted = true, receiveAttempted = true,
                notificationsGranted = false, notificationsAttempted = false
            )
        )
        // RECEIVE denied once → never re-asked; go straight to notifications.
        assertEquals(
            SmsAccessPolicy.PERMISSION_POST_NOTIFICATIONS,
            SmsAccessPolicy.nextSetupPermission(
                notificationsSupported = true,
                receiveGranted = false, receiveAttempted = true,
                notificationsGranted = false, notificationsAttempted = false
            )
        )
        // Everything settled → chain complete (null).
        assertEquals(
            null,
            SmsAccessPolicy.nextSetupPermission(
                notificationsSupported = true,
                receiveGranted = true, receiveAttempted = true,
                notificationsGranted = true, notificationsAttempted = true
            )
        )
    }

    @Test
    fun setupChainSkipsNotificationsOnOlderAndroidAndAfterDenial() {
        // Below API 33: no notification step at all.
        assertEquals(
            null,
            SmsAccessPolicy.nextSetupPermission(
                notificationsSupported = false,
                receiveGranted = true, receiveAttempted = true,
                notificationsGranted = false, notificationsAttempted = false
            )
        )
        // Notifications previously declined → not re-asked; chain ends.
        assertEquals(
            null,
            SmsAccessPolicy.nextSetupPermission(
                notificationsSupported = true,
                receiveGranted = true, receiveAttempted = true,
                notificationsGranted = false, notificationsAttempted = true
            )
        )
    }

    @Test
    fun permissionConstantsMatchAndroidManifestNames() {
        assertEquals("android.permission.READ_SMS", SmsAccessPolicy.PERMISSION_READ_SMS)
        assertEquals("android.permission.RECEIVE_SMS", SmsAccessPolicy.PERMISSION_RECEIVE_SMS)
        assertEquals("android.permission.POST_NOTIFICATIONS", SmsAccessPolicy.PERMISSION_POST_NOTIFICATIONS)
    }
}
