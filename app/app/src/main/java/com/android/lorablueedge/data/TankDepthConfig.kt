package com.android.lorablueedge.data

import android.content.Context
import androidx.core.content.edit

/**
 * The TOF sensor sits at the top of the tank/cistern and reports the
 * distance down to the water surface (`water_lvl` in the BLE/MQTT
 * payload). That raw distance is NOT the water column height — it's the
 * *air gap* above the water. To show a meaningful water column height,
 * a fill percentage and a volume in litres, two things must be known per
 * device, both user-configurable via the gear icon next to "Water Level":
 *
 *  - total depth (sensor-to-bottom distance, meters), and
 *  - capacity (litres the reservoir holds when the water reaches the
 *    sensor-to-bottom height) — OPTIONAL, only needed for litres and for
 *    the consumption rate in L/h.
 *
 * waterColumn = totalDepth - sensorDistance
 * percent     = waterColumn / totalDepth * 100
 * liters      = waterColumn / totalDepth * capacity
 *
 * The litres formula assumes a constant cross-section (box or vertical
 * cylinder). Tapered tanks (e.g. conical water tanks) will be approximate.
 *
 * Stored per device id (Cistern/Tank have independent physical sizes).
 * A value of 0.0 / absent means "not configured yet".
 */
class TankDepthConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("tank_depth_config", Context.MODE_PRIVATE)

    private fun key(deviceId: Int) = "total_depth_m_$deviceId"
    private fun capacityKey(deviceId: Int) = "total_capacity_l_$deviceId"

    /** Returns the configured total depth in meters for [deviceId], or null if not set. */
    fun getTotalDepth(deviceId: Int): Double? {
        val raw = prefs.getFloat(key(deviceId), -1f)
        return if (raw < 0f) null else raw.toDouble()
    }

    fun setTotalDepth(deviceId: Int, totalDepthMeters: Double) {
        prefs.edit { putFloat(key(deviceId), totalDepthMeters.toFloat()) }
    }

    /** Returns the configured capacity in litres for [deviceId], or null if not set. */
    fun getCapacityLiters(deviceId: Int): Double? {
        val raw = prefs.getFloat(capacityKey(deviceId), -1f)
        return if (raw <= 0f) null else raw.toDouble()
    }

    /** Pass null to clear the capacity (litres and L/h are then not shown). */
    fun setCapacityLiters(deviceId: Int, liters: Double?) {
        prefs.edit {
            if (liters == null) remove(capacityKey(deviceId))
            else putFloat(capacityKey(deviceId), liters.toFloat())
        }
    }
}

/**
 * Result of converting a raw TOF sensor distance into a usable water
 * column reading, once the tank/cistern total depth is known.
 *
 * [columnMeters] and [percent] are null when the total depth hasn't been
 * configured yet — there's nothing meaningful to compute. [liters] is
 * additionally null while the capacity isn't configured.
 */
data class WaterColumnReading(
    val sensorDistanceMeters: Double,
    val totalDepthMeters: Double?,
    val columnMeters: Double?,
    val percent: Double?,
    val liters: Double? = null
)

/**
 * Converts a raw sensor distance (ceiling/top sensor down to the water
 * surface) into the actual water column height, fill percentage and
 * volume, given the tank/cistern's configured total depth and capacity.
 *
 * Clamped to [0, totalDepth] so that minor sensor noise (e.g. a reading
 * slightly above an empty tank's total depth, or slightly below 0 when
 * full and the sensor overshoots) doesn't produce a negative column or a
 * percentage outside 0–100%.
 *
 * Results are rounded here (not just at display time) so that every
 * consumer — the UI cards and the MQTT JSON payload alike — gets a clean
 * number instead of raw Double artifacts like 28.000000000000004.
 * columnMeters keeps 2 decimal places, percent and liters are whole
 * numbers. For analytics that need full precision use [litersFor].
 */
object WaterLevelCalculator {

    fun compute(
        sensorDistanceMeters: Double,
        totalDepthMeters: Double?,
        capacityLiters: Double? = null
    ): WaterColumnReading {
        if (totalDepthMeters == null || totalDepthMeters <= 0.0) {
            return WaterColumnReading(
                sensorDistanceMeters = sensorDistanceMeters,
                totalDepthMeters = totalDepthMeters,
                columnMeters = null,
                percent = null,
                liters = null
            )
        }

        val rawColumn = totalDepthMeters - sensorDistanceMeters
        val column = rawColumn.coerceIn(0.0, totalDepthMeters)
        val fraction = column / totalDepthMeters

        return WaterColumnReading(
            sensorDistanceMeters = sensorDistanceMeters,
            totalDepthMeters = totalDepthMeters,
            columnMeters = column.roundTo(2),
            percent = (fraction * 100.0).roundTo(0),
            liters = if (capacityLiters != null && capacityLiters > 0.0) {
                (fraction * capacityLiters).roundTo(0)
            } else null
        )
    }

    /**
     * Unrounded volume in litres for a raw sensor distance. Used by the
     * consumption analysis, where rounding to whole litres would throw
     * away exactly the small changes it needs to see.
     */
    fun litersFor(
        sensorDistanceMeters: Double,
        totalDepthMeters: Double,
        capacityLiters: Double
    ): Double {
        val column = (totalDepthMeters - sensorDistanceMeters).coerceIn(0.0, totalDepthMeters)
        return column / totalDepthMeters * capacityLiters
    }

    private fun Double.roundTo(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}