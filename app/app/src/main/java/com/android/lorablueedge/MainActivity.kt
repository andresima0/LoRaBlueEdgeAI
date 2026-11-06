package com.android.lorablueedge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.lorablueedge.analytics.AutonomyEstimate
import com.android.lorablueedge.analytics.ConsumptionSummary
import com.android.lorablueedge.analytics.LeakAssessment
import com.android.lorablueedge.analytics.LeakReason
import com.android.lorablueedge.analytics.LeakStatus
import com.android.lorablueedge.analytics.RainForecast
import com.android.lorablueedge.analytics.WindowExporter
import com.android.lorablueedge.ble.BleConnectionState
import com.android.lorablueedge.chart.ChartActivity
import com.android.lorablueedge.data.TankDepthConfigDialog
import com.android.lorablueedge.data.TankDepthConfigStore
import com.android.lorablueedge.data.TelemetryReading
import com.android.lorablueedge.data.WaterColumnReading
import com.android.lorablueedge.data.WaterLevelCalculator
import com.android.lorablueedge.history.TelemetryHistory
import com.android.lorablueedge.ml.PythonSmokeTest
import com.android.lorablueedge.weather.WeatherConfigDialog
import com.android.lorablueedge.mqtt.IotPlatform
import com.android.lorablueedge.mqtt.MqttConfigDialog
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread


