package com.noop.sync

import com.noop.widget.HrPoint
import com.noop.widget.StressPoint
import com.noop.data.WorkoutRow
import com.noop.widget.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachSyncPayloadTest {

    @Test
    fun `buildPayload and toJson include only WidgetSnapshot scalars and never raw HR or stress streams`() {
        val snapshot = WidgetSnapshot(
            recoveryPct = 86,
            restPct = 91,
            effortPct = 64,
            heartRate = 148,
            heartRateStale = false,
            batteryPct = 78,
            connected = true,
            hrSeries = listOf(HrPoint(ts = 1_700_000_000L, bpm = 148)),
            stressSeries = listOf(StressPoint(ts = 1_700_000_000L, level = 2.4)),
            stressDay = 20_000L,
            hrvMs = 69,
            restingHr = 50,
            sleepMin = 445,
            steps = 11_240,
            updatedAtMs = 1_700_000_123_000L,
        )

        val payload = CoachSyncClient.buildPayload(
            snapshot = snapshot,
            syncedAt = "2026-11-23T15:00:00Z",
            date = "2026-11-23",
        )

        assertEquals("2026-11-23T15:00:00Z", payload.syncedAt)
        assertEquals("2026-11-23", payload.date)
        assertEquals(86, payload.recoveryPct)
        assertEquals(91, payload.restPct)
        assertEquals(64, payload.effortPct)
        assertEquals(69, payload.hrvMs)
        assertEquals(50, payload.restingHr)
        assertEquals(445, payload.sleepMin)
        assertEquals(11_240, payload.steps)
        assertEquals(78, payload.batteryPct)
        assertTrue(payload.connected)
        assertEquals("noop", payload.source)

        val json = CoachSyncClient.toJson(payload)
        assertTrue(json.contains("\"syncedAt\":\"2026-11-23T15:00:00Z\""))
        assertTrue(json.contains("\"date\":\"2026-11-23\""))
        assertTrue(json.contains("\"recoveryPct\":86"))
        assertTrue(json.contains("\"restPct\":91"))
        assertTrue(json.contains("\"effortPct\":64"))
        assertTrue(json.contains("\"hrvMs\":69"))
        assertTrue(json.contains("\"restingHr\":50"))
        assertTrue(json.contains("\"sleepMin\":445"))
        assertTrue(json.contains("\"steps\":11240"))
        assertTrue(json.contains("\"batteryPct\":78"))
        assertTrue(json.contains("\"connected\":true"))
        assertTrue(json.contains("\"source\":\"noop\""))

        // Never leak live heartRate, hrSeries, stressSeries, or IMU streams
        assertFalse(json.contains("heartRate"))
        assertFalse(json.contains("hrSeries"))
        assertFalse(json.contains("stressSeries"))
        assertFalse(json.contains("148"))
    }

    @Test
    fun `buildPayload and toJson serialize null scalar fields cleanly`() {
        val emptySnapshot = WidgetSnapshot(connected = false)
        val payload = CoachSyncClient.buildPayload(
            snapshot = emptySnapshot,
            syncedAt = "2026-11-23T08:00:00Z",
            date = "2026-11-23",
        )

        assertNull(payload.recoveryPct)
        assertNull(payload.restPct)
        assertNull(payload.effortPct)
        assertNull(payload.hrvMs)
        assertNull(payload.restingHr)
        assertNull(payload.sleepMin)
        assertNull(payload.steps)
        assertNull(payload.batteryPct)
        assertFalse(payload.connected)

        val json = CoachSyncClient.toJson(payload)
        assertTrue(json.contains("\"recoveryPct\":null"))
        assertTrue(json.contains("\"steps\":null"))
        assertTrue(json.contains("\"connected\":false"))
    }

    @Test
    fun `normalizeUrl and sanitizeToken strip whitespace, trailing slashes, duplicate path, and Bearer prefix`() {
        assertEquals(
            "https://coach-mcp.example.workers.dev",
            CoachSyncPrefs.normalizeUrl("  https://coach-mcp.example.workers.dev/// \u200B "),
        )
        assertEquals(
            "https://coach-mcp.example.workers.dev",
            CoachSyncPrefs.normalizeUrl("https://coach-mcp.example.workers.dev/sync/noop/"),
        )
        assertEquals(
            "https://coach-mcp.example.workers.dev/sync/noop",
            CoachSyncPrefs.endpointUrl("https://coach-mcp.example.workers.dev/"),
        )
        assertEquals("", CoachSyncPrefs.endpointUrl("   "))

        assertEquals(
            "secret-token-123",
            CoachSyncPrefs.sanitizeToken("  Bearer   secret-token-123\u200B\n"),
        )
        assertEquals(
            "secret-token-123",
            CoachSyncPrefs.sanitizeToken("bearer secret-token-123"),
        )
        assertEquals("", CoachSyncPrefs.sanitizeToken("   "))
    }

    @Test
    fun `shouldRunForegroundSync enforces 15-minute throttle and config gates`() {
        val baseNow = 1_700_000_000_000L
        val url = "https://coach-mcp.example.workers.dev"
        val token = "valid-token"

        // First sync (lastSyncAtMs == 0) is allowed
        assertTrue(
            CoachSyncPrefs.shouldRunForegroundSync(
                enabled = true,
                url = url,
                token = token,
                inFlight = false,
                lastSyncAtMs = 0L,
                nowMs = baseNow,
            ),
        )

        // Disabled -> false
        assertFalse(
            CoachSyncPrefs.shouldRunForegroundSync(
                enabled = false,
                url = url,
                token = token,
                inFlight = false,
                lastSyncAtMs = 0L,
                nowMs = baseNow,
            ),
        )

        // In-flight -> false
        assertFalse(
            CoachSyncPrefs.shouldRunForegroundSync(
                enabled = true,
                url = url,
                token = token,
                inFlight = true,
                lastSyncAtMs = 0L,
                nowMs = baseNow,
            ),
        )

        // Missing URL or token -> false
        assertFalse(
            CoachSyncPrefs.shouldRunForegroundSync(
                enabled = true,
                url = "  ",
                token = token,
                inFlight = false,
                lastSyncAtMs = 0L,
                nowMs = baseNow,
            ),
        )
        assertFalse(
            CoachSyncPrefs.shouldRunForegroundSync(
                enabled = true,
                url = url,
                token = "Bearer   ",
                inFlight = false,
                lastSyncAtMs = 0L,
                nowMs = baseNow,
            ),
        )

        // Within 15 minutes (e.g. 14m 59s) -> throttled (false)
        assertFalse(
            CoachSyncPrefs.shouldRunForegroundSync(
                enabled = true,
                url = url,
                token = token,
                inFlight = false,
                lastSyncAtMs = baseNow,
                nowMs = baseNow + CoachSyncPrefs.THROTTLE_MS - 1_000L,
            ),
        )

        // At or after 15 minutes -> allowed (true)
        assertTrue(
            CoachSyncPrefs.shouldRunForegroundSync(
                enabled = true,
                url = url,
                token = token,
                inFlight = false,
                lastSyncAtMs = baseNow,
                nowMs = baseNow + CoachSyncPrefs.THROTTLE_MS,
            ),
        )
    }

    @Test
    fun `parseNudgeResponse parses valid nudge JSON and normalizeTone falls back to info`() {
        val rawJson = """
            {
              "ok": true,
              "screen": "live",
              "headline": "Steps Pacing · 2,100 by 5pm",
              "message": "Your steps are at 2,100 by 5pm — a 15-min walk boosts NEAT.",
              "tone": "YELLOW",
              "cached": false,
              "generatedAt": "2026-11-23T23:00:00Z"
            }
        """.trimIndent()

        val nudge = CoachSyncClient.parseNudgeResponse(rawJson, fallbackScreen = "today")
        assertTrue(nudge != null)
        assertEquals("live", nudge!!.screen)
        assertEquals("Steps Pacing · 2,100 by 5pm", nudge.headline)
        assertEquals("Your steps are at 2,100 by 5pm — a 15-min walk boosts NEAT.", nudge.message)
        assertEquals("yellow", nudge.tone)
        assertEquals("2026-11-23T23:00:00Z", nudge.generatedAt)

        // Unknown or missing tone falls back to "info"
        val unknownToneJson = """
            {
              "ok": true,
              "headline": "Pull Volume Lead",
              "message": "Prioritize RDLs tomorrow.",
              "tone": "purple"
            }
        """.trimIndent()
        val fallbackNudge = CoachSyncClient.parseNudgeResponse(unknownToneJson, fallbackScreen = "workouts")
        assertTrue(fallbackNudge != null)
        assertEquals("workouts", fallbackNudge!!.screen)
        assertEquals("info", fallbackNudge.tone)

        assertEquals("green", CoachSyncClient.normalizeTone("Green"))
        assertEquals("red", CoachSyncClient.normalizeTone(" RED "))
        assertEquals("info", CoachSyncClient.normalizeTone("invalid"))
        assertEquals("info", CoachSyncClient.normalizeTone(null))

        // Error or malformed JSON returns null silently
        assertNull(CoachSyncClient.parseNudgeResponse("""{"ok":false,"error":"failed"}"""))
        assertNull(CoachSyncClient.parseNudgeResponse("""{"ok":true,"headline":""}"""))
        assertNull(CoachSyncClient.parseNudgeResponse("not json"))
    }

    @Test
    fun `shouldShowScreenNudges and nudgeEndpointUrl honor sync and nudge prefs`() {
        assertTrue(CoachSyncPrefs.shouldShowScreenNudges(syncEnabled = true, showScreenNudgesPref = true))
        assertFalse(CoachSyncPrefs.shouldShowScreenNudges(syncEnabled = true, showScreenNudgesPref = false))
        assertFalse(CoachSyncPrefs.shouldShowScreenNudges(syncEnabled = false, showScreenNudgesPref = true))

        assertEquals(
            "https://coach-mcp.example.workers.dev/coach/nudge",
            CoachSyncPrefs.nudgeEndpointUrl("https://coach-mcp.example.workers.dev/coach/nudge/"),
        )
        assertEquals(
            "https://coach-mcp.example.workers.dev/coach/nudge",
            CoachSyncPrefs.nudgeEndpointUrl("https://coach-mcp.example.workers.dev/sync/noop"),
        )
    }

    @Test
    fun `parseNudgeResponse parses dynamic action and buildPumpCoachDeepLinkUri encodes prompt safely`() {
        val withPumpActionJson = """
            {
              "ok": true,
              "screen": "workouts",
              "headline": "Hamstrings Under 6 Sets",
              "message": "Back is at 14 sets while hamstrings are at 4 sets. Prioritize RDLs today.",
              "tone": "yellow",
              "generatedAt": "2026-11-23T23:30:00Z",
              "action": {
                "label": "Build Legs Workout",
                "type": "pump_coach",
                "prompt": "Build a hamstring-focused Legs workout in Pump with RDLs @ RPE 8."
              }
            }
        """.trimIndent()

        val parsed = CoachSyncClient.parseNudgeResponse(withPumpActionJson, fallbackScreen = "workouts")
        assertTrue(parsed != null)
        assertEquals("Build Legs Workout", parsed!!.action.label)
        assertEquals("pump_coach", parsed.action.type)
        assertEquals(
            "Build a hamstring-focused Legs workout in Pump with RDLs @ RPE 8.",
            parsed.action.prompt,
        )

        val uri = CoachSyncClient.buildPumpCoachDeepLinkUri(parsed.action.prompt)
        assertEquals(
            "pump://coach?prompt=Build%20a%20hamstring-focused%20Legs%20workout%20in%20Pump%20with%20RDLs%20%40%20RPE%208.",
            uri,
        )
        assertEquals("pump://coach", CoachSyncClient.buildPumpCoachDeepLinkUri(null))
        assertEquals("pump://coach", CoachSyncClient.buildPumpCoachDeepLinkUri("   "))

        // Omitted action on "today" falls back to "Open in Pump" / "pump_coach" with a concrete prompt
        val omittedActionTodayJson = """
            {
              "ok": true,
              "screen": "today",
              "headline": "Charge 44% · Cap RPE",
              "message": "Trim 1 working set today.",
              "tone": "red"
            }
        """.trimIndent()
        val fallbackToday = CoachSyncClient.parseNudgeResponse(omittedActionTodayJson, fallbackScreen = "today")
        assertTrue(fallbackToday != null)
        assertEquals("Open in Pump", fallbackToday!!.action.label)
        assertEquals("pump_coach", fallbackToday.action.type)
        assertTrue(!fallbackToday.action.prompt.isNullOrBlank())

        // noop_live and noop_sync actions parse cleanly and truncate labels > 24 chars
        val syncActionJson = """
            {
              "ok": true,
              "screen": "today",
              "headline": "Strap Sync Needed",
              "message": "Offload latest overnight HR.",
              "tone": "info",
              "action": {
                "label": "Sync Strap And Refresh Cloud Now",
                "type": "noop_sync"
              }
            }
        """.trimIndent()
        val syncNudge = CoachSyncClient.parseNudgeResponse(syncActionJson)
        assertTrue(syncNudge != null)
        assertEquals("noop_sync", syncNudge!!.action.type)
        assertTrue(syncNudge.action.label.length <= 24)
        assertNull(syncNudge.action.prompt)
    }

    @Test
    fun `parseNudgeResponse parses rationale, evidence, pump_routine action, and buildPumpRoutineDeepLinkUri`() {
        val routineNudgeJson = """
            {
              "ok": true,
              "screen": "workouts",
              "headline": "Legs Complete · Pull Routine Next",
              "message": "Legs are at 12 sets this week while biceps are at 0 sets. Start Pull Day next.",
              "tone": "green",
              "generatedAt": "2026-11-23T23:45:00Z",
              "rationale": "Lower body was already logged today (12 sets total), so Pull Day is the next priority in your PPL split.",
              "evidence": [
                "Legs: 12 sets logged (Bulgarian Split Squat + Trap Bar Deadlift today)",
                "Biceps: 0 sets (<6 targetMinSets)"
              ],
              "action": {
                "label": "Start Pull Routine",
                "type": "pump_routine",
                "routineId": "routine_pull_01"
              }
            }
        """.trimIndent()

        val parsed = CoachSyncClient.parseNudgeResponse(routineNudgeJson, fallbackScreen = "workouts")
        assertTrue(parsed != null)
        assertEquals(
            "Lower body was already logged today (12 sets total), so Pull Day is the next priority in your PPL split.",
            parsed!!.rationale,
        )
        assertEquals(2, parsed.evidence.size)
        assertTrue(parsed.evidence[0].contains("Legs: 12 sets"))
        assertEquals("Start Pull Routine", parsed.action.label)
        assertEquals("pump_routine", parsed.action.type)
        assertEquals("routine_pull_01", parsed.action.routineId)

        assertEquals(
            "pump://routine/routine_pull_01",
            CoachSyncClient.buildPumpRoutineDeepLinkUri(parsed.action.routineId),
        )
        assertEquals("pump://routines", CoachSyncClient.buildPumpRoutineDeepLinkUri(null))
        assertEquals("pump://routines", CoachSyncClient.buildPumpRoutineDeepLinkUri("   "))

        // If pump_routine is missing routineId, it safely falls back to pump_coach
        val missingRoutineIdJson = """
            {
              "ok": true,
              "screen": "workouts",
              "headline": "Pull Routine Next",
              "message": "Hit Pull Day tomorrow.",
              "tone": "info",
              "action": {
                "label": "Start Pull Routine",
                "type": "pump_routine"
              }
            }
        """.trimIndent()
        val fallbackCoach = CoachSyncClient.parseNudgeResponse(missingRoutineIdJson, fallbackScreen = "workouts")
        assertTrue(fallbackCoach != null)
        assertEquals("pump_coach", fallbackCoach!!.action.type)
        assertNull(fallbackCoach.action.routineId)
        assertTrue(!fallbackCoach.action.prompt.isNullOrBlank())
    }

    @Test
    fun `chatEndpointUrl, isConfigured, buildChatRequestJson, and parseChatResponse handle Cloud Coach chat contract`() {
        assertEquals(
            "https://pankaj-coach-mcp.pank-r89.workers.dev/coach/chat",
            CoachSyncPrefs.chatEndpointUrl("https://pankaj-coach-mcp.pank-r89.workers.dev/coach/chat/"),
        )
        assertEquals(
            "https://pankaj-coach-mcp.pank-r89.workers.dev/coach/chat",
            CoachSyncPrefs.chatEndpointUrl("https://pankaj-coach-mcp.pank-r89.workers.dev/sync/noop"),
        )
        assertEquals("", CoachSyncPrefs.chatEndpointUrl("   "))

        assertTrue(
            CoachSyncPrefs.isConfigured(
                enabled = true,
                url = "https://pankaj-coach-mcp.pank-r89.workers.dev",
                token = "Bearer secret-123",
            ),
        )
        assertFalse(
            CoachSyncPrefs.isConfigured(
                enabled = false,
                url = "https://pankaj-coach-mcp.pank-r89.workers.dev",
                token = "secret-123",
            ),
        )
        assertFalse(
            CoachSyncPrefs.isConfigured(
                enabled = true,
                url = "   ",
                token = "secret-123",
            ),
        )
        assertFalse(
            CoachSyncPrefs.isConfigured(
                enabled = true,
                url = "https://pankaj-coach-mcp.pank-r89.workers.dev",
                token = "Bearer   ",
            ),
        )

        // First turn without conversationId
        val reqInitial = CoachSyncClient.buildChatRequestJson(
            message = "How is my recovery today?",
            conversationId = null,
            resetConversation = false,
        )
        assertEquals("""{"message":"How is my recovery today?"}""", reqInitial)

        // Follow-up turn with conversationId
        val reqFollowUp = CoachSyncClient.buildChatRequestJson(
            message = "What should I lift?",
            conversationId = "conv_mistral_42",
            resetConversation = false,
        )
        assertEquals(
            """{"message":"What should I lift?","conversationId":"conv_mistral_42"}""",
            reqFollowUp,
        )

        // Reset turn sends resetConversation:true and omits stale conversationId
        val reqReset = CoachSyncClient.buildChatRequestJson(
            message = "Start fresh block",
            conversationId = "conv_mistral_42",
            resetConversation = true,
        )
        assertEquals(
            """{"message":"Start fresh block","resetConversation":true}""",
            reqReset,
        )

        // Standard coach-mcp reply JSON
        val okReplyJson = """
            {
              "ok": true,
              "reply": "Charge is 86% and HRV is 69 ms. Hit Pull Day at RPE 8.",
              "conversationId": "conv_mistral_99",
              "model": "mistral-medium-latest"
            }
        """.trimIndent()
        val parsedResult = CoachSyncClient.parseChatResponse(okReplyJson)
        assertTrue(parsedResult.isSuccess)
        val reply = parsedResult.getOrThrow()
        assertEquals("Charge is 86% and HRV is 69 ms. Hit Pull Day at RPE 8.", reply.reply)
        assertEquals("conv_mistral_99", reply.conversationId)
        assertEquals("mistral-medium-latest", reply.model)

        // Fallback conversationId & Mistral outputs array shape
        val outputsJson = """
            {
              "outputs": [
                {
                  "type": "message.output",
                  "content": "Keep rest intervals at 2 minutes."
                }
              ]
            }
        """.trimIndent()
        val outputsReply = CoachSyncClient.parseChatResponse(
            rawJson = outputsJson,
            fallbackConversationId = "conv_existing_7",
        ).getOrThrow()
        assertEquals("Keep rest intervals at 2 minutes.", outputsReply.reply)
        assertEquals("conv_existing_7", outputsReply.conversationId)
        assertEquals("mistral-cloud-coach", outputsReply.model)

        // Error or empty responses return Result.failure
        assertTrue(
            CoachSyncClient.parseChatResponse("""{"ok":false,"error":"Agent unavailable"}""").isFailure,
        )
        assertTrue(CoachSyncClient.parseChatResponse("""{"ok":true,"reply":"   "}""").isFailure)
        assertTrue(CoachSyncClient.parseChatResponse("").isFailure)
    }

    @Test
    fun `reconcilePumpWorkouts updates stale HC duration and inserts missing Pump workout`() {
        val pullJson = """
            {
              "ok": true,
              "recentWorkouts": [
                {
                  "id": "w_25",
                  "date": "2026-11-25",
                  "startTime": "2026-11-25T13:00:00Z",
                  "endTime": "2026-11-25T14:00:00Z",
                  "title": "Chest & Back A (Antagonist Thickness)",
                  "durationSec": 3600,
                  "totalSets": 16,
                  "totalVolumeKg": 5747,
                  "exercises": [{"name": "Bench Press"}, {"name": "Row"}]
                },
                {
                  "id": "w_23",
                  "date": "2026-11-23",
                  "title": "Legs & Core B (Unilateral Leg Sweep)",
                  "durationSec": 2700,
                  "totalSets": 4,
                  "totalVolumeKg": 907,
                  "exercises": [{"name": "Bulgarian Split Squat"}, {"name": "Plank"}]
                }
              ]
            }
        """.trimIndent()

        val parsed = CoachSyncClient.parsePullWorkoutsResponse(pullJson)
        assertEquals(2, parsed.size)

        val zone = java.time.ZoneId.of("UTC")
        val existing23Start = java.time.Instant.parse("2026-11-23T12:54:00Z").epochSecond
        val existingHc = listOf(
            WorkoutRow(
                deviceId = "health-connect",
                startTs = existing23Start,
                endTs = existing23Start + 9300L,
                sport = "Legs & Core B (Unilateral Leg Sweep)",
                source = "health-connect",
                durationS = 9300.0,
                energyKcal = 181.0,
                avgHr = 118,
                notes = "Logged with Pump",
            )
        )

        val reconciled = CoachSyncClient.reconcilePumpWorkouts(parsed, existingHc, zone)
        assertEquals(2, reconciled.size)

        val nov25 = reconciled.first { it.sport.startsWith("Chest & Back A") }
        assertEquals(3600.0, nov25.durationS!!, 0.1)
        assertEquals(java.time.Instant.parse("2026-11-25T13:00:00Z").epochSecond, nov25.startTs)
        assertTrue(nov25.notes!!.contains("Logged with Pump"))

        val nov23 = reconciled.first { it.sport.startsWith("Legs & Core B") }
        assertEquals(existing23Start, nov23.startTs)
        assertEquals(existing23Start + 2700L, nov23.endTs)
        assertEquals(2700.0, nov23.durationS!!, 0.1)
        assertEquals(181.0, nov23.energyKcal!!, 0.1)
        assertEquals(118, nov23.avgHr)
    }

}
