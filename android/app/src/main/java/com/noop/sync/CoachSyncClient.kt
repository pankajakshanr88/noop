package com.noop.sync

import com.noop.widget.WidgetSnapshot
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure `java.net.HttpURLConnection` client for syncing compact NOOP daily vitals
 * (`WidgetSnapshot` scalars ONLY — never raw BLE/IMU/HR streams) to `POST <url>/sync/noop`
 * and fetching fast per-screen Coach nudges from `POST <url>/coach/nudge`.
 */
object CoachSyncClient {

    data class NoopSyncPayload(
        val syncedAt: String,
        val date: String,
        val recoveryPct: Int?,
        val restPct: Int?,
        val effortPct: Int?,
        val hrvMs: Int?,
        val restingHr: Int?,
        val sleepMin: Int?,
        val steps: Int?,
        val batteryPct: Int?,
        val connected: Boolean,
        val source: String = "noop",
    )

    data class CoachNudge(
        val screen: String,
        val headline: String,
        val message: String,
        val tone: String,
        val generatedAt: String,
    )

    sealed class SyncResult {
        data class Success(val syncedAt: String, val httpCode: Int) : SyncResult()
        data class Failure(val message: String, val httpCode: Int? = null) : SyncResult()
    }

    /**
     * Normalize a nudge tone to one of `"green"`, `"yellow"`, `"red"`, or `"info"`.
     */
    fun normalizeTone(rawTone: String?): String {
        val clean = rawTone?.trim()?.lowercase().orEmpty()
        return when (clean) {
            "green", "yellow", "red", "info" -> clean
            else -> "info"
        }
    }

