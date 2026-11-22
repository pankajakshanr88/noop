package com.noop.analytics

import com.noop.protocol.Whoop5ImuFrame
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure 100 Hz IMU live step detector for WHOOP 5.0/MG realtime raw-IMU frames (T6).
 *
 * Consumes [Whoop5ImuFrame] (1 s of 100 Hz 6-axis samples, strap base timestamp) and credits steps
 * from dynamic-acceleration peaks, guarded against arm flail by a gyroscope RMS check and against
 * desk taps by a 3-step gait-bout gate. Pure/deterministic: no Android, no I/O, no strap — unit-tested
 * on the JVM like the rest of `com.noop.analytics`.
 *
 * Pipeline per sample: dynamic acceleration `abs(|a| - 1g)` -> 5-sample moving average -> local peak
 * above [MIN_PEAK_G] -> gyro RMS guard -> step-interval bounds -> bout gate -> credited step.
 */
class LiveImuStepDetector {

    /** Immutable read-out of the detector state. */
    data class Snapshot(
        val totalSteps: Int,
        val cadenceSpm: Int?,
        val lastStepAtMs: Long?,
        val framesProcessed: Int,
    )

    companion object {
        /** Minimum smoothed dynamic-acceleration peak (g above the 1 g gravity shell). */
        const val MIN_PEAK_G = 0.16
        /** Local gyro RMS at/above this (deg/s) rejects the candidate peak (wild arm flailing). */
        const val GYRO_REJECT_DEG_S = 500.0
        /** Step-to-step interval bounds: 280 ms .. 1400 ms (43..214 steps/min). */
        const val MIN_INTERVAL_S = 0.280
        const val MAX_INTERVAL_S = 1.400
        /** Steps buffered before a gait bout is confirmed and credited. */
        const val BOUT_STEPS = 3
        /** Frame gap (s) beyond which bout/filter state resets (totalSteps is kept). */
        const val GAP_RESET_S = 3.0
        /** Moving-average window (samples) at 100 Hz. */
        const val SMOOTH_WINDOW = 5
    }

    private var totalSteps = 0
    private var framesProcessed = 0
    private var lastStepAtMs: Long? = null
    private var prevCreditedStepTs: Double? = null
    private var lastCreditedStepTs: Double? = null

    private var lastBaseTs: Long? = null
    private val smooth = ArrayDeque<Double>()
    private var s1: Double? = null   // smoothed value at sample i-1
    private var s2: Double? = null   // smoothed value at sample i-2
    private val gyroWindow = ArrayDeque<Double>()
    private val pending = mutableListOf<Double>()   // candidate step times in the current bout
    private var lastCandidateTs: Double? = null
    private var boutActive = false

    /** Feed one decoded IMU frame. Duplicate or out-of-order frames (`baseTs <= lastBaseTs`) are
     *  ignored; a gap > [GAP_RESET_S] resets bout/filter state but keeps [totalSteps]. */
    @Synchronized
    fun accept(frame: Whoop5ImuFrame): Snapshot {
        val last = lastBaseTs
        if (last != null && frame.baseTs <= last) return snapshot()
        if (last != null && frame.baseTs - last > GAP_RESET_S) resetBoutState()
        lastBaseTs = frame.baseTs
        framesProcessed++
        val hz = maxOf(1, frame.sampleRateHz)
        for (i in 0 until frame.samples.size) {
            val s = frame.samples[i]
            val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            val dyn = abs(mag - 1.0)
            smooth.addLast(dyn)
            if (smooth.size > SMOOTH_WINDOW) smooth.removeFirst()
            val smoothed = smooth.sum() / smooth.size
            val gyroMag = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
            gyroWindow.addLast(gyroMag)
            if (gyroWindow.size > SMOOTH_WINDOW) gyroWindow.removeFirst()
            val t = frame.baseTs.toDouble() + i.toDouble() / hz
            // Local peak: the previous smoothed value is a local maximum at/above threshold.
            val prev = s1
            val prevPrev = s2
            if (prev != null && prevPrev != null && prev >= prevPrev && prev > smoothed && prev >= MIN_PEAK_G) {
                val peakT = t - 1.0 / hz
                val rms = sqrt(gyroWindow.sumOf { it * it } / gyroWindow.size)
                if (rms < GYRO_REJECT_DEG_S) onCandidate(peakT)
            }
            s2 = s1
            s1 = smoothed
        }
        return snapshot()
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val cadence = if (prevCreditedStepTs != null && lastCreditedStepTs != null) {
            val dt = lastCreditedStepTs!! - prevCreditedStepTs!!
            if (dt in MIN_INTERVAL_S..MAX_INTERVAL_S) (60.0 / dt).roundToInt() else null
        } else null
        return Snapshot(totalSteps, cadence, lastStepAtMs, framesProcessed)
    }

    /** Zero all counts and state. */
    @Synchronized
    fun reset(): Snapshot {
        resetBoutState()
        totalSteps = 0
        framesProcessed = 0
        lastStepAtMs = null
        prevCreditedStepTs = null
        lastCreditedStepTs = null
        lastBaseTs = null
        return snapshot()
    }

    /** Reset only the bout/filter state (gap > [GAP_RESET_S]); [totalSteps] and [framesProcessed] survive. */
    private fun resetBoutState() {
        pending.clear()
        lastCandidateTs = null
        boutActive = false
        smooth.clear()
        gyroWindow.clear()
        s1 = null
        s2 = null
    }

    private fun onCandidate(t: Double) {
        val last = lastCandidateTs
        if (last != null) {
            val dt = t - last
            if (dt in MIN_INTERVAL_S..MAX_INTERVAL_S) {
                if (boutActive) {
                    creditStep(t)
                } else {
                    pending.add(t)
                    if (pending.size >= BOUT_STEPS) {
                        for (pt in pending) creditStep(pt)
                        pending.clear()
                        boutActive = true
                    }
                }
            } else {
                // Interval out of bounds: the bout is broken; start a fresh buffer with this candidate.
                boutActive = false
                pending.clear()
                pending.add(t)
            }
        } else {
            pending.clear()
            pending.add(t)
        }
        lastCandidateTs = t
    }

    private fun creditStep(t: Double) {
        prevCreditedStepTs = lastCreditedStepTs
        lastCreditedStepTs = t
        totalSteps++
        lastStepAtMs = (t * 1000.0).toLong()
    }
}
