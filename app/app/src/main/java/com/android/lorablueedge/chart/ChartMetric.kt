package com.android.lorablueedge.chart

import com.android.lorablueedge.data.TelemetryReading

/**
 * One selectable series for the chart screen's metric spinner. label is
 * what the spinner shows; extractValue pulls the right Double out of a
 * TelemetrySample for that metric (booleans like pumpOn are mapped to
 * 0.0/1.0 so they can still be plotted on the same LineChart).
 */
enum class ChartMetric(val label: String) {
    WATER_LEVEL("Water Level (m)") {
        override fun extractValue(sample: TelemetrySample) = sample.waterLevel
    },
    BATTERY("Battery (%)") {
        override fun extractValue(sample: TelemetrySample) = sample.batteryPercent
    },
    RSSI("RSSI (dBm)") {
        override fun extractValue(sample: TelemetrySample) = sample.rssiDbm
    },
    PUMP_STATUS("Water Pump (ON/OFF)") {
        override fun extractValue(sample: TelemetrySample) =
            if (sample.pumpOn == true) 1.0 else 0.0
    };

    abstract fun extractValue(sample: TelemetrySample): Double

    companion object {
        /**
         * Only the Cistern has a pump; the Tank offers just the metrics
         * shared by every node.
         */
        fun forDevice(deviceId: Int): List<ChartMetric> = when (deviceId) {
            TelemetryReading.DEVICE_ID_CISTERN -> listOf(WATER_LEVEL, PUMP_STATUS, BATTERY, RSSI)
            TelemetryReading.DEVICE_ID_TANK -> listOf(WATER_LEVEL, BATTERY, RSSI)
            else -> emptyList()
        }
    }
}