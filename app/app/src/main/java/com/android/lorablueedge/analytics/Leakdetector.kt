package com.android.lorablueedge.analytics

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.sqrt

/** Which reservoir is being checked; it changes what counts as "quiet". */
enum class ReservoirRole { CISTERN, TANK }

enum class LeakStatus {
    /** Not enough clean data in the quiet period to say anything yet. */
    UNKNOWN,

    /** Enough quiet-period data, and the reservoir is not losing water. */
    OK,

    /** Water is leaving when it should not be. */
    SUSPECTED
}

enum class LeakReason {
    /** Draining during hours when no consumption is expected. */
    QUIET_PERIOD_FLOW,

    /** Draining in every window for many hours, with no pause. */
    CONTINUOUS_FLOW
}

/**
 * @property estimatedLph   Estimated size of the leak (only when SUSPECTED).
 * @property evidenceHours  Hours of clean data the verdict is based on.
 */
data class LeakAssessment(
    val status: LeakStatus,
    val reason: LeakReason? = null,
    val estimatedLph: Double? = null,
    val evidenceHours: Double = 0.0
) {
    companion object {
        val UNKNOWN = LeakAssessment(LeakStatus.UNKNOWN)
    }
}

/**
 * Rule-based leak detector (RF06 baseline). It is meant to work from day
 * one with no training data, and to provide the reference the Random
 * Forest has to beat.
 *
 * It reasons on the per-window flows produced by [ConsumptionAnalyzer]
 * (pump-on and filling windows are already excluded there) and rests on
 * one physical idea: a leak is a CONSTANT outflow. When nobody is using
 * water, the volume should be flat; if it keeps falling, something is
 * losing water.
 *
 * Rules, checked over the last [Config.lookbackMs]:
 *
 * 1. QUIET_PERIOD_FLOW — look only at windows in the quiet period (for a
 *    TANK the night hours [Config.quietStartHour, Config.quietEndHour);
 *    for a CISTERN every pump-off window, because its only legitimate
 *    outflow is the pump). The leak size is the MEDIAN slope, which
 *    ignores the odd flushed toilet at 3 am. It must exceed both
 *    [Config.minLeakLph] and [Config.sigma] times the statistical noise of
 *    that median. The alert is withdrawn if the most recent hour no longer
 *    shows a drain of that size (leak fixed, or it was one-off use).
 *
 * 2. CONTINUOUS_FLOW — the most recent run of adjacent windows that ALL
 *    drain significantly lasts at least [Config.continuousHours]. Normal use
 *    has pauses; this catches leaks (or a forgotten tap) when the night
 *    data is missing.
 *
 * Limits: the noise floor of one 15-min window is a few L/h on a 1000 L
 * reservoir, so leaks below ~3 L/h need several quiet hours to be seen,
 * and a slow inflow (rain, dripping supply) can mask a leak.
 *
 * Pure Kotlin with no Android dependency.
 */
object LeakDetector {

    data class Config(
        val lookbackMs: Long = 24 * 3_600_000L,
        val quietStartHour: Int = 1,
        val quietEndHour: Int = 5,
        /** Quiet windows needed before OK/SUSPECTED can be stated (8 = 2 h). */
        val minQuietWindows: Int = 8,
        /** Smallest outflow worth reporting, whatever the noise. */
        val minLeakLph: Double = 3.0,
        val sigma: Double = 3.0,
        /** Sigmas used to decide the latest hour contradicts the leak. */
        val contradictionSigma: Double = 2.0,
        val contradictionWindows: Int = 4,
        val continuousHours: Double = 8.0,
        /** A cistern only loses water through the pump; any pump-off drain is a leak. */
        val cisternAlwaysQuiet: Boolean = true
    )

    fun evaluate(
        windows: List<FlowWindow>,
        role: ReservoirRole,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        config: Config = Config()
    ): LeakAssessment {
        val recent = windows
            .filter { it.status == WindowStatus.USABLE && it.startMs >= nowMs - config.lookbackMs }
            .sortedBy { it.startMs }

        val quiet = recent.filter { isQuiet(it, role, timeZone, config) }

        quietPeriodFlow(recent, quiet, config)?.let { return it }
        continuousFlow(recent, nowMs, config)?.let { return it }

        return if (quiet.size >= config.minQuietWindows) {
            LeakAssessment(LeakStatus.OK, evidenceHours = quiet.sumOf { it.hours })
        } else {
            LeakAssessment.UNKNOWN
        }
    }

