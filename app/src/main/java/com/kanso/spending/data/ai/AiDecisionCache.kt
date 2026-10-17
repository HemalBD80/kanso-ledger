package com.kanso.spending.data.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * A small, bounded, on-device cache of AI reply text, keyed by a hash of the
 * redacted request.
 *
 * WHY: the v2.4 pipeline interprets every eligible message, so a re-import, an
 * app restart, or a repeated broadcast would otherwise re-spend a network round
 * trip on a message already interpreted. A stable key (same model + same redacted
 * text) lets the transport answer instantly and cheaply.
 *
 * PRIVACY: only the model's own structured reply is stored — never the raw SMS,
 * never an identifier, never the credential. Keys are SHA-256 hashes. The cache
 * is bounded and can be cleared from Settings alongside the SMS import metadata.
 */
class AiDecisionCache(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Cached reply for a request key, or null. */
    fun get(key: String): String? = read().optString(key, "").takeIf { it.isNotEmpty() }

    /** Remember a successful reply. Bounded: oldest-inserted keys are evicted. */
    fun put(key: String, reply: String) {
        if (reply.isBlank()) return
        val map = read()
        map.put(key, reply)
        if (map.length() > MAX_ENTRIES) {
            val excess = map.length() - MAX_ENTRIES
            map.keys().asSequence().take(excess).toList().forEach { map.remove(it) }
        }
        prefs.edit().putString(KEY_BLOB, map.toString()).apply()
    }

    /** Drop every cached decision. Called when SMS processing metadata is cleared. */
    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun read(): JSONObject {
        val raw = prefs.getString(KEY_BLOB, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    companion object {
        private const val PREFS_NAME = "kanso_ai_cache"
        private const val KEY_BLOB = "decisions"
        /** Hard cap on retained decisions; older entries are evicted. */
        const val MAX_ENTRIES = 400
    }
}
