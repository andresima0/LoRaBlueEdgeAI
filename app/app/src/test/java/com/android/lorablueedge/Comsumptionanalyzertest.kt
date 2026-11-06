package com.android.lorablueedge

import com.android.lorablueedge.analytics.ConsumptionAnalyzer
import com.android.lorablueedge.analytics.LevelPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ConsumptionAnalyzerTest {

    // 1000 L reservoir, 1 m deep => 1000 L per metre => 2 mm sensor noise = 2 L.
    private val noiseLiters = 1000.0 * ConsumptionAnalyzer.SENSOR_NOISE_METERS

    private val hour = 3_600_000L
    private val interval = 10_000L // the nodes transmit every 10 s

    /** Samples every [interval] ms with a constant slope plus Gaussian noise. */
    private fun series(
        startMs: Long,
        durationMs: Long,
        startLiters: Double,
        slopeLph: Double,
        rnd: Random,
        pumpOn: Boolean = false,
        sigma: Double = 1.5
    ): List<LevelPoint> {
        val out = ArrayList<LevelPoint>()
        var t = startMs
        while (t < startMs + durationMs) {
            val hours = (t - startMs) / hour.toDouble()
            out.add(LevelPoint(t, startLiters + slopeLph * hours + rnd.nextGaussian() * sigma, pumpOn))
            t += interval
        }
        return out
    }

    @Test
    fun steadyDrain_isMeasured() {
        val pts = series(0, 3 * hour, 800.0, -10.0, Random(1))
        val stats = ConsumptionAnalyzer.analyze(pts, noiseLiters)
        assertNotNull(stats.rateLph)
        assertEquals(10.0, stats.rateLph!!, 1.5)
        assertFalse(stats.fillingSeen)
    }

    @Test
    fun idleReservoir_hasNearZeroConsumption() {
        val pts = series(0, 3 * hour, 500.0, 0.0, Random(2))
        val stats = ConsumptionAnalyzer.analyze(pts, noiseLiters)
        assertNotNull(stats.rateLph)
        assertEquals(0.0, stats.rateLph!!, 1.5)
    }

    @Test
    fun filling_isExcludedNotCountedAsConsumption() {
        val pts = series(0, hour, 200.0, +200.0, Random(3))
        val stats = ConsumptionAnalyzer.analyze(pts, noiseLiters)
        assertNull(stats.rateLph)
        assertTrue(stats.fillingSeen)
    }

    @Test
    fun pumpOn_windowsAreExcluded() {
        val pts = series(0, hour, 900.0, -60.0, Random(4), pumpOn = true)
        assertNull(ConsumptionAnalyzer.analyze(pts, noiseLiters).rateLph)
    }

    @Test
    fun dataHoles_invalidateWindows() {
        // Each 15-min window loses 5 minutes in the middle.
        val rnd = Random(5)
        val pts = ArrayList<LevelPoint>()
        for (w in 0 until 8) {
            val start = w * 15 * 60_000L
            pts += series(start, 5 * 60_000L, 800.0, -10.0, rnd)
            pts += series(start + 10 * 60_000L, 5 * 60_000L, 800.0, -10.0, rnd)
        }
        assertNull(ConsumptionAnalyzer.analyze(pts, noiseLiters).rateLph)
    }

    @Test
    fun tooLittleData_isUnknown() {
        val pts = series(0, 20 * 60_000L, 800.0, -10.0, Random(6))
        assertNull(ConsumptionAnalyzer.analyze(pts, noiseLiters).rateLph)
    }

    @Test
    fun drainFillDrain_fillingIsSkippedAndDrainIsKept() {
        val rnd = Random(7)
        val pts = ArrayList<LevelPoint>()
        pts += series(0, 90 * 60_000L, 800.0, -12.0, rnd)              // consuming
        pts += series(90 * 60_000L, 60 * 60_000L, 782.0, +250.0, rnd)  // refilling
        pts += series(150 * 60_000L, 90 * 60_000L, 900.0, -12.0, rnd)  // consuming again
        val stats = ConsumptionAnalyzer.analyze(pts, noiseLiters)
        assertNotNull(stats.rateLph)
        assertEquals(12.0, stats.rateLph!!, 2.0)
        assertTrue(stats.fillingSeen)
    }

    @Test
    fun summarize_separatesLastHourFromLastDay() {
        val rnd = Random(8)
        val now = 24 * hour
        val pts = ArrayList<LevelPoint>()
        pts += series(0, 23 * hour, 900.0, 0.0, rnd)                  // idle most of the day
        pts += series(23 * hour, hour, 900.0, -30.0, rnd)             // heavy use in the last hour
        val summary = ConsumptionAnalyzer.summarize(pts, noiseLiters, now)
        assertNotNull(summary.last1h.rateLph)
        assertNotNull(summary.last24h.rateLph)
        assertEquals(30.0, summary.last1h.rateLph!!, 4.0)
        assertTrue(summary.last24h.rateLph!! < summary.last1h.rateLph!!)
    }
}