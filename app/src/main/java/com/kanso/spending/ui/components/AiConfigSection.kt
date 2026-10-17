package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.ai.AiSettingsStore
import com.kanso.spending.domain.ai.AiConfig
import com.kanso.spending.ui.theme.LocalKansoColors

/**
 * Settings for the optional GLM 5.3 Flash AI-assisted SMS parsing.
 *
 * SMS access and external AI processing are explained as two SEPARATE choices:
 * enabling AI here never grants SMS permission, and granting SMS permission
 * never sends anything anywhere. Three gates must all be open before a single
 * byte leaves the device — feature on, explicit consent, and a credential the
 * user supplies at runtime. With any gate closed, every message is parsed by
 * the local rules and manual tracking is untouched.
 *
 * The credential is entered here and stored in app-private preferences. It is
 * never hardcoded, committed, or built into the APK.
 */
@Composable
fun AiConfigSection(
    store: AiSettingsStore,
    /** Real connectivity probe wired to the repository. Null hides the button. */
    onTestConnection: ((String) -> Unit)? = null
) {
    val colors = LocalKansoColors.current

    // Local editing state, seeded once from the persisted store. Nothing is
    // written back until the user taps Save, so a half-filled form is harmless.
    var enabled by remember { mutableStateOf(store.aiEnabled) }
    var consent by remember { mutableStateOf(store.aiConsentGranted) }
    val initial = remember { store.read() }
    var modelId by remember { mutableStateOf(initial.modelId) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val keyPresent = apiKey.isNotBlank()
    val gatesOpen = enabled && consent && keyPresent

    Text("AI-ASSISTED PARSING (OPTIONAL, OFF BY DEFAULT)", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
    Spacer(Modifier.height(6.dp))

    // ---- the two choices, explained separately
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.elevatedSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Two separate permissions", style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "1. SMS access — read messages on this device only. Parsed locally; bodies never stored.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary
            )
            Text(
                "2. External AI processing — send redacted text of messages the LOCAL parser could not " +
                    "understand to GLM 5.3 Flash. This leaves your phone. It is a separate, optional " +
                    "choice and is OFF until you turn it on below.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    ToggleRow(
        title = "Enable AI-assisted parsing",
        description = "Sends only redacted text of messages the local parser found ambiguous. " +
            "When off, everything is parsed locally — identical to the app without AI.",
        checked = enabled,
        onCheckedChange = { enabled = it; saved = false }
    )

    if (enabled) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(14.dp),
            color = colors.background,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = consent, onCheckedChange = { consent = it; saved = false })
                Column(Modifier.weight(1f)) {
                    Text("I consent to external processing", style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                    Text(
                        "Required. I understand redacted financial message text is sent to a third-party " +
                            "AI service. Without this consent, nothing is ever sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary
                    )
                }
            }
        }

        AiField("Model ID", modelId, AiConfig.DEFAULT_MODEL_ID) { modelId = it; saved = false }
        AiField("API base URL", baseUrl, AiConfig.DEFAULT_BASE_URL) { baseUrl = it; saved = false }
        AiField(
            "API key (stored on this device only)",
            apiKey,
            "Paste your key — never built into the APK",
            isPassword = true
        ) { apiKey = it; saved = false }

        if (!keyPresent) {
            Text(
                "Add your API key to activate AI. Without it, messages continue through local parsing only.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ochreAccent,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Button(
            onClick = {
                store.write(modelId = modelId, baseUrl = baseUrl, apiKey = apiKey.ifBlank { null })
                store.aiEnabled = enabled
                store.aiConsentGranted = consent
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = colors.positiveAccent)
        ) {
            Text(if (saved) "Saved" else "Save AI settings", color = colors.inkPrimary)
        }

        if (onTestConnection != null) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // Persist what's on screen so the probe uses exactly these
                    // settings, then run ONE real request. No SMS, no ledger data.
                    store.write(modelId = modelId, baseUrl = baseUrl, apiKey = apiKey.ifBlank { null })
                    testing = true
                    testResult = null
                    onTestConnection { description ->
                        testing = false
                        testResult = description
                    }
                },
                enabled = !testing && keyPresent,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.elevatedSurface)
            ) {
                Text(
                    if (testing) "Testing connection…" else "Test connection",
                    color = if (keyPresent) colors.inkPrimary else colors.inkMuted
                )
            }
            testResult?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (gatesOpen)
                "Active: ambiguous messages will be sent to GLM 5.3 Flash for help."
            else
                "Not active: every message is parsed locally. You can turn this off at any time.",
            style = MaterialTheme.typography.bodySmall,
            color = if (gatesOpen) colors.positiveAccent else colors.inkMuted
        )
    }

    Spacer(Modifier.height(6.dp))
    Text(
        "Honest limits: the AI provider can see the redacted text that is sent; only what leaves the " +
            "device is external. Your API key is stored in this app's private storage, not encrypted with " +
            "hardware keys — on a rooted device it is extractable, so a server-side proxy is the only way " +
            "to keep a key fully private. The AI never writes balances directly and every response is " +
            "validated locally before it can touch the ledger.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.inkMuted
    )
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalKansoColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = colors.background,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.positiveAccent)
            )
        }
    }
}

@Composable
private fun AiField(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit
) {
    val colors = LocalKansoColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.elevatedSurface,
            unfocusedContainerColor = colors.elevatedSurface,
            focusedIndicatorColor = colors.fjordAccent,
            unfocusedIndicatorColor = colors.hairlineBorder
        )
    )
}
