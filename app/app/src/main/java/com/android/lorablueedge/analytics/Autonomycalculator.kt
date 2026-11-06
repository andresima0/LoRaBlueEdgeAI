package com.android.lorablueedge.analytics

import kotlin.math.max
import kotlin.math.min

/**
 * One step of the weather forecast. OpenWeather's 5-day forecast has 3-hour
 * steps; "dt" is the END of the step and "rain.3h" the rain volume of the
 * 3 hours before it. [rain3hMm] is null when the API omits "rain" (no rain).
 */
data class ForecastSlot(
    val endEpochSec: Long,
    val rain3hMm: Double?,
    val pop: Double?          // probability of precipitation, 0..1
)

/** Rain expected in the next 24 h, as last fetched from the weather API. */
data class RainForecast(
    val expectedMm24h: Double,
    val fetchedAtMs: Long
)

object RainForecastCalculator {

    private const val SLOT_MS = 3 * 3_600_000L
    private const val HORIZON_MS = 24 * 3_600_000L

    /**
     * Expected rain (mm) between [nowMs] and [nowMs] + 24 h. Each step is
     * weighted by the fraction of it that falls inside that window (the
     * step in progress and the last one are only partly inside) and by its
     * probability of precipitation, so a 70 % chance of 10 mm counts as 7 mm.
     * This is deliberately conservative: it avoids crediting rain that is
     * only a possibility.
     */
    fun expectedRainMm(slots: List<ForecastSlot>, nowMs: Long): Double {
        var total = 0.0
        for (slot in slots) {
            val rain = slot.rain3hMm ?: continue
            if (rain <= 0.0) continue

            val end = slot.endEpochSec * 1000
            val start = end - SLOT_MS
            val overlap = min(end, nowMs + HORIZON_MS) - max(start, nowMs)
            if (overlap <= 0) continue

            val probability = (slot.pop ?: 1.0).coerceIn(0.0, 1.0)
            total += rain * probability * overlap / SLOT_MS
        }
        return total
    }
}

/**
 * @property days          Days until the stored water runs out at the current
 *                         demand (rain credit included), or null when the
 *                         demand is unknown or too low to give a meaningful
 *                         figure.
 * @property storedLiters  Water in the reservoirs right now.
 * @property harvestLiters Rain expected to reach the cistern (already
 *                         limited by its free space).
 * @property demandLph     Demand used, in litres per hour.
 */
data class AutonomyEstimate(
    val days: Double?,
    val storedLiters: Double,
    val harvestLiters: Double,
    val demandLph: Double?
) {
    companion object {
        val UNKNOWN = AutonomyEstimate(null, 0.0, 0.0, null)
    }
}

/**
 * Fuses the current volume, the measured demand and the rain forecast
 * into the "days of water autonomy" figure (RF07).
 *
 * It is plain arithmetic on purpose: every term can be inspected and
 * explained to the customer, which a regressor trained on synthetic data
 * could not offer.
 *
 *   rain harvested = rain (mm) x roof area (m2) x runoff coefficient
 *                    (1 mm on 1 m2 is 1 L), capped at the cistern's free space
 *   autonomy       = (stored + harvested) / (demand x 24 h)
 */
object AutonomyCalculator {

    /** Share of the rain on the roof that actually reaches the cistern. */
    const val RUNOFF_COEFFICIENT = 0.8

    /** Below this the reservoir is effectively idle and "days left" is meaningless. */
    const val MIN_DEMAND_LPH = 1.0

    fun harvestLiters(
        expectedRainMm: Double,
        roofAreaM2: Double?,
        cisternFreeLiters: Double?
    ): Double {
        if (roofAreaM2 == null || roofAreaM2 <= 0.0 || cisternFreeLiters == null) return 0.0
        val collected = expectedRainMm * roofAreaM2 * RUNOFF_COEFFICIENT
        return min(collected, max(0.0, cisternFreeLiters))
    }

    fun estimate(storedLiters: Double, demandLph: Double?, harvestLiters: Double): AutonomyEstimate {
        if (demandLph == null || demandLph < MIN_DEMAND_LPH) {
            return AutonomyEstimate(null, storedLiters, harvestLiters, demandLph)
        }
        val days = (storedLiters + harvestLiters) / (demandLph * 24.0)
        return AutonomyEstimate(days, storedLiters, harvestLiters, demandLph)
    }
}