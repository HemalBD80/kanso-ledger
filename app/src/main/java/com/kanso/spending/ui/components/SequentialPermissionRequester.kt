package com.kanso.spending.ui.components

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.kanso.spending.domain.sms.SmsAccessPolicy

/**
 * A lifecycle-safe, strictly sequential runtime-permission requester.
 *
 * WHY THIS EXISTS (root cause of the v2.1.0 onboarding crash):
 * An [androidx.activity.result.ActivityResultCallback] runs *inside*
 * [androidx.activity.result.ActivityResultRegistry.dispatchResult], while the
 * registry is still mid-dispatch. Calling `launcher.launch(...)` from within
 * that callback is therefore a re-entrant call into the registry. On Android
 * 12–14 this is a documented failure mode that either throws out of `launch()`
 * (the app closes) or silently drops the follow-up request (the flow stalls).
 *
 * The v2.1.0 code chained three launches exactly that way:
 *  - `readLauncher`'s callback synchronously called `receiveLauncher.launch(...)`;
 *  - `receiveLauncher`'s callback synchronously called `notifLauncher.launch(...)`;
 *  - the Smart SMS sheet fired two overlapping launches from one click handler.
 *
 * HOW THIS FIXES IT:
 * Callers never touch a launcher directly. They only *request* a permission,
 * which records it in state. A single [LaunchedEffect] observes that state and
 * performs the launch on the next frame — outside result dispatch, so the
 * registry is never re-entered. Only one request is ever in flight ([inFlight]),
 * so overlapping launches are impossible. Every launch is wrapped so a residual
 * platform quirk degrades to "denied" instead of closing the app.
 *
 * Each permission is requested at most once (see [SmsAccessPolicy.shouldRequestPermission]);
 * a denial is never re-prompted automatically.
 */
class SequentialPermissionRequester internal constructor(
    private val onRequest: (String) -> Unit
) {
    /** Queue [permission] to be requested on the next frame. No-op while a request is in flight. */
    fun request(permission: String) = onRequest(permission)
}

/**
 * Remembers a [SequentialPermissionRequester] for this composition.
 *
 * [onResult] is invoked once per completed request with the permission and its
 * grant result. It may itself call [SequentialPermissionRequester.request] to
 * chain the next permission — that is safe, because the request is only recorded
 * in state and launched later by the [LaunchedEffect], never inside this callback.
 */
@Composable
fun rememberSequentialPermissionRequester(
    onResult: (permission: String, granted: Boolean) -> Unit
): SequentialPermissionRequester {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)

    // The permission currently awaiting a system result (null = idle).
    var inFlight by rememberSaveable { mutableStateOf<String?>(null) }
    // The permission queued to be launched on the next frame.
    var pendingRequest by rememberSaveable { mutableStateOf<String?>(null) }

    val requester = remember {
        SequentialPermissionRequester(onRequest = { permission ->
            if (inFlight == null && pendingRequest == null) {
                pendingRequest = permission
            }
        })
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val requested = inFlight
        inFlight = null
        if (requested != null) {
            currentOnResult(requested, granted)
        }
    }

    // Launch from the effect scope (next frame, outside result dispatch) so the
    // registry is never re-entered and requests stay strictly sequential.
    LaunchedEffect(pendingRequest) {
        val permission = pendingRequest ?: return@LaunchedEffect
        pendingRequest = null
        // Already granted (e.g. via Settings) — report success without a dialog.
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            inFlight = null
            currentOnResult(permission, true)
            return@LaunchedEffect
        }
        inFlight = permission
        // Defence in depth: a platform quirk must degrade to "denied", never crash.
        runCatching { launcher.launch(permission) }
            .onFailure {
                inFlight = null
                currentOnResult(permission, false)
            }
    }

    return requester
}
