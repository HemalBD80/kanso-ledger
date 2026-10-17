package com.kanso.spending.data.ai

import android.content.Context
import android.content.SharedPreferences
import com.kanso.spending.domain.ai.AiApiFormat
import com.kanso.spending.domain.ai.AiConfig

/**
 * Runtime store for the user-supplied AI credential.
 *
 * The key is entered by the user in settings and held in app-private
 * [SharedPreferences]. It is NEVER written to source, committed to Git, or
 * baked into the APK — the build has no knowledge of it.
 *
 * HONEST LIMITATION: this is app-sandbox storage, not hardware-backed
 * encryption. On a rooted device the value is extractable. A backend proxy that
 * holds the key server-side is the only way to keep it truly private; see the
 * README's credential-security section. This store exists so a purely client-side
 * deployment is possible without ever shipping a secret in the binary.
 */
class AiSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Current configuration, or an unconfigured instance when nothing is stored. */
    fun read(): AiConfig = AiConfig(
        modelId = prefs.getString(KEY_MODEL, AiConfig.DEFAULT_MODEL_ID) ?: AiConfig.DEFAULT_MODEL_ID,
        baseUrl = prefs.getString(KEY_BASE_URL, AiConfig.DEFAULT_BASE_URL) ?: AiConfig.DEFAULT_BASE_URL,
        apiKey = prefs.getString(KEY_API, null),
        apiFormat = AiApiFormat.fromId(prefs.getString(KEY_FORMAT, null))
    )

    /**
     * Persist the model, base URL, key and API format. A blank key clears the
     * credential; blank model/base are stored blank (never defaulted to a vendor).
     */
    fun write(modelId: String, baseUrl: String, apiKey: String?, apiFormat: AiApiFormat) {
        prefs.edit()
            .putString(KEY_MODEL, modelId.trim())
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_API, apiKey?.trim()?.takeIf { it.isNotEmpty() })
            .putString(KEY_FORMAT, apiFormat.id)
            .apply()
    }

    /** Remove the stored credential, leaving model/base defaults intact. */
    fun clearKey() {
        prefs.edit().remove(KEY_API).apply()
    }

    /**
     * Whether the user has consented to send message text to an external
     * service. Separate from holding a key: consent can be withdrawn while a
     * key is still stored, and processing must stop either way.
     */
    var aiConsentGranted: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CONSENT, value).apply()
        }

    /**
     * Whether the user has enabled AI-assisted parsing at all. This is the
     * master switch: when false, every message is handled by the local parser
     * and no text leaves the device, even if a key and consent are present.
     */
    var aiEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "kanso_ai_prefs"
        private const val KEY_MODEL = "ai_model_id"
        private const val KEY_BASE_URL = "ai_base_url"
        private const val KEY_API = "ai_api_key"
        private const val KEY_CONSENT = "ai_external_consent"
        private const val KEY_ENABLED = "ai_enabled"
        private const val KEY_FORMAT = "ai_api_format"
    }
}
