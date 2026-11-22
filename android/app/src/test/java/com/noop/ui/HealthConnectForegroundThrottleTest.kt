package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectForegroundThrottleTest {

    @Test
    fun throttleWindow_isSixtySeconds() {
        assertEquals(60_000L, HC_FOREGROUND_THROTTLE_MS)
    }

    @Test
    fun firstForegroundResume_runsWhenAutoSyncEnabled() {
        assertTrue(
            shouldRunForegroundHcSync(
                autoSyncEnabled = true,
                inFlight = false,
                lastAttemptMs = 0L,
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun disabledAutoSync_blocksSync() {
        assertFalse(
            shouldRunForegroundHcSync(
                autoSyncEnabled = false,
                inFlight = false,
                lastAttemptMs = 0L,
                nowMs = 100_000L,
            ),
        )
    }

    @Test
    fun inFlightImport_blocksDuplicateSync() {
        assertFalse(
            shouldRunForegroundHcSync(
                autoSyncEnabled = true,
                inFlight = true,
                lastAttemptMs = 0L,
                nowMs = 100_000L,
            ),
        )
    }

    @Test
    fun withinSixtySeconds_isThrottled_andAtSixtySeconds_runsAgain() {
        val last = 1_000_000L
        assertFalse(
            shouldRunForegroundHcSync(
                autoSyncEnabled = true,
                inFlight = false,
                lastAttemptMs = last,
                nowMs = last,
            ),
        )
        assertFalse(
            shouldRunForegroundHcSync(
                autoSyncEnabled = true,
                inFlight = false,
                lastAttemptMs = last,
                nowMs = last + 30_000L,
            ),
        )
        assertFalse(
            shouldRunForegroundHcSync(
                autoSyncEnabled = true,
                inFlight = false,
                lastAttemptMs = last,
                nowMs = last + 59_999L,
            ),
        )
        assertTrue(
            shouldRunForegroundHcSync(
                autoSyncEnabled = true,
                inFlight = false,
                lastAttemptMs = last,
                nowMs = last + 60_000L,
            ),
        )
        assertTrue(
            shouldRunForegroundHcSync(
                autoSyncEnabled = true,
                inFlight = false,
                lastAttemptMs = last,
                nowMs = last + 120_000L,
            ),
        )
    }

    @Test
    fun backwardsClockJump_doesNotWedgeThrottle() {
        assertTrue(
            shouldRunForegroundHcSync(
                autoSyncEnabled = true,
                inFlight = false,
                lastAttemptMs = 1_000_000L,
                nowMs = 900_000L,
            ),
        )
    }
}
