package com.noop.sync

import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import com.noop.data.WorkoutRow
import com.noop.widget.WidgetSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val bedtimeIso: String? = null,
        val wakeIso: String? = null,
        val sleepDeepMin: Double? = null,
        val sleepRemMin: Double? = null,
        val sleepLightMin: Double? = null,
        val sleepAwakeMin: Double? = null,
        val sleepEfficiencyPct: Double? = null,
        val sleepDebtMin: Double? = null,
        val sleepConsistencyPct: Double? = null,
        val respRateBpm: Double? = null,
        val skinTempDevC: Double? = null,
    )

    data class CoachNudgeAction(
        val label: String,
        val type: String,
        val routineId: String? = null,
        val prompt: String? = null,
    )

    data class CoachNudge(
        val screen: String,
        val headline: String,
        val message: String,
        val tone: String,
        val generatedAt: String,
        val rationale: String? = null,
        val evidence: List<String> = emptyList(),
        val action: CoachNudgeAction = defaultActionForScreen(screen),
    )

    sealed class SyncResult {
        data class Success(val syncedAt: String, val httpCode: Int) : SyncResult()
        data class Failure(val message: String, val httpCode: Int? = null) : SyncResult()
    }

    data class CloudCoachChatReply(
        val reply: String,
        val conversationId: String? = null,
        val model: String = "mistral-cloud-coach",
    )

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
     * Normalize an action type to one of `"pump_routine"`, `"pump_coach"`, `"noop_live"`, or `"noop_sync"`.
     */
    fun normalizeActionType(rawType: String?, fallbackType: String = "pump_coach"): String {
        val clean = rawType?.trim()?.lowercase().orEmpty()
        return when (clean) {
            "pump_routine", "pump_coach", "noop_live", "noop_sync" -> clean
            else -> fallbackType
        }
    }

    /**
     * Default action per screen when the backend omits or partially specifies `action`.
     */
    fun defaultActionForScreen(
        screen: String,
        headline: String = "",
        message: String = "",
    ): CoachNudgeAction {
        val cleanScreen = screen.trim().lowercase()
        val contextPrefix = if (headline.isNotBlank() && message.isNotBlank()) {
            "Context from NOOP ($headline: $message). "
        } else {
            ""
        }
        return when (cleanScreen) {
            "live" -> CoachNudgeAction(
                label = "Open Live Pedometer",
                type = "noop_live",
                prompt = null,
            )
            "workouts" -> CoachNudgeAction(
                label = "Build Next Workout",
                type = "pump_coach",
                prompt = "${contextPrefix}Based on my recent Pump workouts, weekly muscle balance, and NOOP recovery, build my next optimal workout session.",
            )
            "sleep" -> CoachNudgeAction(
                label = "Plan Tonight in Coach",
                type = "pump_coach",
                prompt = "${contextPrefix}Based on my last night's sleep architecture, sleep debt, and today's Pump training load, what should my bedtime target and wind-down plan be tonight?",
            )
            else -> CoachNudgeAction(
                label = "Open in Pump",
                type = "pump_coach",
                prompt = "${contextPrefix}Based on my NOOP recovery and readiness today, adjust today's Pump workout sets, loads, and target RPE.",
            )
        }
    }

    /**
     * Parse the `action` object inside a Coach nudge JSON response, falling back safely to
     * `'Open in Pump'` / `'pump_coach'` (or screen default) when omitted or malformed.
     */
    fun parseNudgeAction(
        actionObj: JSONObject?,
        screen: String,
        headline: String = "",
        message: String = "",
    ): CoachNudgeAction {
        val fallback = defaultActionForScreen(screen, headline, message)
        if (actionObj == null) return fallback

        var type = normalizeActionType(
            rawType = actionObj.optString("type", ""),
            fallbackType = fallback.type,
        )
        val routineId = actionObj.optString("routineId", "")
            .trim()
            .ifEmpty { actionObj.optString("routine_id", "").trim() }
            .takeIf { it.isNotEmpty() }

        if (type == "pump_routine" && routineId == null) {
            type = "pump_coach"
        }

        val rawLabel = actionObj.optString("label", "").trim()
        val defaultLabel = when (type) {
            "pump_routine" -> "Start Routine"
            "noop_live" -> "Open Live Pedometer"
            "noop_sync" -> "Sync Strap"
            else -> "Open in Pump"
        }
        val label = (rawLabel.ifEmpty { defaultLabel }).let {
            if (it.length <= 24) it else "${it.take(23).trimEnd()}…"
        }

        val prompt = if (type == "pump_coach") {
            val rawPrompt = actionObj.optString("prompt", "").trim()
            rawPrompt.ifEmpty {
                fallback.prompt
                    ?: "Based on my NOOP insight ($headline: $message), prescribe my optimal Pump workout adjustments for today."
            }
        } else {
            null
        }

        return CoachNudgeAction(
            label = label,
            type = type,
            routineId = if (type == "pump_routine") routineId else null,
            prompt = prompt,
        )
    }

    /**
     * Build the `pump://coach?prompt=<encoded>` deep-link URI string using pure JVM
     * `URLEncoder` (`+` converted to `%20`) so it is 100% unit-testable without Android `Uri` stubs.
     */
    fun buildPumpCoachDeepLinkUri(prompt: String?): String {
        val cleanPrompt = prompt?.trim().orEmpty()
        if (cleanPrompt.isEmpty()) return "pump://coach"
        val encoded = java.net.URLEncoder.encode(cleanPrompt, "UTF-8").replace("+", "%20")
        return "pump://coach?prompt=$encoded"
    }

    /**
     * Build the `pump://routine/<routineId>` deep-link URI string for opening a specific
     * Pump routine directly. Falls back to `"pump://routines"` if [routineId] is blank.
     */
    fun buildPumpRoutineDeepLinkUri(routineId: String?): String {
        val cleanId = routineId?.trim().orEmpty()
        if (cleanId.isEmpty()) return "pump://routines"
        val encoded = java.net.URLEncoder.encode(cleanId, "UTF-8").replace("+", "%20")
        return "pump://routine/$encoded"
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
            val rationale = obj.optString("rationale", "").trim().takeIf { it.isNotEmpty() }
            val evidenceArr = obj.optJSONArray("evidence")
            val evidence = buildList {
                if (evidenceArr != null) {
                    for (i in 0 until evidenceArr.length()) {
                        val item = evidenceArr.optString(i, "").trim()
                        if (item.isNotEmpty()) add(item)
                    }
                }
            }
            val action = parseNudgeAction(
                actionObj = obj.optJSONObject("action"),
                screen = screen,
                headline = headline,
                message = message,
            )
            CoachNudge(
                screen = screen,
                headline = headline,
                message = message,
                tone = tone,
                generatedAt = generatedAt,
                rationale = rationale,
                evidence = evidence,
                action = action,
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
        payload.bedtimeIso?.let { append(",\"bedtimeIso\":").append(escapeJsonString(it)) }
        payload.wakeIso?.let { append(",\"wakeIso\":").append(escapeJsonString(it)) }
        payload.sleepDeepMin?.let { append(",\"sleepDeepMin\":").append(it) }
        payload.sleepRemMin?.let { append(",\"sleepRemMin\":").append(it) }
        payload.sleepLightMin?.let { append(",\"sleepLightMin\":").append(it) }
        payload.sleepAwakeMin?.let { append(",\"sleepAwakeMin\":").append(it) }
        payload.sleepEfficiencyPct?.let { append(",\"sleepEfficiencyPct\":").append(it) }
        payload.sleepDebtMin?.let { append(",\"sleepDebtMin\":").append(it) }
        payload.sleepConsistencyPct?.let { append(",\"sleepConsistencyPct\":").append(it) }
        payload.respRateBpm?.let { append(",\"respRateBpm\":").append(it) }
        payload.skinTempDevC?.let { append(",\"skinTempDevC\":").append(it) }
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

    /**
     * Build the request JSON body for `POST <baseUrl>/coach/chat`.
     * When [resetConversation] is true, includes `"resetConversation":true` and omits any stale [conversationId].
     */
    fun buildChatRequestJson(
        message: String,
        conversationId: String? = null,
        resetConversation: Boolean = false,
    ): String = buildString {
        val cleanMessage = message.trim()
        val cleanConvId = conversationId?.trim().orEmpty()
        append('{')
        append("\"message\":").append(escapeJsonString(cleanMessage))
        if (resetConversation) {
            append(",\"resetConversation\":true")
        } else if (cleanConvId.isNotEmpty()) {
            append(",\"conversationId\":").append(escapeJsonString(cleanConvId))
        }
        append('}')
    }

    private fun extractReplyTextFromJsonObject(obj: JSONObject): String {
        for (key in listOf("reply", "text", "response", "content", "output", "answer")) {
            val v = obj.optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }

        val messageObj = obj.optJSONObject("message")
        if (messageObj != null) {
            val content = messageObj.optString("content", "").trim()
            if (content.isNotEmpty()) return content
            val text = messageObj.optString("text", "").trim()
            if (text.isNotEmpty()) return text
        } else {
            val messageStr = obj.optString("message", "").trim()
            if (messageStr.isNotEmpty()) return messageStr
        }

        val outputs = obj.optJSONArray("outputs")
        if (outputs != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until outputs.length()) {
                val item = outputs.optJSONObject(i) ?: continue
                val contentArr = item.optJSONArray("content")
                if (contentArr != null) {
                    for (j in 0 until contentArr.length()) {
                        val chunk = contentArr.optJSONObject(j) ?: continue
                        val t = chunk.optString("text", "").trim()
                        if (t.isNotEmpty()) parts.add(t)
                    }
                } else {
                    val c = item.optString("content", "").trim()
                    if (c.isNotEmpty()) parts.add(c)
                }
            }
            if (parts.isNotEmpty()) return parts.joinToString("\n").trim()
        }

        val choices = obj.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val first = choices.optJSONObject(0)
            val msg = first?.optJSONObject("message")
            val content = msg?.optString("content", "")?.trim().orEmpty()
            if (content.isNotEmpty()) return content
        }

        return ""
    }

    /**
     * Pure parser for `POST <baseUrl>/coach/chat` response JSON.
     * Supports `reply`, `text`, `response`, `outputs`, and `choices` shapes, and returns a
     * [Result.failure] when the server reports `ok: false`, invalid JSON, or an empty reply.
     */
    fun parseChatResponse(
        rawJson: String?,
        fallbackConversationId: String? = null,
    ): Result<CloudCoachChatReply> {
        if (rawJson.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Cloud Coach returned an empty response."))
        }
        return try {
            val obj = JSONObject(rawJson)
            if (obj.has("ok") && !obj.optBoolean("ok", true)) {
                val err = obj.optString("error", "").trim().ifEmpty { "Cloud Coach request failed." }
                return Result.failure(IllegalStateException(err))
            }

            val replyText = extractReplyTextFromJsonObject(obj)
            if (replyText.isEmpty()) {
                return Result.failure(IllegalStateException("Cloud Coach returned an empty reply."))
            }

            val convId = obj.optString("conversationId", "").trim()
                .ifEmpty { obj.optString("conversation_id", "").trim() }
                .ifEmpty { fallbackConversationId?.trim().orEmpty() }
                .takeIf { it.isNotEmpty() }

            val model = obj.optString("model", "").trim().ifEmpty { "mistral-cloud-coach" }

            Result.success(
                CloudCoachChatReply(
                    reply = replyText,
                    conversationId = convId,
                    model = model,
                ),
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Invalid Cloud Coach response: ${e.message ?: e.javaClass.simpleName}",
                    e,
                ),
            )
        }
    }

    /**
     * Synchronous worker for `POST <baseUrl>/coach/chat` with Bearer auth and 25s read timeout.
     */
    fun postCloudCoachChat(
        baseUrl: String,
        token: String,
        message: String,
        conversationId: String? = null,
        resetConversation: Boolean = false,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 25_000,
    ): Result<CloudCoachChatReply> {
        val cleanMessage = message.trim()
        if (cleanMessage.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        val endpoint = CoachSyncPrefs.chatEndpointUrl(baseUrl)
        val cleanToken = CoachSyncPrefs.sanitizeToken(token)
        if (endpoint.isEmpty() ||
            (!endpoint.startsWith("https://", ignoreCase = true) &&
                !endpoint.startsWith("http://", ignoreCase = true))
        ) {
            return Result.failure(IllegalArgumentException("Cloud Coach URL is not configured."))
        }
        if (cleanToken.isEmpty()) {
            return Result.failure(IllegalArgumentException("Cloud Coach Bearer token is not configured."))
        }

        val bodyBytes = buildChatRequestJson(
            message = cleanMessage,
            conversationId = conversationId,
            resetConversation = resetConversation,
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

            val code = conn.responseCode
            if (code in 200..299) {
                val responseText = conn.inputStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                }
                parseChatResponse(
                    rawJson = responseText,
                    fallbackConversationId = if (resetConversation) null else conversationId,
                )
            } else {
                val errBody = runCatching {
                    val stream = conn.errorStream ?: conn.inputStream
                    stream?.let {
                        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r ->
                            r.readText().take(240).trim()
                        }
                    }
                }.getOrNull().orEmpty()
                val parsedError = runCatching {
                    if (errBody.startsWith("{")) {
                        JSONObject(errBody).optString("error", "").trim()
                    } else {
                        ""
                    }
                }.getOrNull().orEmpty()
                val detail = parsedError.ifEmpty { errBody.take(160) }
                val msg = if (detail.isNotEmpty()) {
                    "Cloud Coach HTTP $code: $detail"
                } else {
                    "Cloud Coach HTTP $code"
                }
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    e.message?.takeIf { it.isNotBlank() } ?: "Could not reach Cloud Coach.",
                    e,
                ),
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Send a user chat turn to `POST <baseUrl>/coach/chat` on the Mistral Managed Agent (`coach-mcp`)
     * with Bearer auth and a 25s read timeout.
     */
    suspend fun sendCloudCoachChat(
        baseUrl: String,
        token: String,
        message: String,
        conversationId: String? = null,
        resetConversation: Boolean = false,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 25_000,
    ): Result<CloudCoachChatReply> = withContext(Dispatchers.IO) {
        postCloudCoachChat(
            baseUrl = baseUrl,
            token = token,
            message = message,
            conversationId = conversationId,
            resetConversation = resetConversation,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
    }

    data class CloudPumpWorkout(
        val id: String,
        val date: String,
        val startTime: String?,
        val endTime: String?,
        val title: String,
        val durationSec: Int,
        val totalSets: Int,
        val totalVolumeKg: Double,
        val exerciseCount: Int,
    )

    fun pullEndpointUrl(baseUrl: String): String =
        "${baseUrl.trim().trimEnd('/')}/sync/pull"

    fun parsePullWorkoutsResponse(jsonText: String): List<CloudPumpWorkout> {
        val root = JSONObject(jsonText)
        val arr = root.optJSONArray("recentWorkouts") ?: return emptyList()
        val out = ArrayList<CloudPumpWorkout>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            val date = obj.optString("date", "").trim()
            val title = obj.optString("title", "Workout").trim().ifEmpty { "Workout" }
            if (id.isEmpty() || date.isEmpty()) continue
            val startTime = obj.optString("startTime", "").trim().ifEmpty { null }
            val endTime = obj.optString("endTime", "").trim().ifEmpty { null }
            val durationSec = obj.optInt("durationSec", 0).coerceAtLeast(0)
            val totalSets = obj.optInt("totalSets", 0).coerceAtLeast(0)
            val totalVolumeKg = obj.optDouble("totalVolumeKg", 0.0).takeIf { !it.isNaN() } ?: 0.0
            val exercisesArr = obj.optJSONArray("exercises")
            val exerciseCount = exercisesArr?.length() ?: obj.optInt("exerciseCount", 0).coerceAtLeast(0)
            out.add(
                CloudPumpWorkout(
                    id = id,
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                    title = title,
                    durationSec = durationSec,
                    totalSets = totalSets,
                    totalVolumeKg = totalVolumeKg,
                    exerciseCount = exerciseCount,
                )
            )
        }
        return out
    }

    suspend fun fetchPumpWorkoutsFromCloud(
        baseUrl: String,
        bearerToken: String,
        timeoutMs: Int = 8_000,
    ): Result<List<CloudPumpWorkout>> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val cleanToken = bearerToken.trim()
        if (cleanUrl.isEmpty() || cleanToken.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Cloud Coach URL and token are required."))
        }
        var conn: HttpURLConnection? = null
        try {
            val endpoint = URL(pullEndpointUrl(cleanUrl))
            conn = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $cleanToken")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                Result.success(parsePullWorkoutsResponse(body))
            } else {
                Result.failure(IllegalStateException("Pull HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Reconcile Pump workouts from Supabase against existing Health Connect rows in NOOP:
     * - Updates stale durations/notes in-place on matching rows (preserving WHOOP HR/kcal/startTs).
     * - Inserts missing Pump sessions so NOOP Latest Workouts stays 100% in sync with Pump.
     */
    fun reconcilePumpWorkouts(
        cloudWorkouts: List<CloudPumpWorkout>,
        existingHcWorkouts: List<WorkoutRow>,
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): List<WorkoutRow> {
        if (cloudWorkouts.isEmpty()) return emptyList()
        val fmtVol = { kg: Double ->
            val rounded = kotlin.math.round(kg).toLong()
            java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(rounded)
        }
        val toLocalDate = { epochSec: Long ->
            java.time.Instant.ofEpochSecond(epochSec).atZone(zoneId).toLocalDate().toString()
        }
        val parseIsoSec = { iso: String? ->
            iso?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() }
        }
        val usedStartTs = mutableSetOf<Long>()
        val result = ArrayList<WorkoutRow>(cloudWorkouts.size)

        for (cw in cloudWorkouts) {
            if (cw.durationSec <= 0) continue
            val parsedStart = parseIsoSec(cw.startTime)
            val parsedEnd = parseIsoSec(cw.endTime)
            val notes = buildString {
                if (cw.exerciseCount > 0) append("${cw.exerciseCount} exercises · ")
                append("${cw.totalSets} sets · ${fmtVol(cw.totalVolumeKg)} kg volume · Logged with Pump")
            }

            // Match existing HC workout on same date + same title (case-insensitive) OR within 2h of startTime
            val match = existingHcWorkouts.firstOrNull { row ->
                row.startTs !in usedStartTs && (
                    (toLocalDate(row.startTs) == cw.date &&
                        row.sport.trim().equals(cw.title.trim(), ignoreCase = true)) ||
                    (parsedStart != null && kotlin.math.abs(row.startTs - parsedStart) <= 7200L)
                )
            }

            if (match != null) {
                usedStartTs.add(match.startTs)
                val newEnd = match.startTs + cw.durationSec.toLong()
                result.add(
                    match.copy(
                        endTs = newEnd,
                        sport = cw.title,
                        durationS = cw.durationSec.toDouble(),
                        notes = notes,
                    )
                )
            } else {
                val baseStart = parsedStart ?: runCatching {
                    java.time.LocalDate.parse(cw.date)
                        .atTime(12, 0)
                        .atZone(zoneId)
                        .toEpochSecond()
                }.getOrDefault(System.currentTimeMillis() / 1000L - cw.durationSec)
                var uniqueStart = baseStart
                while (uniqueStart in usedStartTs) uniqueStart += 60L
                usedStartTs.add(uniqueStart)
                val endTs = if (parsedStart != null && parsedEnd != null && parsedEnd > parsedStart) {
                    parsedEnd
                } else {
                    uniqueStart + cw.durationSec.toLong()
                }
                result.add(
                    WorkoutRow(
                        deviceId = "health-connect",
                        startTs = uniqueStart,
                        endTs = endTs,
                        sport = cw.title,
                        source = "health-connect",
                        durationS = cw.durationSec.toDouble(),
                        notes = notes,
                    )
                )
            }
        }
        return result
    }


    /**
     * Enrich a base [NoopSyncPayload] with compact nightly sleep architecture scalars from
     * [SleepSession] and [DailyMetric] (never raw epoch arrays or BLE streams).
     */
    fun enrichPayloadWithSleep(
        base: NoopSyncPayload,
        session: SleepSession?,
        daily: DailyMetric?,
        sleepDebtMin: Double? = null,
        sleepConsistencyPct: Double? = null,
    ): NoopSyncPayload {
        var deepMin: Double? = null
        var remMin: Double? = null
        var lightMin: Double? = null
        var awakeMin: Double? = null

        val rawStages = session?.stagesJSON?.trim().orEmpty()
        if (rawStages.startsWith("[")) {
            runCatching {
                val arr = org.json.JSONArray(rawStages)
                var dSec = 0L; var rSec = 0L; var lSec = 0L; var aSec = 0L
                for (i in 0 until arr.length()) {
                    val seg = arr.optJSONObject(i) ?: continue
                    val start = seg.optLong("start", 0L)
                    val end = seg.optLong("end", 0L)
                    val dur = (end - start).coerceAtLeast(0L)
                    when (seg.optString("stage", "").trim().lowercase()) {
                        "deep", "sws" -> dSec += dur
                        "rem" -> rSec += dur
                        "light" -> lSec += dur
                        "wake", "awake" -> aSec += dur
                    }
                }
                if (dSec + rSec + lSec + aSec > 0L) {
                    deepMin = kotlin.math.round(dSec / 6.0) / 10.0
                    remMin = kotlin.math.round(rSec / 6.0) / 10.0
                    lightMin = kotlin.math.round(lSec / 6.0) / 10.0
                    awakeMin = kotlin.math.round(aSec / 6.0) / 10.0
                }
            }
        } else if (rawStages.startsWith("{")) {
            runCatching {
                val obj = JSONObject(rawStages)
                if (obj.has("deep")) deepMin = kotlin.math.round(obj.optDouble("deep", 0.0) * 10.0) / 10.0
                if (obj.has("rem")) remMin = kotlin.math.round(obj.optDouble("rem", 0.0) * 10.0) / 10.0
                if (obj.has("light")) lightMin = kotlin.math.round(obj.optDouble("light", 0.0) * 10.0) / 10.0
                if (obj.has("awake")) awakeMin = kotlin.math.round(obj.optDouble("awake", 0.0) * 10.0) / 10.0
                else if (obj.has("wake")) awakeMin = kotlin.math.round(obj.optDouble("wake", 0.0) * 10.0) / 10.0
            }
        }

        val bedtimeIso = session?.effectiveStartTs?.takeIf { it > 0L }?.let {
            Instant.ofEpochSecond(it).toString()
        }
        val wakeIso = session?.endTs?.takeIf { it > 0L }?.let {
            Instant.ofEpochSecond(it).toString()
        }
        val effPct = session?.efficiency?.let { e ->
            val pct = if (e <= 1.0) e * 100.0 else e
            kotlin.math.round(pct * 10.0) / 10.0
        }
        val resp = daily?.respRateBpm?.let { kotlin.math.round(it * 10.0) / 10.0 }
        val skinDev = daily?.skinTempDevC?.let { kotlin.math.round(it * 100.0) / 100.0 }

        return base.copy(
            bedtimeIso = bedtimeIso ?: base.bedtimeIso,
            wakeIso = wakeIso ?: base.wakeIso,
            sleepDeepMin = deepMin ?: base.sleepDeepMin,
            sleepRemMin = remMin ?: base.sleepRemMin,
            sleepLightMin = lightMin ?: base.sleepLightMin,
            sleepAwakeMin = awakeMin ?: base.sleepAwakeMin,
            sleepEfficiencyPct = effPct ?: base.sleepEfficiencyPct,
            sleepDebtMin = sleepDebtMin?.let { kotlin.math.round(it * 10.0) / 10.0 } ?: base.sleepDebtMin,
            sleepConsistencyPct = sleepConsistencyPct?.let { kotlin.math.round(it * 10.0) / 10.0 } ?: base.sleepConsistencyPct,
            respRateBpm = resp ?: base.respRateBpm,
            skinTempDevC = skinDev ?: base.skinTempDevC,
        )
    }

}
