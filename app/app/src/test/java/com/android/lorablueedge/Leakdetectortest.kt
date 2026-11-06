package com.android.lorablueedge

import com.android.lorablueedge.analytics.ConsumptionAnalyzer
import com.android.lorablueedge.analytics.LeakAssessment
import com.android.lorablueedge.analytics.LeakDetector
import com.android.lorablueedge.analytics.LeakReason
import com.android.lorablueedge.analytics.LeakStatus
import com.android.lorablueedge.analytics.LevelPoint
import com.android.lorablueedge.analytics.ReservoirRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.TimeZone

/**
 * Simulated days. Time 0 is 00:00 UTC and the tests evaluate in UTC, so
 * the tank's quiet period (01:00-05:00) is hours 1..5 of the simulation.
 */
class LeakDetectorTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 1000 L reservoir, 1 m deep => 1000 L per metre => 2 mm sensor noise = 2 L.
    private val noiseLiters = 1000.0 * ConsumptionAnalyzer.SENSOR_NOISE_METERS

    private val hour = 3_600_000L
    private val interval = 10_000L // the nodes transmit every 10 s

    /**
     * Integrates [slopeAt] (L/h as a function of the hour of the
     * simulation) from [fromHour] to [toHour] and samples it every 10 s
     * with Gaussian sensor noise.
     */
    private fun simulate(
        fromHour: Double,
        toHour: Double,
        startLiters: Double,
        seed: Long,
        pumpAt: (Double) -> Boolean = { false },
        slopeAt: (Double) -> Double
    ): List<LevelPoint> {
        val rnd = Random(seed)
        val out = ArrayList<LevelPoint>()
        var liters = startLiters
        var t = (fromHour * hour).toLong()
        val end = (toHour * hour).toLong()
        while (t < end) {
            val h = t / hour.toDouble()
            out.add(LevelPoint(t, liters + rnd.nextGaussian() * 1.5, pumpAt(h)))
            liters += slopeAt(h) * interval / hour
            t += interval
        }
        return out
    }

    private fun assess(
        points: List<LevelPoint>,
        role: ReservoirRole,
        nowHour: Double
    ): LeakAssessment {
        val windows = ConsumptionAnalyzer.flowWindows(points, noiseLiters)
        return LeakDetector.evaluate(windows, role, (nowHour * hour).toLong(), utc)
    }

    /** Typical household use of a tank: morning, lunch and evening peaks. */
    private fun usage(h: Double): Double = when {
        h % 24 in 6.0..8.0 -> 40.0
        h % 24 in 12.0..13.0 -> 30.0
        h % 24 in 18.0..21.0 -> 50.0
        else -> 0.0
    }

    @Test
    fun tankWithNightLeak_isDetected() {
        val pts = simulate(0.0, 24.0, 950.0, 1) { h -> -(6.0 + usage(h)) }
        val a = assess(pts, ReservoirRole.TANK, 24.0)
        assertEquals(LeakStatus.SUSPECTED, a.status)
        assertEquals(LeakReason.QUIET_PERIOD_FLOW, a.reason)
        assertNotNull(a.estimatedLph)
        assertEquals(6.0, a.estimatedLph!!, 2.0)
    }

    @Test
    fun tankWithoutLeak_isOk() {
        val pts = simulate(0.0, 24.0, 950.0, 2) { h -> -usage(h) }
        assertEquals(LeakStatus.OK, assess(pts, ReservoirRole.TANK, 24.0).status)
    }

    @Test
    fun tooLittleData_isUnknown() {
        val pts = simulate(0.0, 1.0, 950.0, 3) { 0.0 }
        assertEquals(LeakStatus.UNKNOWN, assess(pts, ReservoirRole.TANK, 1.0).status)
    }

    @Test
    fun leakThatWasFixed_isNoLongerReported() {
        // Leaking until 03:30, flat afterwards; evaluated at 06:00.
        val pts = simulate(0.0, 6.0, 950.0, 4) { h -> if (h < 3.5) -6.0 else 0.0 }
        assertTrue(assess(pts, ReservoirRole.TANK, 6.0).status != LeakStatus.SUSPECTED)
    }

    @Test
    fun cisternDrainingWithPumpOff_isALeakAtAnyHour() {
        val pts = simulate(8.0, 14.0, 900.0, 5) { -8.0 }
        val a = assess(pts, ReservoirRole.CISTERN, 14.0)
        assertEquals(LeakStatus.SUSPECTED, a.status)
        assertEquals(8.0, a.estimatedLph!!, 2.5)
    }

    @Test
    fun cisternPumpingToTheTank_isNotALeak() {
        val pts = simulate(8.0, 14.0, 900.0, 6, pumpAt = { true }) { -60.0 }
        assertEquals(LeakStatus.UNKNOWN, assess(pts, ReservoirRole.CISTERN, 14.0).status)
    }

    @Test
    fun allDayFlowWithoutPause_isContinuousFlow() {
        val pts = simulate(0.0, 20.0, 990.0, 7) { h -> if (h in 8.0..20.0) -25.0 else 0.0 }
        val a = assess(pts, ReservoirRole.TANK, 20.0)
        assertEquals(LeakStatus.SUSPECTED, a.status)
        assertEquals(LeakReason.CONTINUOUS_FLOW, a.reason)
        assertEquals(25.0, a.estimatedLph!!, 4.0)
    }

    @Test
    fun intermittentDaytimeUse_isOk() {
        // Half an hour of use, half an hour of pause, from 06:00 to 22:00.
        val pts = simulate(0.0, 23.0, 990.0, 8) { h ->
            if (h in 6.0..22.0 && (h % 1.0) < 0.5) -40.0 else 0.0
        }
        assertEquals(LeakStatus.OK, assess(pts, ReservoirRole.TANK, 23.0).status)
    }

    @Test
    fun oneOffNightUse_isIgnored() {
        // Someone flushes at 02:00: one window at -60 L/h among a flat night.
        val pts = simulate(0.0, 8.0, 800.0, 9) { h -> if (h in 2.0..2.25) -60.0 else 0.0 }
        assertEquals(LeakStatus.OK, assess(pts, ReservoirRole.TANK, 8.0).status)
    }
}