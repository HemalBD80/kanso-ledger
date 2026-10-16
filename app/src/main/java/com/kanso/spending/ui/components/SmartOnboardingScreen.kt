package com.kanso.spending.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kanso.spending.domain.sms.SmsAccessPolicy
import com.kanso.spending.ui.SmartLedgerActions

/**
 * First-launch Smart Setup screen (shown until the choice is persisted):
 *
 *  1. explains Smart SMS Tracking and its privacy implications, then offers
 *     exactly two choices — enable it, or continue without SMS access;
 *  2. if enabled, requests READ_SMS (history), RECEIVE_SMS (incoming) and,
 *     on API 33+, POST_NOTIFICATIONS — always from an explicit tap, never
 *     silently — and treats denial, permanent denial and later revocation as
 *     normal outcomes with documented fallbacks (paste/share + manual);
 *  3. then offers the initial history import with preview, duplicate
 *     inspection, and the explicit auto-record vs review-first choice.
 *
 * Manual tracking is never gated on any of this: choosing "continue without
 * SMS access" lands on the same fully working ledger.
 */
@Composable
fun SmartOnboardingScreen(
    currencySymbol: String,
    actions: SmartLedgerActions,
    onFinish: () -> Unit
) {
    val colors = com.kanso.spending.ui.theme.LocalKansoColors.current
    val context = LocalContext.current
    val activity = context as? Activity

    var step by rememberSaveable { mutableStateOf("welcome") }
    var readAttempted by rememberSaveable { mutableStateOf(false) }
    var receiveAttempted by rememberSaveable { mutableStateOf(false) }
    var notifAttempted by rememberSaveable { mutableStateOf(false) }
    var readGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var receiveGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var notifGranted by remember { mutableStateOf(false) }
    // True while the READ→RECEIVE→NOTIF chain is running, so the ON_RESUME
    // refresh (e.g. returning from Settings) never interrupts a live dialog.
    var chainInFlight by remember { mutableStateOf(false) }
    var accessNote by remember { mutableStateOf<String?>(null) }

    fun hasRead() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED
    fun hasReceive() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED

    fun permanentlyDenied(): Boolean =
        readAttempted && !readGranted &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS) == false

    /** After the permission chain: enable only when access actually exists. */
    fun settleAccess() {
        chainInFlight = false
        readGranted = hasRead()
        receiveGranted = hasReceive()
        val anyGranted = readGranted || receiveGranted
        actions.onSetSmsEnabled(anyGranted)
        if (readGranted) {
            accessNote = null
            step = "import"
        } else {
            accessNote = SmsAccessPolicy.denialMessage(permanentlyDenied()) +
                if (receiveGranted) {
                    " (Incoming detection is available, but history import needs Read SMS — " +
                        "you can paste messages instead.)"
                } else ""
        }
    }

    // Returning from Android Settings: refresh the real permission state so the
    // UI never shows a stale grant/denial, and re-evaluate the access step — but
    // never while a permission chain is still running (resume can race a live
    // dialog's result dispatch).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                readGranted = hasRead()
                receiveGranted = hasReceive()
                if (step == "access" && !chainInFlight && (readAttempted || receiveAttempted)) {
                    settleAccess()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Strictly sequential, lifecycle-safe permission requests. Each launch runs
    // from a LaunchedEffect (next frame, outside result dispatch) so the
    // ActivityResultRegistry is never re-entered — the fix for the v2.1.0 crash.
    // A deferred holder avoids a self-reference inside the result callback.
    var requesterHolder: SequentialPermissionRequester? = null
    val permissionRequester = rememberSequentialPermissionRequester { permission, granted ->
        when (permission) {
            Manifest.permission.READ_SMS -> {
                readAttempted = true
                readGranted = granted
            }
            Manifest.permission.RECEIVE_SMS -> {
                receiveAttempted = true
                receiveGranted = granted
            }
            else -> {
                notifGranted = granted
                notifAttempted = true
            }
        }
        val next = SmsAccessPolicy.nextSetupPermission(
            notificationsSupported = Build.VERSION.SDK_INT >= 33,
            receiveGranted = hasReceive(),
            receiveAttempted = receiveAttempted,
            notificationsGranted = notifGranted,
            notificationsAttempted = notifAttempted
        )
        if (next != null) {
            requesterHolder?.request(next)
        } else {
            settleAccess()
        }
    }
    requesterHolder = permissionRequester

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.surface,
        contentColor = colors.inkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            when (step) {
                // ------------------------------------------------------ welcome
                "welcome" -> {
                    Text("KANSO LEDGER · PRIVATE BY DESIGN", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
                    Spacer(Modifier.height(6.dp))
                    Text("Welcome", style = MaterialTheme.typography.displaySmall, color = colors.inkPrimary)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Smart SMS Tracking (optional)",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    PrivacyBullet("Detects bKash, Nagad, Rocket, bank and card transactions from your messages and records them for you.")
                    PrivacyBullet("Parsing is deterministic and runs entirely on this device. Message bodies are never stored, logged, backed up, or sent anywhere — only fingerprints and extracted fields are kept.")
                    PrivacyBullet("Ambiguous messages always wait in a review queue. The app never guesses a missing amount, type, account, or balance.")
                    PrivacyBullet("Manual tracking works fully without SMS access, and you can turn this off at any time without losing records.")
                    PrivacyBullet("Balances are never inferred from partial message history — you enter or reconcile opening balances yourself.")
                    PrivacyBullet("Android permission note: Read SMS is a sensitive permission; Google Play requires a policy declaration for it. This app is distributed as an offline APK.")
                    Spacer(Modifier.height(22.dp))

                    Button(
                        onClick = { step = "access" },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("ENABLE SMART SMS TRACKING", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            actions.onSetSmsEnabled(false)
                            onFinish()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.hairlineBorder)
                    ) {
                        Text("CONTINUE WITHOUT SMS ACCESS", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "No permissions are requested until you tap Enable. Declining changes nothing about " +
                            "how the ledger itself works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted
                    )
                }

                // ------------------------------------------------------- access
                "access" -> {
                    Text("STEP 1 OF 2 · ACCESS", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
                    Spacer(Modifier.height(6.dp))
                    Text("Two permissions, one tap each", style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
                    Spacer(Modifier.height(12.dp))
                    PermissionRow(
                        title = "Read SMS",
                        detail = "Import your existing transaction messages when you choose a date range. " +
                            "Nothing is read until you run a preview or import."
                    )
                    PermissionRow(
                        title = "Receive SMS",
                        detail = "Detect new transaction messages while Smart Tracking is on, so they can be " +
                            "recorded (or queued, in review mode) automatically."
                    )
                    PermissionRow(
                        title = "Notifications (optional)",
                        detail = "Tells you when a transaction was recorded or is waiting for review. " +
                            "Declining this only hides notifications."
                    )
                    Spacer(Modifier.height(14.dp))

                    if (accessNote != null) {
                        Text(
                            accessNote!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warningAccent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }

                    Button(
                        onClick = {
                            // Explicit user action — the only trigger for a
                            // permission request. Requests are queued and
                            // launched one frame later, never from inside a
                            // result callback (the v2.1.0 crash).
                            chainInFlight = true
                            permissionRequester.request(Manifest.permission.READ_SMS)
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("GRANT SMS ACCESS", style = MaterialTheme.typography.labelLarge)
                    }

                    if (permanentlyDenied()) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { openAppSettings() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, colors.hairlineBorder)
                        ) {
                            Text("OPEN ANDROID SETTINGS", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    if (readAttempted && !readGranted) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                // User may have flipped it in Android Settings meanwhile.
                                settleAccess()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, colors.hairlineBorder)
                        ) {
                            Text("I CHANGED IT IN SETTINGS — CONTINUE", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            actions.onSetSmsEnabled(false)
                            onFinish()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.hairlineBorder)
                    ) {
                        Text("CONTINUE WITHOUT SMS ACCESS", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Denied, blocked, or later revoked? Nothing breaks: paste/share import and manual " +
                            "tracking keep working, and your records are never touched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted
                    )
                }

                // ------------------------------------------------------- import
                else -> {
                    Text("STEP 2 OF 2 · HISTORY", style = MaterialTheme.typography.labelSmall, color = colors.positiveAccent)
                    Spacer(Modifier.height(6.dp))
                    Text("Import your history (optional)", style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.elevatedSurface,
                        border = BorderStroke(1.dp, colors.hairlineBorder)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "BEFORE YOU IMPORT",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.warningAccent,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "• Preview first: you see confident, needs-review, duplicate and ignored counts " +
                                    "before a single record is created.\n" +
                                    "• Choose how to import: record the confident ones now, or send everything " +
                                    "to review first.\n" +
                                    "• Duplicates are matched by provider reference or full fingerprint — never " +
                                    "by amount alone — so re-running an import is always safe.\n" +
                                    "• Your messages may be incomplete, so no opening balance, current balance, " +
                                    "or account history is ever assumed from them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    HistoryImportSection(actions = actions, currencySymbol = currencySymbol)

                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("DONE — OPEN THE DASHBOARD", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You can import, review, undo, or turn Smart Tracking on and off at any time " +
                            "from the Smart SMS sheet in the dashboard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyBullet(text: String) {
    val colors = com.kanso.spending.ui.theme.LocalKansoColors.current
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("—  ", style = MaterialTheme.typography.bodySmall, color = colors.positiveAccent)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PermissionRow(title: String, detail: String) {
    val colors = com.kanso.spending.ui.theme.LocalKansoColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.elevatedSurface,
        border = BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
    }
}
