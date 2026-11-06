package com.android.lorablueedge.analytics

import kotlin.math.max
import kotlin.math.sqrt

/** One water-volume sample of a reservoir. */
data class LevelPoint(
    val timestamp: Long,
    val liters: Double,
    val pumpOn: Boolean = false
)

/** Why a window could (or could not) be used to measure consumption. */
enum class WindowStatus {
    /** Clean data, not being filled: its slope is a trustworthy net flow. */
    USABLE,

    /** Volume clearly rising: the reservoir is being filled. */
    FILLING,

    /** The pump was on at some moment: a transfer, not consumption. */
    PUMP_ON,

    /** Too few samples, a hole in the data, or the window is only partly covered. */
    INSUFFICIENT_DATA
}

/**
 * Net flow of one fixed time window.
 *
 * @property slopeLph       Volume trend in L/h (negative = draining). Null
 *                          for [WindowStatus.PUMP_ON] and
 *                          [WindowStatus.INSUFFICIENT_DATA].
 * @property slopeNoiseLph  1-sigma uncertainty of [slopeLph] caused by the
 *                          sensor noise (same null rule).
 */
data class FlowWindow(
    val startMs: Long,
    val endMs: Long,
    val slopeLph: Double?,
    val slopeNoiseLph: Double?,
    val status: WindowStatus
) {
    val midMs: Long get() = (startMs + endMs) / 2
    val hours: Double get() = (endMs - startMs) / 3_600_000.0
}

/**
 * Result of analysing one lookback period.
 *
 * @property rateLph        Average consumption in litres per hour, or null
 *                          when there is not enough clean data yet.
 * @property usableHours    Hours of data that actually contributed.
 * @property fillingSeen    True if some windows were discarded because the
 *                          reservoir was being filled during them.
 */
data class ConsumptionStats(
    val rateLph: Double?,
    val usableHours: Double,
    val fillingSeen: Boolean
) {
    companion object {
        val UNKNOWN = ConsumptionStats(null, 0.0, false)
    }
}

/** Short-term and long-term consumption of one reservoir. */
data class ConsumptionSummary(
    val last1h: ConsumptionStats,
    val last24h: ConsumptionStats
) {
    companion object {
        val UNKNOWN = ConsumptionSummary(ConsumptionStats.UNKNOWN, ConsumptionStats.UNKNOWN)
    }
}

/**
 * Estimates the consumption rate (RF04) of a reservoir from its volume
 * history, ignoring everything that is not consumption.
 *
 * Method: the history is cut into fixed windows (15 min) ALIGNED TO THE
 * CLOCK (:00, :15, :30, :45), so re-running the analysis a minute later
 * yields the same windows instead of shifted ones. In each window a
 * least-squares line is fitted to volume vs time; its slope is the net
 * flow in L/h. A window is not [WindowStatus.USABLE] when it cannot be
 * trusted:
 *   - too few samples, a hole in the data, or only partly covered;
 *   - the pump was on at any moment (the cistern is being emptied into
 *     the tank — a transfer, not consumption);
 *   - the volume is clearly RISING (slope above [Config.fillSigma] times
 *     the slope noise): the reservoir is being filled, so consumption
 *     cannot be observed.
 * The rate is the negated MEAN of the usable slopes. Averaging signed
 * slopes (instead of summing only the drops) keeps sensor noise from
 * biasing the result upwards, and idle windows (slope ~ 0) correctly pull
 * the average down.
 *
 * [flowWindows] exposes the per-window result, which [LeakDetector] uses.
 * Pure Kotlin with no Android dependency, so it is covered by plain JVM
 * unit tests.
 */
object ConsumptionAnalyzer {

    /** Assumed 1-sigma error of the (median-filtered) level sensor. */
    const val SENSOR_NOISE_METERS = 0.002

    data class Config(
        val windowMs: Long = 15 * 60_000L,
        val minPointsPerWindow: Int = 10,
        val maxGapMs: Long = 3 * 60_000L,
        val minUsableWindows: Int = 2,
        val fillSigma: Double = 3.0
    )

