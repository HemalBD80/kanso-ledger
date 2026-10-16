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
}