    /**
     * Pure parser for `POST <url>/coach/nudge` response JSON.
     * Returns null if JSON is malformed or missing `headline`/`message`.
     */
    fun parseNudgeResponse(rawJson: String?, fallbackScreen: String = "today"): CoachNudge? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(rawJson)
            if (obj.has("ok") && !obj.optBoolean("ok", true)) return null
            val headline = obj.optString("headline", "").trim()
            val message = obj.optString("message", "").trim()
            if (headline.isEmpty() || message.isEmpty()) return null
            val screen = obj.optString("screen", fallbackScreen).trim().ifEmpty { fallbackScreen }
            val tone = normalizeTone(obj.optString("tone", "info"))
            val generatedAt = obj.optString("generatedAt", "").trim()
            CoachNudge(
                screen = screen,
                headline = headline,
                message = message,
                tone = tone,
                generatedAt = generatedAt,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build the request JSON body for `POST <url>/coach/nudge`.
     */
    fun buildNudgeRequestJson(
        screen: String,
        localHour: Int,
        force: Boolean = false,
    ): String = buildString {
        append('{')
        append("\"screen\":").append(escapeJsonString(screen.trim().lowercase())).append(',')
        append("\"localHour\":").append(localHour.coerceIn(0, 23)).append(',')
        append("\"force\":").append(force)
        append('}')
    }

    /**
     * Fetch a per-screen Coach nudge from `POST <baseUrl>/coach/nudge` with a short 6s timeout.
     * Returns null silently on any network, auth, or parsing failure.
     */
    fun fetchNudge(
        baseUrl: String,
        bearerToken: String,
        screen: String,
        localHour: Int = LocalTime.now().hour,
        force: Boolean = false,
        connectTimeoutMs: Int = 6_000,
        readTimeoutMs: Int = 6_000,
    ): CoachNudge? {
        val endpoint = CoachSyncPrefs.nudgeEndpointUrl(baseUrl)
        val cleanToken = CoachSyncPrefs.sanitizeToken(bearerToken)
        if (endpoint.isEmpty() || cleanToken.isEmpty()) return null
        if (!endpoint.startsWith("https://", ignoreCase = true) &&
            !endpoint.startsWith("http://", ignoreCase = true)
        ) {
            return null
        }

        val bodyBytes = buildNudgeRequestJson(
            screen = screen,
            localHour = localHour,
            force = force,
        ).toByteArray(Charsets.UTF_8)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                useCaches = false
                setFixedLengthStreamingMode(bodyBytes.size)
                setRequestProperty("Authorization", "Bearer $cleanToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { out ->
                out.write(bodyBytes)
                out.flush()
            }

            if (conn.responseCode !in 200..299) return null
            val responseText = conn.inputStream?.let { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            }
            parseNudgeResponse(responseText, fallbackScreen = screen)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Build a compact scalar-only sync payload from [WidgetSnapshot].
     * Explicitly omits `heartRate`, `hrSeries`, `stressSeries`, and any raw BLE/IMU streams.
     */
    fun buildPayload(
        snapshot: WidgetSnapshot,
        syncedAt: String = Instant.now().toString(),
        date: String = LocalDate.now().toString(),
    ): NoopSyncPayload = NoopSyncPayload(
        syncedAt = syncedAt,
        date = date,
        recoveryPct = snapshot.recoveryPct,
        restPct = snapshot.restPct,
        effortPct = snapshot.effortPct,
        hrvMs = snapshot.hrvMs,
        restingHr = snapshot.restingHr,
        sleepMin = snapshot.sleepMin,
        steps = snapshot.steps,
        batteryPct = snapshot.batteryPct,
        connected = snapshot.connected,
        source = "noop",
    )

    /**
     * Pure Kotlin JSON serializer for [NoopSyncPayload].
     * Avoids `org.json.JSONObject` Android stub issues in JVM unit tests and guarantees
     * that only the expected scalar keys are ever serialized.
     */
    fun toJson(payload: NoopSyncPayload): String = buildString {
        append('{')
        append("\"syncedAt\":").append(escapeJsonString(payload.syncedAt)).append(',')
        append("\"date\":").append(escapeJsonString(payload.date)).append(',')
        append("\"recoveryPct\":").append(payload.recoveryPct?.toString() ?: "null").append(',')
        append("\"restPct\":").append(payload.restPct?.toString() ?: "null").append(',')
        append("\"effortPct\":").append(payload.effortPct?.toString() ?: "null").append(',')
        append("\"hrvMs\":").append(payload.hrvMs?.toString() ?: "null").append(',')
        append("\"restingHr\":").append(payload.restingHr?.toString() ?: "null").append(',')
        append("\"sleepMin\":").append(payload.sleepMin?.toString() ?: "null").append(',')
        append("\"steps\":").append(payload.steps?.toString() ?: "null").append(',')
        append("\"batteryPct\":").append(payload.batteryPct?.toString() ?: "null").append(',')
        append("\"connected\":").append(payload.connected).append(',')
        append("\"source\":").append(escapeJsonString(payload.source))
        append('}')
    }

    private fun escapeJsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    /**
     * POST [payload] to `<baseUrl>/sync/noop` with `Authorization: Bearer <token>`.
     * Must be invoked off the main thread (e.g., `Dispatchers.IO`).
     */
    fun postNoopVitals(
        baseUrl: String,
        bearerToken: String,
        payload: NoopSyncPayload,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
    ): SyncResult {
        val endpoint = CoachSyncPrefs.endpointUrl(baseUrl)
        val cleanToken = CoachSyncPrefs.sanitizeToken(bearerToken)

        if (endpoint.isEmpty()) {
            return SyncResult.Failure("Cloud Coach URL is not configured")
        }
        if (!endpoint.startsWith("https://", ignoreCase = true) &&
            !endpoint.startsWith("http://", ignoreCase = true)
        ) {
            return SyncResult.Failure("Cloud Coach URL must start with https:// or http://")
        }
        if (cleanToken.isEmpty()) {
            return SyncResult.Failure("Bearer token is not configured")
        }

        val bodyBytes = toJson(payload).toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                useCaches = false
                setFixedLengthStreamingMode(bodyBytes.size)
                setRequestProperty("Authorization", "Bearer $cleanToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { out ->
                out.write(bodyBytes)
                out.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                SyncResult.Success(syncedAt = payload.syncedAt, httpCode = code)
            } else {
                val errDetail = runCatching {
                    val stream = conn.errorStream ?: conn.inputStream
                    stream?.let {
                        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r ->
                            r.readText().take(160).trim()
                        }
                    }
                }.getOrNull().orEmpty()
                val msg = if (errDetail.isNotEmpty()) {
                    "HTTP $code: $errDetail"
                } else {
                    "HTTP $code"
                }
                SyncResult.Failure(message = msg, httpCode = code)
            }
        } catch (e: Exception) {
            SyncResult.Failure(
                message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
            )
        } finally {
            conn?.disconnect()
        }
    }
}