/**
 * Pure UI layer: binds views, observes BleViewModel via LiveData, and wires
 * click listeners. Contains no BLE/GATT logic — the connection, MQTT
 * publishing and history all live in LoRaBlueService (a foreground
 * service), which keeps running when this Activity is closed. See
 * BleViewModel for the thin bridge between the two.
 *
 * Two telemetry cards are shown: Cistern and Tank. Each is updated
 * independently as its corresponding LiveData emits — receiving a Cistern
 * reading does not touch the Tank card's displayed values, and vice versa.
 *
 * Only the two everyday actions (SCAN & CONNECT and TX TEST) are buttons on
 * the screen. Everything else — platform settings, weather settings and data
 * export — lives in the options menu opened by the hamburger icon of the top
 * app bar (see [showMainMenu]).
 *
 * The app targets API 37, where edge-to-edge is enforced: the window is drawn
 * under the system bars. The root view (R.id.mainRoot) therefore receives the
 * system bar insets as padding, so no control is hidden by the status or the
 * navigation bar.
 */

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val viewModel: BleViewModel by viewModels()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView

    private var latestAutonomy: AutonomyEstimate? = null
    private var latestRain: RainForecast? = null

    // Cistern card
    private lateinit var cardCistern: View
    private lateinit var tvCisternWaterLevel: TextView
    private lateinit var tvCisternWaterPercent: TextView
    private lateinit var btnCisternDepthConfig: ImageButton
    private lateinit var tvCisternPump: TextView
    private lateinit var tvCisternBattery: TextView
    private lateinit var tvCisternRssi: TextView
    private lateinit var tvCisternConsumption: TextView
    private lateinit var tvCisternLeak: TextView

    // Tank card
    private lateinit var cardTank: View
    private lateinit var tvTankWaterLevel: TextView
    private lateinit var tvTankWaterPercent: TextView
    private lateinit var btnTankDepthConfig: ImageButton
    private lateinit var tvTankBattery: TextView
    private lateinit var tvTankRssi: TextView
    private lateinit var tvTankConsumption: TextView
    private lateinit var tvTankLeak: TextView

    private lateinit var btnConnect: Button
    private lateinit var btnTxTest: Button

    // Persists the user-entered total depth (sensor-to-bottom distance)
    // per device, used to convert the raw TOF sensor distance into a
    // water column height + fill percentage.
    private val tankDepthConfigStore by lazy { TankDepthConfigStore(this) }

    // Cache of the most recent VALID raw sensor distance (water_lvl) per
    // device, so that saving a new total depth via the gear icon
    // immediately re-renders the card without waiting for the next BLE
    // telemetry packet to arrive.
    private var lastCisternSensorDistance: Double? = null
    private var lastTankSensorDistance: Double? = null

    // Presentation-only toggle state for btnTxTest. Not BLE connection
    // state — this just tracks whether the last command sent was PING
    // (ON / green) or CLEAR (OFF / gray).
    private var isTxTestActive = false

    // =========================================================================
    // MODERN PERMISSION / ENABLE-BLUETOOTH LAUNCHERS
    // Registered as fields (before onStart) so they survive configuration
    // changes correctly, per the Activity Result API contract.
    // =========================================================================
    private val requestBtPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                ensureBluetoothEnabledThenScan()
            } else {
                Toast.makeText(
                    this,
                    "Permissions are required to operate LoRaBlue telemetry.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // Android 13+: without this permission the foreground service still
    // runs, but its notification is hidden. Not required to operate, so a
    // denial is simply ignored.
    private val requestNotificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.startScan()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth must be enabled to connect to the Gateway.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applySystemBarInsets()
        bindViews()
        wireClickListeners()
        observeViewModel()
        requestNotificationPermissionIfNeeded()

        // TEMPORARY: Python/scikit-learn smoke test. Remove after validating on the Pi 5.
        PythonSmokeTest.run { success, text ->
            runOnUiThread {
                if (!isFinishing) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(if (success) "Python OK" else "Python FAILED")
                        .setMessage(text)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /**
     * targetSdk 37 draws the app edge to edge. Without this, the bottom
     * buttons end up under the navigation bar. The insets become padding of
     * the root view, so everything inside stays clear of the status bar, the
     * navigation bar and any display cutout.
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)

        tvStatus = findViewById(R.id.tvStatus)
        tvSummary = addSummaryLine(tvStatus)

        cardCistern = findViewById(R.id.cardCistern)
        tvCisternWaterLevel = findViewById(R.id.tvCisternWaterLevel)
        tvCisternWaterPercent = findViewById(R.id.tvCisternWaterPercent)
        btnCisternDepthConfig = findViewById(R.id.btnCisternDepthConfig)
        tvCisternPump = findViewById(R.id.tvCisternPump)
        tvCisternBattery = findViewById(R.id.tvCisternBattery)
        tvCisternRssi = findViewById(R.id.tvCisternRssi)
        tvCisternConsumption = addStatusLine("Consumption: -- L/h", tvCisternBattery, tvCisternRssi)
        tvCisternLeak =
            addStatusLine("Leak check: collecting data…", tvCisternBattery, tvCisternRssi)

        cardTank = findViewById(R.id.cardTank)
        tvTankWaterLevel = findViewById(R.id.tvTankWaterLevel)
        tvTankWaterPercent = findViewById(R.id.tvTankWaterPercent)
        btnTankDepthConfig = findViewById(R.id.btnTankDepthConfig)
        tvTankBattery = findViewById(R.id.tvTankBattery)
        tvTankRssi = findViewById(R.id.tvTankRssi)
        tvTankConsumption = addStatusLine("Consumption: -- L/h", tvTankBattery, tvTankRssi)
        tvTankLeak = addStatusLine("Leak check: collecting data…", tvTankBattery, tvTankRssi)

        btnConnect = findViewById(R.id.btnConnect)
        btnTxTest = findViewById(R.id.btnTxTest)
    }

    private fun wireClickListeners() {
        // --- Hamburger menu -------------------------------------------------
        toolbar.setNavigationOnClickListener { showMainMenu() }

        // --- Device cards → ChartActivity -----------------------------------
        cardCistern.setOnClickListener {
            openChart(TelemetryReading.DEVICE_ID_CISTERN, "Cistern")
        }
        cardTank.setOnClickListener {
            openChart(TelemetryReading.DEVICE_ID_TANK, "Tank")
        }

        // --- Water Level depth configuration (gear icons) --------------------
        // These are nested clickable Views inside the (also clickable) card,
        // so Android dispatches the tap to them rather than bubbling it up
        // to the card's own click listener — tapping the gear never opens
        // ChartActivity.
        btnCisternDepthConfig.setOnClickListener {
            TankDepthConfigDialog.show(
                context = this,
                deviceId = TelemetryReading.DEVICE_ID_CISTERN,
                deviceLabel = "Cistern"
            ) { newDepth ->
                renderCisternWaterLevel(lastCisternSensorDistance, newDepth)
            }
        }
        btnTankDepthConfig.setOnClickListener {
            TankDepthConfigDialog.show(
                context = this,
                deviceId = TelemetryReading.DEVICE_ID_TANK,
                deviceLabel = "Tank"
            ) { newDepth ->
                renderTankWaterLevel(lastTankSensorDistance, newDepth)
            }
        }

        // --- BLE actions ----------------------------------------------------
        btnConnect.setOnClickListener {
            if (checkBlePermissions()) ensureBluetoothEnabledThenScan()
        }

        btnTxTest.setOnClickListener {
            isTxTestActive = !isTxTestActive

            if (isTxTestActive) {
                btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                viewModel.sendPing()
            } else {
                btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
                viewModel.sendClear()
            }
        }
    }

    /** Options menu opened by the hamburger icon of the top app bar. */
    private fun showMainMenu() {
        val popup = PopupMenu(this, toolbar, Gravity.START)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_platform_thingsboard -> {
                    MqttConfigDialog.show(this, IotPlatform.THINGSBOARD) { viewModel.reloadMqttConfigs() }
                    true
                }
                R.id.menu_platform_konker -> {
                    MqttConfigDialog.show(this, IotPlatform.KONKER) { viewModel.reloadMqttConfigs() }
                    true
                }

                R.id.menu_weather_settings -> {
                    WeatherConfigDialog.show(this) { viewModel.reloadWeather() }
                    true
                }

                R.id.menu_export_data -> {
                    exportData()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun openChart(deviceId: Int, deviceName: String) {
        val intent = Intent(this, ChartActivity::class.java).apply {
            putExtra(ChartActivity.EXTRA_DEVICE_ID, deviceId)
            putExtra(ChartActivity.EXTRA_DEVICE_NAME, deviceName)
        }
        startActivity(intent)
    }

    private fun observeViewModel() {
        viewModel.connectionState.observe(this) { state -> renderConnectionState(state) }

        viewModel.cisternConsumption.observe(this) {
            tvCisternConsumption.text = formatConsumption(it)
        }
        viewModel.tankConsumption.observe(this) { tvTankConsumption.text = formatConsumption(it) }
        viewModel.cisternLeak.observe(this) { renderLeak(tvCisternLeak, it) }
        viewModel.tankLeak.observe(this) { renderLeak(tvTankLeak, it) }

        viewModel.autonomy.observe(this) { latestAutonomy = it; renderSummary() }
        viewModel.rain.observe(this) { latestRain = it; renderSummary() }
        viewModel.weatherStatus.observe(this) { text ->
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        viewModel.cisternData.observe(this) { data ->
            if (data.waterValid) {
                lastCisternSensorDistance = data.waterLevel
                val depth = tankDepthConfigStore.getTotalDepth(TelemetryReading.DEVICE_ID_CISTERN)
                renderCisternWaterLevel(data.waterLevel, depth)
            } else {
                tvCisternWaterLevel.text = "Water Level: -- m (sensor error)"
                tvCisternWaterPercent.text = "(-- %)"
            }

            tvCisternPump.text = if (data.pumpOn) "Water Pump: ON" else "Water Pump: OFF"
            tvCisternBattery.text = String.format("Battery: %.0f %%", data.batteryPercent)
            tvCisternRssi.text = String.format("RSSI: %.1f dBm", data.rssiDbm)
        }

        viewModel.tankData.observe(this) { data ->
            if (data.waterValid) {
                lastTankSensorDistance = data.waterLevel
                val depth = tankDepthConfigStore.getTotalDepth(TelemetryReading.DEVICE_ID_TANK)
                renderTankWaterLevel(data.waterLevel, depth)
            } else {
                tvTankWaterLevel.text = "Water Level: -- m (sensor error)"
                tvTankWaterPercent.text = "(-- %)"
            }

            tvTankBattery.text = String.format("Battery: %.0f %%", data.batteryPercent)
            tvTankRssi.text = String.format("RSSI: %.1f dBm", data.rssiDbm)
        }

        // Debug messages (PING/CLEAR acks from the firmware), surfaced as a
        // lightweight Toast. Delivered once each (see SingleLiveEvent).
        viewModel.debugLog.observe(this) { text ->
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        // MQTT publish outcomes (success/failure), surfaced the same
        // lightweight way. Each enabled platform (ThingsBoard and/or Konker)
        // reports independently, so a failure on one doesn't hide a
        // simultaneous success on the other.
        viewModel.mqttStatus.observe(this) { text ->
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Renders the Cistern's "Water Level" line and the percent badge next
     * to it. [sensorDistance] is the raw TOF distance from the top sensor
     * down to the water surface (as received from the firmware);
     * [totalDepth] is the user-configured sensor-to-bottom distance for
     * this device (null until configured via the gear icon).
     *
     * Water column height = totalDepth - sensorDistance, so it's computed
     * here rather than displaying the raw sensor distance directly.
     */
    private fun renderCisternWaterLevel(sensorDistance: Double?, totalDepth: Double?) {
        if (sensorDistance == null) return
        val reading = WaterLevelCalculator.compute(
            sensorDistance, totalDepth,
            tankDepthConfigStore.getCapacityLiters(TelemetryReading.DEVICE_ID_CISTERN)
        )

        tvCisternWaterLevel.text = if (reading.columnMeters != null) {
            String.format("Water Level: %.2fm", reading.columnMeters)
        } else {
            "Water Level: -- m (set depth)"
        }
        tvCisternWaterPercent.text = if (reading.percent != null) {
            formatPercent(reading)
        } else {
            "(-- %)"
        }
    }

    /** Tank equivalent of [renderCisternWaterLevel]. */
    private fun renderTankWaterLevel(sensorDistance: Double?, totalDepth: Double?) {
        if (sensorDistance == null) return
        val reading = WaterLevelCalculator.compute(
            sensorDistance, totalDepth,
            tankDepthConfigStore.getCapacityLiters(TelemetryReading.DEVICE_ID_TANK)
        )

        tvTankWaterLevel.text = if (reading.columnMeters != null) {
            String.format("Water Level: %.2fm", reading.columnMeters)
        } else {
            "Water Level: -- m (set depth)"
        }
        tvTankWaterPercent.text = if (reading.percent != null) {
            formatPercent(reading)
        } else {
            "(-- %)"
        }
    }

    /** "(52%)" or, when the capacity is configured, "(52% · 520 L)". */
    private fun formatPercent(reading: WaterColumnReading): String =
        if (reading.liters != null) {
            String.format("(%.0f%% · %.0f L)", reading.percent, reading.liters)
        } else {
            String.format("(%.0f%%)", reading.percent)
        }

    /**
     * Adds a status line to a card, just above its RSSI line and styled
     * like the battery line. Created in code so it works with both the
     * portrait and the landscape layout without editing either XML.
     */
    private fun addStatusLine(
        initialText: String,
        styleSource: TextView,
        anchor: TextView
    ): TextView {
        val view = TextView(this).apply {
            text = initialText
            setTextSize(TypedValue.COMPLEX_UNIT_PX, styleSource.textSize)
            setTextColor(styleSource.currentTextColor)
        }
        val parent = anchor.parent as LinearLayout
        parent.addView(view, parent.indexOfChild(anchor))
        return view
    }

    /** One-line summary (autonomy and rain) right under the connection status. */
    private fun addSummaryLine(anchor: TextView): TextView {
        val view = TextView(this).apply {
            text = "Autonomy: --  ·  Rain 24h: --"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#333333"))
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = (16 * resources.displayMetrics.density).toInt()
        }
        val parent = anchor.parent as LinearLayout
        parent.addView(view, parent.indexOfChild(anchor) + 1, params)
        return view
    }

    private fun renderSummary() {
        val autonomy = latestAutonomy
        val stored = autonomy?.storedLiters ?: 0.0
        val harvest = autonomy?.harvestLiters ?: 0.0
        val days = autonomy?.days

        val autonomyText = when {
            stored <= 0.0 -> "Autonomy: --"
            days == null -> "Autonomy: -- (no consumption data yet)"
            harvest >= 1.0 -> String.format("Autonomy: %.1f days (+%.0f L rain)", days, harvest)
            else -> String.format("Autonomy: %.1f days", days)
        }

        val rain = latestRain
        val rainText = if (rain == null) {
            "Rain 24h: --"
        } else {
            String.format("Rain 24h: %.1f mm", rain.expectedMm24h)
        }

        tvSummary.text = "$autonomyText  ·  $rainText"
    }

    private fun renderLeak(view: TextView, assessment: LeakAssessment) {
        val (text, color) = when (assessment.status) {
            LeakStatus.UNKNOWN -> "Leak check: collecting data…" to "#888888"
            LeakStatus.OK -> "Leak check: OK" to "#4CAF50"
            LeakStatus.SUSPECTED -> {
                val why = when (assessment.reason) {
                    LeakReason.QUIET_PERIOD_FLOW -> "while idle"
                    LeakReason.CONTINUOUS_FLOW -> "never stops"
                    null -> ""
                }
                String.format(
                    "Possible leak: ~%.1f L/h (%s)",
                    assessment.estimatedLph ?: 0.0,
                    why
                ) to "#F44336"
            }
        }
        view.text = text
        view.setTextColor(Color.parseColor(color))
    }

    private fun formatConsumption(summary: ConsumptionSummary): String {
        fun part(rate: Double?) = rate?.let { String.format("%.1f L/h", it) } ?: "--"
        return "Consumption: ${part(summary.last1h.rateLph)} (24h: ${part(summary.last24h.rateLph)})"
    }

    private fun renderConnectionState(state: BleConnectionState) {
        val (text, color) = when (state) {
            is BleConnectionState.Disconnected ->
                "Status: Disconnected" to "#F44336"

            is BleConnectionState.Scanning ->
                "Status: Scanning..." to "#FF9800"

            is BleConnectionState.Connecting ->
                "Status: Found device, connecting..." to "#FF9800"

            is BleConnectionState.NegotiatingMtu ->
                "Status: Negotiating MTU..." to "#FF9800"

            is BleConnectionState.Connected ->
                "Status: Connected! (MTU ${state.mtu})" to "#4CAF50"

            is BleConnectionState.ScanFailed ->
                "Status: Scan failed (${state.reason})" to "#F44336"
        }
        tvStatus.text = text
        tvStatus.setTextColor(Color.parseColor(color))

        if (state is BleConnectionState.Disconnected) {
            isTxTestActive = false
            btnTxTest.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
        }
    }

    /**
     * Writes readings_<stamp>.csv (raw history) and windows_<stamp>.csv (flow
     * windows for model training) to the app's external files folder. Runs on a
     * background thread: the history can hold millions of rows.
     */
    private fun exportData() {
        val dir = getExternalFilesDir("exports")
        if (dir == null) {
            Toast.makeText(this, "External storage unavailable", Toast.LENGTH_LONG).show()
            return
        }
        dir.mkdirs()
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show()

        thread(name = "data-export") {
            val message = try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val history = TelemetryHistory(applicationContext)
                try {
                    val readings = history.exportCsv(File(dir, "readings_$stamp.csv"))
                    val windows = WindowExporter.export(
                        File(dir, "windows_$stamp.csv"), history, tankDepthConfigStore
                    )
                    buildString {
                        append("$readings readings and ${windows.windows} windows exported to:\n")
                        append(dir.absolutePath)
                        if (windows.skippedNodes.isNotEmpty()) {
                            append("\n\nNodes without depth/capacity configured (no windows): ")
                            append(windows.skippedNodes.joinToString())
                        }
                    }
                } finally {
                    history.close()
                }
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }

            runOnUiThread {
                if (!isFinishing) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Export")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun ensureBluetoothEnabledThenScan() {
        if (!viewModel.isBluetoothEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            viewModel.startScan()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkBlePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missing = required.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestBtPermissionsLauncher.launch(missing.toTypedArray())
            return false
        }
        return true
    }
}