    /**
     * Classifies every clock-aligned window that contains at least one
     * sample, oldest first.
     *
     * @param noiseLiters 1-sigma volume noise of one sample, i.e.
     *                    litres-per-metre of height x [SENSOR_NOISE_METERS].
     */
    fun flowWindows(
        points: List<LevelPoint>,
        noiseLiters: Double,
        config: Config = Config()
    ): List<FlowWindow> {
        if (points.isEmpty()) return emptyList()

        return points
            .sortedBy { it.timestamp }
            .groupBy { it.timestamp / config.windowMs }
            .toSortedMap()
            .map { (index, window) ->
                val start = index * config.windowMs
                val end = start + config.windowMs

                fun result(status: WindowStatus, slope: Double? = null, noise: Double? = null) =
                    FlowWindow(start, end, slope, noise, status)

                when {
                    window.size < config.minPointsPerWindow ->
                        result(WindowStatus.INSUFFICIENT_DATA)

                    window.any { it.pumpOn } ->
                        result(WindowStatus.PUMP_ON)

                    hasGap(window, config.maxGapMs) ->
                        result(WindowStatus.INSUFFICIENT_DATA)

                    window.last().timestamp - window.first().timestamp < config.windowMs * 0.8 ->
                        result(WindowStatus.INSUFFICIENT_DATA) // partial window

                    else -> {
                        val fit = fitSlope(window)
                        if (fit == null) {
                            result(WindowStatus.INSUFFICIENT_DATA)
                        } else {
                            // Slope noise: sigma / sqrt(Sxx), with x in hours.
                            val slopeNoise = noiseLiters / sqrt(fit.sxx)
                            val status = if (fit.slopeLph > config.fillSigma * slopeNoise) {
                                WindowStatus.FILLING
                            } else {
                                WindowStatus.USABLE
                            }
                            result(status, fit.slopeLph, slopeNoise)
                        }
                    }
                }
            }
    }

    fun analyze(
        points: List<LevelPoint>,
        noiseLiters: Double,
        config: Config = Config()
    ): ConsumptionStats {
        val windows = flowWindows(points, noiseLiters, config)
        val usable = windows.filter { it.status == WindowStatus.USABLE }

        val usableHours = usable.size * config.windowMs / 3_600_000.0
        val fillingSeen = windows.any { it.status == WindowStatus.FILLING }
        val rate = if (usable.size >= config.minUsableWindows) {
            max(0.0, -usable.sumOf { it.slopeLph!! } / usable.size)
        } else null

        return ConsumptionStats(rate, usableHours, fillingSeen)
    }

    /** Last hour and last 24 h, both ending at [nowMs]. */
    fun summarize(
        points: List<LevelPoint>,
        noiseLiters: Double,
        nowMs: Long,
        config: Config = Config()
    ): ConsumptionSummary {
        val hourAgo = nowMs - 3_600_000L
        val dayAgo = nowMs - 24 * 3_600_000L
        return ConsumptionSummary(
            last1h = analyze(points.filter { it.timestamp >= hourAgo }, noiseLiters, config),
            last24h = analyze(points.filter { it.timestamp >= dayAgo }, noiseLiters, config)
        )
    }

    private fun hasGap(window: List<LevelPoint>, maxGapMs: Long): Boolean {
        for (i in 1 until window.size) {
            if (window[i].timestamp - window[i - 1].timestamp > maxGapMs) return true
        }
        return false
    }

    private class Fit(val slopeLph: Double, val sxx: Double)

    /** Ordinary least squares of liters vs hours. */
    private fun fitSlope(window: List<LevelPoint>): Fit? {
        val first = window.first().timestamp
        val xs = window.map { (it.timestamp - first) / 3_600_000.0 }
        val ys = window.map { it.liters }
        val mx = xs.average()
        val my = ys.average()

        var sxx = 0.0
        var sxy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx
            sxx += dx * dx
            sxy += dx * (ys[i] - my)
        }
        if (sxx <= 0.0) return null
        return Fit(sxy / sxx, sxx)
    }
}