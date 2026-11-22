package com.noop.analytics

import com.noop.protocol.RawImuSample
import com.noop.protocol.Whoop5ImuFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveImuStepDetectorTest {

    // --- helpers ------------------------------------------------------------

    private fun sample(ax: Double, ay: Double, az: Double, gx: Double = 0.0, gy: Double = 0.0, gz: Double = 0.0) =
        RawImuSample(ax, ay, az, gx, gy, gz)

    /** One 1-second frame at 100 Hz. [wave] maps absolute sample time (s) to the accel z offset (g). */
    private fun frame(baseTs: Long, wave: (t: Double) -> Double, gyro: (t: Double) -> Double = { 0.0 }): Whoop5ImuFrame {
        val samples = (0 until 100).map { i ->
            val t = baseTs.toDouble() + i / 100.0
            sample(0.0, 0.0, 1.0 + wave(t), gyro(t), gyro(t), gyro(t))
        }
        return Whoop5ImuFrame(baseTs = baseTs, sampleRateHz = 100, samples = samples)
    }

    /** Half-wave-rectified walking waveform at [spm] steps/min with [amp] g peaks (one peak per step). */
    private fun walkWave(spm: Double, amp: Double): (Double) -> Double {
        val f = spm / 60.0
        return { t -> amp * kotlin.math.max(0.0, kotlin.math.sin(2.0 * kotlin.math.PI * f * t)) }
    }

    private fun feedWalking(detector: LiveImuStepDetector, baseTsStart: Long, seconds: Int, spm: Double, amp: Double = 0.25): LiveImuStepDetector.Snapshot {
        var snap = detector.snapshot()
        for (s in 0 until seconds) {
            snap = detector.accept(frame(baseTsStart + s, walkWave(spm, amp)))
        }
        return snap
    }

    // --- tests ---------------------------------------------------------------

    @Test
    fun walkingWaveform_countsExpectedStepsAndCadence() {
        // 100 spm -> one step every 0.6 s = exactly 60 samples. 12 s of walking -> 20 peaks.
        val detector = LiveImuStepDetector()
        val snap = feedWalking(detector, baseTsStart = 1_700_000_000, seconds = 12, spm = 100.0)
        assertEquals(20, snap.totalSteps)
        assertEquals(12, snap.framesProcessed)
        assertNotNull(snap.cadenceSpm)
        assertEquals(100, snap.cadenceSpm)
        assertNotNull(snap.lastStepAtMs)
    }

    @Test
    fun walkingWaveform_110spm_countsWithinExpectedRange() {
        // ~110 spm -> interval ~0.545 s; 10 s -> ~18 steps.
        val detector = LiveImuStepDetector()
        val snap = feedWalking(detector, baseTsStart = 1_700_000_000, seconds = 10, spm = 110.0)
        assertTrue("expected ~18 steps, got ${snap.totalSteps}", snap.totalSteps in 16..19)
        assertNotNull(snap.cadenceSpm)
        assertTrue("cadence ${snap.cadenceSpm} not near 110", snap.cadenceSpm!! in 105..115)
    }

    @Test
    fun isolatedImpulses_areSuppressedByBoutGate() {
        val detector = LiveImuStepDetector()
        // One desk tap: a single 0.3 g bump in the middle of an otherwise still second.
        detector.accept(frame(1_700_000_000, wave = { t -> if (t - 1_700_000_000.0 in 0.45..0.55) 0.3 else 0.0 }))
        // A second tap 2 s later (interval 2 s > 1400 ms bound): still no bout.
        detector.accept(frame(1_700_000_002, wave = { t -> if (t - 1_700_000_002.0 in 0.45..0.55) 0.3 else 0.0 }))
        val snap = detector.snapshot()
        assertEquals(0, snap.totalSteps)
        assertNull(snap.cadenceSpm)
        assertEquals(2, snap.framesProcessed)
    }

    @Test
    fun highGyroFlail_isRejected() {
        val detector = LiveImuStepDetector()
        // Same walking waveform, but gyro RMS >= 500 deg/s everywhere.
        var snap = detector.snapshot()
        for (s in 0 until 10) {
            snap = detector.accept(frame(1_700_000_000L + s, walkWave(100.0, 0.25)) { 600.0 })
        }
        assertEquals(0, snap.totalSteps)
        assertEquals(10, snap.framesProcessed)
    }

    @Test
    fun duplicateBaseTsFrames_areIgnored() {
        val detector = LiveImuStepDetector()
        val f = frame(1_700_000_000, walkWave(100.0, 0.25))
        val first = detector.accept(f)
        val dup = detector.accept(f)   // same baseTs -> ignored
        assertEquals(first.framesProcessed, dup.framesProcessed)
        assertEquals(first.totalSteps, dup.totalSteps)
        // Out-of-order baseTs also ignored.
        val older = detector.accept(frame(1_699_999_999L, walkWave(100.0, 0.25)))
        assertEquals(first.framesProcessed, older.framesProcessed)
    }

    @Test
    fun gapOver3s_resetsBoutButKeepsTotalSteps() {
        val detector = LiveImuStepDetector()
        // 6 s of walking at 100 spm -> 10 steps credited.
        val walked = feedWalking(detector, baseTsStart = 1_700_000_000, seconds = 6, spm = 100.0)
        assertEquals(10, walked.totalSteps)
        // 10 s gap (> 3 s): bout/filter state resets, totalSteps preserved.
        val afterGap = detector.accept(frame(1_700_000_016, wave = { 0.0 }))
        assertEquals(10, afterGap.totalSteps)
        assertEquals(7, afterGap.framesProcessed)
        // Two isolated taps after the gap must NOT count (bout was reset, gate re-arms).
        detector.accept(frame(1_700_000_017, wave = { t -> if (t - 1_700_000_017.0 in 0.45..0.55) 0.3 else 0.0 }))
        val snap = detector.accept(frame(1_700_000_019, wave = { t -> if (t - 1_700_000_019.0 in 0.45..0.55) 0.3 else 0.0 }))
        assertEquals(10, snap.totalSteps)
    }

    @Test
    fun reset_zeroesCounts() {
        val detector = LiveImuStepDetector()
        feedWalking(detector, baseTsStart = 1_700_000_000, seconds = 6, spm = 100.0)
        val snap = detector.reset()
        assertEquals(0, snap.totalSteps)
        assertNull(snap.cadenceSpm)
        assertNull(snap.lastStepAtMs)
        assertEquals(0, snap.framesProcessed)
        // And the detector works again afterwards.
        val again = feedWalking(detector, baseTsStart = 1_700_000_100, seconds = 6, spm = 100.0)
        assertEquals(10, again.totalSteps)
    }

    @Test
    fun snapshot_isConsistentWithoutNewFrames() {
        val detector = LiveImuStepDetector()
        val snap = feedWalking(detector, baseTsStart = 1_700_000_000, seconds = 6, spm = 100.0)
        val again = detector.snapshot()
        assertEquals(snap.totalSteps, again.totalSteps)
        assertEquals(snap.cadenceSpm, again.cadenceSpm)
        assertEquals(snap.framesProcessed, again.framesProcessed)
        assertEquals(snap.lastStepAtMs, again.lastStepAtMs)
    }
}
