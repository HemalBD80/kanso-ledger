package com.kanso.spending.sms

import android.content.Context
import android.net.Uri
import com.kanso.spending.domain.sms.RawInboundSms

/**
 * Date-filtered read of the on-device SMS inbox for historical import.
 *
 * Requires READ_SMS, which the UI requests only after the user understands
 * why. Rows are converted to [RawInboundSms] immediately and handed straight
 * to the ingest pipeline: nothing from this query is logged, cached, or
 * written to disk — only the parser's structured output and fingerprints ever
 * reach the database.
 */
object SmsHistoryReader {

    private const val MAX_MESSAGES = 500

    fun read(context: Context, fromMillis: Long, toMillis: Long): List<RawInboundSms> {
        val projection = arrayOf("_id", "address", "body", "date")
        val selection = "date >= ? AND date <= ?"
        val args = arrayOf(fromMillis.toString(), toMillis.toString())
        val result = mutableListOf<RawInboundSms>()

        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                projection,
                selection,
                args,
                "date DESC LIMIT $MAX_MESSAGES"
            )?.use { cursor ->
                val addressIndex = cursor.getColumnIndex("address")
                val bodyIndex = cursor.getColumnIndex("body")
                val dateIndex = cursor.getColumnIndex("date")
                while (cursor.moveToNext()) {
                    val body = if (bodyIndex >= 0) cursor.getString(bodyIndex) else null
                    if (body.isNullOrBlank()) continue
                    val receivedAt = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                    result += RawInboundSms(
                        sender = if (addressIndex >= 0) cursor.getString(addressIndex) ?: "Unknown"
                        else "Unknown",
                        body = body,
                        receivedAtMillis = receivedAt
                    )
                }
            }
        } catch (security: SecurityException) {
            throw IllegalStateException("SMS permission is not granted", security)
        }
        return result
    }
}
