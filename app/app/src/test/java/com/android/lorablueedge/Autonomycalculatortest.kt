package com.android.lorablueedge

import com.android.lorablueedge.analytics.AutonomyCalculator
import com.android.lorablueedge.analytics.ForecastSlot
import com.android.lorablueedge.analytics.RainForecastCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutonomyCalculatorTest {

    private val hour = 3_600_000L
    private val now = 1_000_000L * hour        // arbitrary "now", aligned to an hour

    private fun slotEndingIn(hours: Double, rain: Double?, pop: Double? = 1.0) =
        ForecastSlot(((now + hours * hour) / 1000).toLong(), rain, pop)

    @Test
    fun noRainInForecast_isZero() {
        val slots = listOf(slotEndingIn(3.0, null), slotEndingIn(6.0, null))
        assertEquals(0.0, RainForecastCalculator.expectedRainMm(slots, now), 1e-9)
    }

    @Test
    fun probabilityScalesTheRain() {
        // 10 mm with 70 % chance, fully inside the next 24 h => 7 mm.
        val slots = listOf(slotEndingIn(9.0, 10.0, 0.7))
        assertEquals(7.0, RainForecastCalculator.expectedRainMm(slots, now), 1e-6)
    }

    @Test
    fun stepInProgress_countsOnlyItsRemainingPart() {
        // The step ends in 1.5 h, so half of it is still ahead.
        val slots = listOf(slotEndingIn(1.5, 4.0, 1.0))
        assertEquals(2.0, RainForecastCalculator.expectedRainMm(slots, now), 1e-6)
    }

    @Test
    fun stepBeyondTheHorizon_isIgnoredAndTheEdgeOneIsCutShort() {
        val slots = listOf(
            slotEndingIn(25.5, 6.0, 1.0),   // 1.5 h of it is inside 24 h => half
            slotEndingIn(30.0, 50.0, 1.0)   // entirely beyond
        )
        assertEquals(3.0, RainForecastCalculator.expectedRainMm(slots, now), 1e-6)
    }

    @Test
    fun pastStep_isIgnored() {
        val slots = listOf(slotEndingIn(-1.0, 20.0, 1.0))
        assertEquals(0.0, RainForecastCalculator.expectedRainMm(slots, now), 1e-9)
    }

    @Test
    fun harvest_isRainTimesRoofTimesRunoff() {
        // 10 mm x 50 m2 x 0.8 = 400 L, with room for it.
        assertEquals(400.0, AutonomyCalculator.harvestLiters(10.0, 50.0, 1000.0), 1e-6)
    }

    @Test
    fun harvest_isCappedByTheCisternFreeSpace() {
        assertEquals(150.0, AutonomyCalculator.harvestLiters(10.0, 50.0, 150.0), 1e-6)
    }

    @Test
    fun harvest_isZeroWithoutRoofAreaOrCistern() {
        assertEquals(0.0, AutonomyCalculator.harvestLiters(10.0, null, 1000.0), 1e-9)
        assertEquals(0.0, AutonomyCalculator.harvestLiters(10.0, 50.0, null), 1e-9)
    }

    @Test
    fun autonomy_isStoredOverDailyDemand() {
        // 600 L at 25 L/h (600 L/day) => 1 day; +120 L of rain => 1.2 days.
        assertEquals(1.0, AutonomyCalculator.estimate(600.0, 25.0, 0.0).days!!, 1e-9)
        assertEquals(1.2, AutonomyCalculator.estimate(600.0, 25.0, 120.0).days!!, 1e-9)
    }

    @Test
    fun idleOrUnknownDemand_hasNoAutonomyFigure() {
        assertNull(AutonomyCalculator.estimate(600.0, 0.2, 0.0).days)
        assertNull(AutonomyCalculator.estimate(600.0, null, 0.0).days)
        assertNotNull(AutonomyCalculator.estimate(600.0, 2.0, 0.0).days)
    }
}