    // ------------------------------------------------------------------
    // Rule 1
    // ------------------------------------------------------------------
    private fun quietPeriodFlow(
        recent: List<FlowWindow>,
        quiet: List<FlowWindow>,
        config: Config
    ): LeakAssessment? {
        if (quiet.size < config.minQuietWindows) return null

        val estimate = -median(quiet.map { it.slopeLph!! })
        // Standard error of a median of n similar estimates ~ 1.25 * sigma / sqrt(n).
        val medianNoise = 1.25 * quiet.map { it.slopeNoiseLph!! }.average() / sqrt(quiet.size.toDouble())

        if (estimate <= max(config.minLeakLph, config.sigma * medianNoise)) return null
        if (contradictedByLatestHour(recent, estimate, config)) return null

        return LeakAssessment(
            status = LeakStatus.SUSPECTED,
            reason = LeakReason.QUIET_PERIOD_FLOW,
            estimatedLph = estimate,
            evidenceHours = quiet.sumOf { it.hours }
        )
    }

    /**
     * A constant leak drains in EVERY window, so if the latest hour is
     * clearly flatter than the estimated leak, the leak is gone (or was not
     * a leak). Uses the mean of the last windows against 2-sigma.
     */
    private fun contradictedByLatestHour(
        recent: List<FlowWindow>,
        estimate: Double,
        config: Config
    ): Boolean {
        val latest = recent.takeLast(config.contradictionWindows)
        if (latest.size < config.contradictionWindows) return false

        val mean = latest.map { it.slopeLph!! }.average()
        val meanNoise = sqrt(latest.sumOf { val n = it.slopeNoiseLph!!; n * n }) / latest.size
        return mean > -estimate + config.contradictionSigma * meanNoise
    }

    // ------------------------------------------------------------------
    // Rule 2
    // ------------------------------------------------------------------
    private fun continuousFlow(
        recent: List<FlowWindow>,
        nowMs: Long,
        config: Config
    ): LeakAssessment? {
        val runs = ArrayList<List<FlowWindow>>()
        var current = ArrayList<FlowWindow>()

        for (w in recent) {
            val draining = w.slopeLph!! < -max(config.minLeakLph, config.sigma * w.slopeNoiseLph!!)
            val adjacent = current.isNotEmpty() && w.startMs == current.last().endMs

            if (draining && (current.isEmpty() || adjacent)) {
                current.add(w)
            } else {
                if (current.isNotEmpty()) runs.add(current)
                current = if (draining) arrayListOf(w) else ArrayList()
            }
        }
        if (current.isNotEmpty()) runs.add(current)

        val run = runs.lastOrNull() ?: return null
        val windowMs = run.last().endMs - run.last().startMs
        val activeNow = run.last().endMs >= nowMs - 2 * windowMs
        val hours = run.sumOf { it.hours }

        if (!activeNow || hours < config.continuousHours) return null

        return LeakAssessment(
            status = LeakStatus.SUSPECTED,
            reason = LeakReason.CONTINUOUS_FLOW,
            estimatedLph = -run.map { it.slopeLph!! }.average(),
            evidenceHours = hours
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private fun isQuiet(
        window: FlowWindow,
        role: ReservoirRole,
        timeZone: TimeZone,
        config: Config
    ): Boolean {
        if (role == ReservoirRole.CISTERN && config.cisternAlwaysQuiet) return true

        val hour = Calendar.getInstance(timeZone)
            .apply { timeInMillis = window.midMs }
            .get(Calendar.HOUR_OF_DAY)

        return if (config.quietStartHour <= config.quietEndHour) {
            hour >= config.quietStartHour && hour < config.quietEndHour
        } else { // period crossing midnight, e.g. 23 -> 5
            hour >= config.quietStartHour || hour < config.quietEndHour
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}