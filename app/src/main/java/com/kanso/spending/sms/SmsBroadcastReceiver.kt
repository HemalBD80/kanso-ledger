package com.kanso.spending.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import com.kanso.spending.KansoApplication
import com.kanso.spending.domain.sms.RawInboundSms
import com.kanso.spending.notifications.AppNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Live detection of incoming financial messages.
 *
 * Contract:
 *  - runs ONLY while the user has enabled Smart SMS Tracking (checked first,
 *    so an unconfigured device never touches messages);
 *  - parsing happens on a background dispatcher via [goAsync]; message bodies
 *    live in memory for the duration of the parse and are never logged,
 *    stored, backed up, or transmitted;
 *  - deduplication happens in the repository, so redelivered broadcasts or a
 *    simultaneously running historical import can never double-record.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SMS_RECEIVED_ACTION) return

        val app = context.applicationContext as? KansoApplication ?: return
        val repository = app.repository
        if (!repository.smartSettings.value.smsEnabled) return

        val messages = extractMessages(intent)
        if (messages.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val summary = repository.ingestSmsBatch(
                    messages,
                    repository.smartSettings.value.smsImportMode
                )
                val detected = summary.recorded + summary.queuedForReview
                if (detected > 0 && repository.smartSettings.value.notifyNewDetections) {
                    AppNotifier.notifyImport(context.applicationContext, summary)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun extractMessages(intent: Intent): List<RawInboundSms> {
        val extras = intent.extras ?: return emptyList()
        val pdus = extras.get("pdus") as? Array<*> ?: return emptyList()
        val format = extras.getString("format")
        val result = mutableListOf<RawInboundSms>()

        for (pdu in pdus) {
            if (pdu !is ByteArray) continue
            val message = if (format != null) {
                SmsMessage.createFromPdu(pdu, format)
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu)
            } ?: continue
            val body = message.messageBody
            if (body.isNullOrBlank()) continue
            result += RawInboundSms(
                sender = message.displayOriginatingAddress ?: "Unknown",
                body = body,
                receivedAtMillis = message.timestampMillis
            )
        }
        return result
    }

    private companion object {
        const val SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED"
    }
}
