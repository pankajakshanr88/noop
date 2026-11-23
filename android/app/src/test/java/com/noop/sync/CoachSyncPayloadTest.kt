package com.noop.sync

import com.noop.widget.HrPoint
import com.noop.widget.StressPoint
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
}
