package com.android.lorablueedge.chart

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.android.lorablueedge.R
import com.android.lorablueedge.data.TelemetryReading

/**
 * Shows a 10-minute history chart for one device (Cistern or Tank),
 * launched from MainActivity by tapping that device's card. The metric
 * spinner only lists metrics that make sense for the given device (see
 * ChartMetric.forDevice) — Cistern never offers Turbidity, Tank never
 * offers Pump Status.
 */
class ChartActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    private val viewModel: ChartViewModel by viewModels()

    private lateinit var tvTitle: TextView
    private lateinit var spinnerMetric: Spinner
    private lateinit var lineChartView: LineChartView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart)

        val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Device"

        if (deviceId != TelemetryReading.DEVICE_ID_CISTERN &&
            deviceId != TelemetryReading.DEVICE_ID_TANK
        ) {
            finish() // launched incorrectly without a valid device id — nothing to show
            return
        }

        bindViews()
        setupSpinner(deviceId)

        tvTitle.text = "$deviceName — last 10 minutes"

        viewModel.start(deviceId)
        observeViewModel()
    }

    private fun bindViews() {
        tvTitle = findViewById(R.id.tvChartTitle)
        spinnerMetric = findViewById(R.id.spinnerMetric)
        lineChartView = findViewById(R.id.lineChartView)
    }

    private fun setupSpinner(deviceId: Int) {
        val metrics = ChartMetric.forDevice(deviceId)
        val labels = metrics.map { it.label }

        spinnerMetric.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        spinnerMetric.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long
            ) {
                viewModel.selectMetric(metrics[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun observeViewModel() {
        viewModel.points.observe(this) { points -> lineChartView.setPoints(points) }
        viewModel.yRange.observe(this) { range -> lineChartView.setYRange(range) }
    }
}