package com.noop.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences store and pure validation/throttling helpers for direct
 * NOOP -> Cloud Coach (`coach-mcp` Cloudflare Worker) sync.
 */
object CoachSyncPrefs {
    private const val PREFS_FILE = "noop_coach_sync"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val KEY_LAST_SYNC_AT_MS = "lastSyncAtMs"
    private const val KEY_LAST_STATUS = "lastStatus"

    /** Background foreground-resume auto-sync throttle: 15 minutes. */
    const val THROTTLE_MS = 15 * 60 * 1_000L

    private val INVISIBLE_CHARS_REGEX =
        Regex("[\\u0000-\\u001F\\u007F-\\u009F\\u200B-\\u200D\\uFEFF]")
    private val BEARER_PREFIX_REGEX = Regex("^Bearer(?:\\s+|$)", RegexOption.IGNORE_CASE)

    /**
     * Normalize a user-entered Cloud Coach URL:
     * - strips invisible control/zero-width chars and trims whitespace
     * - strips trailing slashes
     * - strips a trailing `/sync/noop` suffix if the user pasted the full endpoint path
     */
    fun normalizeUrl(raw: String?): String {
        if (raw == null) return ""
        var cleaned = raw.replace(INVISIBLE_CHARS_REGEX, "").trim()
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.dropLast(1).trim()
        }
        if (cleaned.endsWith("/sync/noop", ignoreCase = true)) {
            cleaned = cleaned.dropLast("/sync/noop".length).trim()
            while (cleaned.endsWith("/")) {
                cleaned = cleaned.dropLast(1).trim()
            }
        }
        return cleaned
    }

    /**
     * Resolve the full `POST <baseUrl>/sync/noop` endpoint URL, or empty string if baseUrl is blank.
     */
    fun endpointUrl(rawBaseUrl: String?): String {
        val base = normalizeUrl(rawBaseUrl)
        if (base.isEmpty()) return ""
        return "$base/sync/noop"
    }

    /**
     * Sanitize a user-entered Bearer token:
     * - strips invisible control/zero-width chars and trims whitespace
     * - strips a leading `Bearer ` prefix if pasted with the header scheme
     */
    fun sanitizeToken(raw: String?): String {
        if (raw == null) return ""
        val withoutInvisible = raw.replace(INVISIBLE_CHARS_REGEX, "").trim()
        return withoutInvisible.replaceFirst(BEARER_PREFIX_REGEX, "").trim()
    }

    /**
     * Pure 15-minute foreground sync gate.
     * Returns true iff sync is enabled, URL and token are non-blank, no sync is currently in flight,
     * and at least [throttleMs] have elapsed since [lastSyncAtMs].
     */
    fun shouldRunForegroundSync(
        enabled: Boolean,
        url: String?,
        token: String?,
        inFlight: Boolean,
        lastSyncAtMs: Long,
        nowMs: Long,
        throttleMs: Long = THROTTLE_MS,
    ): Boolean {
        if (!enabled || inFlight) return false
        if (normalizeUrl(url).isEmpty() || sanitizeToken(token).isEmpty()) return false
        if (lastSyncAtMs > 0L) {
            val elapsed = nowMs - lastSyncAtMs
            if (elapsed in 0 until throttleMs) return false
        }
        return true
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun url(context: Context): String =
        normalizeUrl(prefs(context).getString(KEY_URL, ""))

    fun setUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_URL, normalizeUrl(url)).apply()
    }

    fun token(context: Context): String =
        sanitizeToken(prefs(context).getString(KEY_TOKEN, ""))

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, sanitizeToken(token)).apply()
    }

    fun lastSyncAtMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC_AT_MS, 0L)

    fun setLastSyncAtMs(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC_AT_MS, atMs).apply()
    }

    fun lastStatus(context: Context): String =
        prefs(context).getString(KEY_LAST_STATUS, "").orEmpty()

    fun setLastStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_LAST_STATUS, status).apply()
    }

    fun recordSyncResult(context: Context, atMs: Long, status: String, updateTimestamp: Boolean = true) {
        val editor = prefs(context).edit().putString(KEY_LAST_STATUS, status)
        if (updateTimestamp) {
            editor.putLong(KEY_LAST_SYNC_AT_MS, atMs)
        }
        editor.apply()
    }
}
