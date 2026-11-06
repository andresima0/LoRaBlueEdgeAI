package com.android.lorablueedge.chart

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.android.lorablueedge.data.TankDepthConfigStore

/**
 * Holds chart-screen state: which device this screen is showing, which
 * metric is currently selected in the spinner, and the resulting series
 * of (timestamp, value) points ready to draw.
 *
 * Reactivity here comes from TelemetryStore's shared samplesLiveData
 * (all devices, unfiltered) — this ViewModel maps every emission through
 * TelemetryStore.filterRange() for this screen's deviceId, then through
 * the selected ChartMetric, recomputing whenever either the underlying
 * data or the metric selection changes.
 */
class ChartViewModel(application: Application) : AndroidViewModel(application) {

    private val store = TelemetryStore(application)
    private val tankDepthConfigStore = TankDepthConfigStore(application)

    companion object {
        const val WINDOW_MILLIS = 10L * 60_000 // 10 minutes
    }

    private var deviceId: Int = -1

    private val _selectedMetric = MediatorLiveData<ChartMetric>()
    val selectedMetric: LiveData<ChartMetric> = _selectedMetric

    private val _points = MediatorLiveData<List<Pair<Long, Double>>>()
    val points: LiveData<List<Pair<Long, Double>>> = _points

    /**
     * Fixed Y-axis range for the current metric, or null to auto-scale.
     * Only non-null for [ChartMetric.WATER_LEVEL] when the user has already
     * configured the total depth for this device via the gear icon — the
     * range is then [0.0, totalDepth], matching the physical empty/full bounds.
     */
    private val _yRange = MediatorLiveData<Pair<Double, Double>?>()
    val yRange: LiveData<Pair<Double, Double>?> = _yRange

    private var lastAllSamples: List<TelemetrySample> = emptyList()

    /**
     * Must be called once, right after the ViewModel is created, before
     * observing [points]. Not done in init{} because it needs the
     * deviceId that only arrives via the launching Intent, which the
     * Activity reads after the ViewModel already exists.
     */
    fun start(deviceId: Int) {
        if (this.deviceId == deviceId) return // already started for this device
        this.deviceId = deviceId

        val metrics = ChartMetric.forDevice(deviceId)
        _selectedMetric.value = metrics.firstOrNull()

        _points.addSource(store.samplesLiveData) { all ->
            lastAllSamples = all
            recompute()
        }
    }

    fun selectMetric(metric: ChartMetric) {
        _selectedMetric.value = metric
        recompute()
    }

    fun availableMetrics(): List<ChartMetric> = ChartMetric.forDevice(deviceId)

    private fun recompute() {
        val metric = _selectedMetric.value ?: return
        val deviceSamples = store.filterRange(lastAllSamples, deviceId, WINDOW_MILLIS)
        _points.value = deviceSamples.map { it.timestamp to metric.extractValue(it) }

        // For WATER_LEVEL, pin the Y axis to the physical [0, totalDepth] range
        // so the chart always reads against a consistent scale instead of
        // auto-scaling to whatever values happen to be in the current window.
        // For all other metrics keep auto-scaling (null = no fixed range).
        _yRange.value = if (metric == ChartMetric.WATER_LEVEL) {
            tankDepthConfigStore.getTotalDepth(deviceId)?.let { 0.0 to it }
        } else {
            null
        }
    }
}