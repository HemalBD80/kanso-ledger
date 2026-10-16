package com.kanso.spending.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kanso.spending.R
import com.kanso.spending.data.repository.SmsIngestSummary
import com.kanso.spending.data.repository.SpendingRepository
import com.kanso.spending.domain.CurrencyFormatter
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Local, configurable notifications. Privacy rules that hold here:
 *  - no message content is ever included — counts, categories and amounts the
 *    user already recorded on this device only;
 *  - nothing leaves the device as part of a notification — they carry only
 *    counts, categories and amounts already recorded here, never message text;
 *  - every area is opt-in in settings and each event is watermarked so the
 *    same situation never re-notifies;
 *  - if POST_NOTIFICATIONS is denied, checks degrade to no-ops silently.
 */
object AppNotifier {

    private const val CHANNEL_IMPORT = "kanso_import"
    private const val CHANNEL_ALERTS = "kanso_alerts"
    private const val PREFS = "kanso_notifier"

    fun notifyImport(context: Context, summary: SmsIngestSummary) {
        val detected = summary.recorded + summary.queuedForReview
        if (detected <= 0) return
        val text = if (summary.recorded > 0) {
            "${summary.recorded} saved, ${summary.queuedForReview} waiting for your review."
        } else {
            "${summary.queuedForReview} transaction(s) waiting for your review — nothing is saved until you confirm."
        }
        post(context, CHANNEL_IMPORT, "Smart tracking: $detected new", text, 1001)
    }

    /**
     * One-shot checks called when the app opens: budget threshold, provider
     * reconciliation warnings, and recurring payments coming due. Each area is
     * gated by its own setting and a watermark in private preferences.
     */
    suspend fun checkAndNotify(context: Context, repository: SpendingRepository) {
        val settings = repository.smartSettings.value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (settings.notifyBudgetWarnings) {
            runCatching {
                val month = YearMonth.now()
                val budget = repository.observeMonthlyBudget(month).first()
                val limit = budget?.overallLimitCents ?: 0L
                if (limit > 0L) {
                    val spent = repository.monthSpentCents(month)
                    if (spent >= limit) {
                        val key = "budget_$month"
                        if (prefs.getString(key, null) != "1") {
                            post(
                                context, CHANNEL_ALERTS,
                                "Budget reached for ${month}",
                                "${CurrencyFormatter.formatCents(spent)} spent of a ${CurrencyFormatter.formatCents(limit)} limit — recorded figures only.",
                                2001
                            )
                            prefs.edit().putString(key, "1").apply()
                        }
                    }
                }
            }
        }

        if (settings.notifyReconciliation) {
            runCatching {
                val warnings = repository.accountStatesOnce().filter { state ->
                    state.reconciliation?.isInAgreement == false
                }
                if (warnings.isNotEmpty()) {
                    val key = "reconcile_${LocalDate.now()}"
                    if (prefs.getString(key, null) != "1") {
                        val detail = warnings.take(3).joinToString(", ") { state ->
                            val diff = state.reconciliation!!.differenceCents
                            "${state.name} ${CurrencyFormatter.formatCents(diff, showPlusForPositive = true)}"
                        }
                        post(
                            context, CHANNEL_ALERTS,
                            "${warnings.size} account(s) differ from provider reports",
                            "$detail — your ledger balance is unchanged until you reconcile.",
                            2002
                        )
                        prefs.edit().putString(key, "1").apply()
                    }
                }
            }
        }

        if (settings.notifyRecurringDue) {
            runCatching {
                val endOfToday = LocalDate.now().plusDays(1)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val due = repository.snapshotRecurringRules().filter {
                    it.isActive && it.nextDueMillis in 1 until endOfToday
                }
                val newlyDue = due.filter { rule ->
                    prefs.getString("recurring_${rule.id}_${rule.nextDueMillis}", null) != "1"
                }
                if (newlyDue.isNotEmpty()) {
                    post(
                        context, CHANNEL_ALERTS,
                        "Recurring payment${if (newlyDue.size == 1) "" else "s"} due",
                        newlyDue.joinToString(", ") {
                            "${it.title} (${CurrencyFormatter.formatCents(it.amountCents)})"
                        } + " — a forecast until you record it.",
                        2003
                    )
                    newlyDue.forEach { rule ->
                        prefs.edit()
                            .putString("recurring_${rule.id}_${rule.nextDueMillis}", "1")
                            .apply()
                    }
                }
            }
        }
    }

    private fun post(
        context: Context,
        channelId: String,
        title: String,
        text: String,
        notificationId: Int
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_kanso)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_IMPORT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_IMPORT,
                    "Detected transactions",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "New transactions detected from your own messages (counts only)."
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ALERTS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Ledger alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Budget thresholds, reconciliation warnings and due recurring payments."
                }
            )
        }
    }
}
