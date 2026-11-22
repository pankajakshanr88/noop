package com.noop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpWorkoutPresentationTest {

    private fun row(
        sport: String = "Push Day",
        deviceId: String = "health-connect",
        source: String = "health-connect",
        notes: String? = null,
    ) = WorkoutRow(
        deviceId = deviceId,
        startTs = 1_700_000_000L,
        endTs = 1_700_003_600L,
        sport = sport,
        source = source,
        durationS = 3600.0,
        notes = notes,
    )

    @Test
    fun isPumpWorkout_detectsAttributionCaseInsensitively() {
        assertTrue(isPumpWorkout(row(notes = "16 sets · 14,200 lb\nLogged with Pump")))
        assertTrue(isPumpWorkout(row(notes = "logged with pump · 12 sets")))
        assertFalse(isPumpWorkout(row(notes = "Regular gym session")))
        assertFalse(isPumpWorkout(row(notes = null)))
    }

    @Test
    fun workoutBadgeLabel_givesPumpBadgeWhilePreservingHealthConnectStorage() {
        val pumpRow = row(
            sport = "Upper A",
            deviceId = "health-connect",
            source = "health-connect",
            notes = "18 sets · 12,400 kg\nLogged with Pump",
        )
        assertEquals("PUMP", workoutBadgeLabel(pumpRow))
        // Underlying storage stays "health-connect" so fillWorkoutHrFromStrap attaches WHOOP HR.
        assertEquals("HC", workoutSourceLabel(pumpRow.deviceId, pumpRow.source))

        val genericHcRow = row(sport = "Running", notes = "Morning jog")
        assertEquals("HC", workoutBadgeLabel(genericHcRow))

        val liftingRow = row(sport = "Strength Training", deviceId = "lifting", source = "lifting")
        assertEquals("LIFT", workoutBadgeLabel(liftingRow))

        val whoopRow = row(sport = "Running", deviceId = "my-whoop", source = "my-whoop")
        assertEquals("WHP", workoutBadgeLabel(whoopRow))

        val appleRow = row(sport = "Running", deviceId = "apple-health", source = "Apple Health")
        assertEquals("APL", workoutBadgeLabel(appleRow))
    }

    @Test
    fun sportIcon_alwaysReturnsDumbbellForPumpWorkoutsEvenWithCustomTitle() {
        val pushDay = row(sport = "Push Day", notes = "15 sets · 18,500 lb\nLogged with Pump")
        val upperA = row(sport = "Upper A", notes = "Logged with Pump · 12 sets")
        val runTitledPump = row(sport = "Running Warmup + Lift", notes = "Logged with Pump")

        assertEquals(Icons.Filled.FitnessCenter, sportIcon(pushDay))
        assertEquals(Icons.Filled.FitnessCenter, sportIcon(upperA))
        assertEquals(Icons.Filled.FitnessCenter, sportIcon(runTitledPump))

        val normalRun = row(sport = "Running", notes = "Outdoor 5k")
        assertEquals(Icons.AutoMirrored.Filled.DirectionsRun, sportIcon(normalRun))
    }

    @Test
    fun pumpVolumeSummary_extractsCleanSummaryAcrossFormats() {
        assertEquals(
            "16 sets · 14,200 lb",
            pumpVolumeSummary(row(notes = "16 sets · 14,200 lb\nLogged with Pump")),
        )
        assertEquals(
            "18 sets · 12,400 kg",
            pumpVolumeSummary(row(notes = "Logged with Pump · 18 sets · 12,400 kg")),
        )
        assertEquals(
            "14 sets · 9,800 lb",
            pumpVolumeSummary(row(notes = "14 sets · 9,800 lb · Logged with Pump")),
        )
        assertEquals(
            "12 sets, 10,000 lb",
            pumpVolumeSummary(row(notes = "Logged with Pump: 12 sets, 10,000 lb")),
        )
        assertNull(pumpVolumeSummary(row(notes = "Logged with Pump")))
        assertNull(pumpVolumeSummary(row(notes = "16 sets · 14,200 lb")))
    }

    @Test
    fun workoutCaption_prependsPumpSummaryWhenPresent() {
        val pumpRow = row(sport = "Push Day", notes = "16 sets · 14,200 lb\nLogged with Pump")
        val normalRow = row(sport = "Push Day", notes = "Plain note")

        val pumpCaption = workoutCaption(pumpRow)
        val normalCaption = workoutCaption(normalRow)

        assertTrue(pumpCaption.startsWith("16 sets · 14,200 lb · "))
        assertTrue(pumpCaption.endsWith(normalCaption))
        assertFalse(normalCaption.contains("Plain note"))
    }
}
