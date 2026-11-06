package com.android.lorablueedge.analytics

import com.android.lorablueedge.data.TankDepthConfigStore
import com.android.lorablueedge.data.TelemetryReading
import com.android.lorablueedge.data.WaterLevelCalculator
import com.android.lorablueedge.history.TelemetryHistory
import java.io.File
import java.util.Locale

/**
 * Exports the per-window flows computed by [ConsumptionAnalyzer] to CSV, so
 * model training sees exactly what the app computes (no re-implementation of
 * the windowing in Python).
 *
 * The volume series is rebuilt from the RAW sensor distances using the CURRENT
 * depth and capacity of each reservoir, exactly like LoRaBlueService does.
 * Nodes without depth or capacity configured cannot produce volumes and are
 * skipped (reported in [Result.skippedNodes]).
 *
 * History is processed in 24 h chunks aligned to the epoch. Since 24 h is a
 * multiple of the 15 min window, no window is ever split between two chunks,
 * and memory stays small even with 90 days of readings.
 */
object WindowExporter {

    const val HEADER =
        "node_id,role,start_ms,end_ms,slope_lph,slope_noise_lph,status,depth_m,capacity_l"

    private const val CHUNK_MS = 24L * 3_600_000L

    data class Result(val windows: Int, val skippedNodes: List<Int>)

    fun export(file: File, history: TelemetryHistory, config: TankDepthConfigStore): Result {
        var written = 0
        val skipped = ArrayList<Int>()

        file.bufferedWriter().use { out ->
            out.write(HEADER)
            out.write("\n")

            for ((nodeId, nodeType) in history.nodes()) {
                val isCistern = nodeType == TelemetryReading.NODE_TYPE_CISTERN
                val role = if (isCistern) ReservoirRole.CISTERN else ReservoirRole.TANK
                val slot = if (isCistern) {
                    TelemetryReading.DEVICE_ID_CISTERN
                } else {
                    TelemetryReading.DEVICE_ID_TANK
                }

                val depth = config.getTotalDepth(slot)
                val capacity = config.getCapacityLiters(slot)
                if (depth == null || depth <= 0.0 || capacity == null) {
                    skipped.add(nodeId)
                    continue
                }

                val noiseLiters = capacity / depth * ConsumptionAnalyzer.SENSOR_NOISE_METERS
                val (first, last) = history.timeRange(nodeId) ?: continue

                var chunkStart = first / CHUNK_MS * CHUNK_MS
                while (chunkStart <= last) {
                    val points = history.queryRange(nodeId, chunkStart, chunkStart + CHUNK_MS)
                        .filter { it.waterValid }
                        .map {
                            LevelPoint(
                                timestamp = it.timestamp,
                                liters = WaterLevelCalculator.litersFor(it.sensorMeters, depth, capacity),
                                pumpOn = it.pumpOn == true
                            )
                        }

                    for (window in ConsumptionAnalyzer.flowWindows(points, noiseLiters)) {
                        out.write(toCsvLine(nodeId, role, window, depth, capacity))
                        out.write("\n")
                        written++
                    }
                    chunkStart += CHUNK_MS
                }
            }
        }
        return Result(written, skipped)
    }

    /** One CSV row. Dot as decimal separator, empty cell when a value is absent. */
    fun toCsvLine(
        nodeId: Int,
        role: ReservoirRole,
        window: FlowWindow,
        depthMeters: Double,
        capacityLiters: Double
    ): String = listOf(
        nodeId.toString(),
        role.name,
        window.startMs.toString(),
        window.endMs.toString(),
        number(window.slopeLph),
        number(window.slopeNoiseLph),
        window.status.name,
        number(depthMeters),
        number(capacityLiters)
    ).joinToString(",")

    private fun number(value: Double?): String =
        value?.let { String.format(Locale.US, "%.4f", it) } ?: ""
}