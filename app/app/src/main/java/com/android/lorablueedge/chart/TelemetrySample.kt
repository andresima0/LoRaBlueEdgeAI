package com.android.lorablueedge.chart

/**
 * One stored sample for a single device (Cistern or Tank) at a point in
 * time. deviceId matches TelemetryReading.DEVICE_ID_CISTERN/_TANK so chart
 * queries can filter by the same id used everywhere else in the app.
 *
 * pumpOn is nullable because a single list holds samples for both device
 * types — only Cistern samples carry it.
 */
data class TelemetrySample(
    val deviceId: Int,
    val timestamp: Long,
    val waterLevel: Double,
    val batteryPercent: Double,
    val rssiDbm: Double,
    val pumpOn: Boolean? = null   // Cistern-only
)