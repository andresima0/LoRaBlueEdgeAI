package com.android.lorablueedge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.android.lorablueedge.MainActivity
import com.android.lorablueedge.analytics.AutonomyCalculator
import com.android.lorablueedge.analytics.AutonomyEstimate
import com.android.lorablueedge.analytics.ConsumptionAnalyzer
import com.android.lorablueedge.analytics.ConsumptionSummary
import com.android.lorablueedge.analytics.LeakAssessment
import com.android.lorablueedge.analytics.LeakDetector
import com.android.lorablueedge.analytics.LeakReason
import com.android.lorablueedge.analytics.LeakStatus
import com.android.lorablueedge.analytics.LevelPoint
import com.android.lorablueedge.analytics.RainForecast
import com.android.lorablueedge.analytics.ReservoirRole
import com.android.lorablueedge.ble.BleConnectionState
import com.android.lorablueedge.ble.BleManager
import com.android.lorablueedge.chart.TelemetrySample
import com.android.lorablueedge.chart.TelemetryStore
import com.android.lorablueedge.data.BleMessage
import com.android.lorablueedge.data.TankDepthConfigStore
import com.android.lorablueedge.data.TelemetryReading
import com.android.lorablueedge.data.WaterColumnReading
import com.android.lorablueedge.data.WaterLevelCalculator
import com.android.lorablueedge.history.HistoryRow
import com.android.lorablueedge.history.TelemetryHistory
import com.android.lorablueedge.mqtt.MqttConfig
import com.android.lorablueedge.mqtt.MqttConfigStore
import com.android.lorablueedge.mqtt.MqttPublisher
import com.android.lorablueedge.weather.WeatherConfigStore
import com.android.lorablueedge.weather.WeatherRepository
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground Service that owns the whole data path (RF03):
 *
 *   Bridge --BLE--> BleManager --> publish MQTT
 *                              --> chart store (1 h, SharedPreferences)
 *                              --> history (90 days, SQLite)
 *                              --> GatewayHub (LiveData for the UI)
 *
 * Because it is a foreground service, collection continues when the
 * Activity is closed or the screen is off, and Android is much less likely
 * to kill the process. BLE auto-reconnect (RNF03) lives in [BleManager].
 *
 * The service is started by the first "SCAN & CONNECT" (that is when the
 * Bluetooth permissions are guaranteed to be granted — required to start a
 * connectedDevice foreground service on Android 14+).
 */
class LoRaBlueService : Service() {

    companion object {
        private const val TAG = "LoRaBlueService"
        const val ACTION_START_SCAN = "com.android.lorablueedge.action.START_SCAN"
        const val ACTION_STOP = "com.android.lorablueedge.action.STOP"

        private const val CHANNEL_ID = "lorablue_gateway"
        private const val NOTIFICATION_ID = 1

        // Leak alerts use their own (high importance) channel so they pop up,
        // unlike the quiet, permanent connection notification.
        private const val ALERT_CHANNEL_ID = "lorablue_alerts"
        private const val LEAK_NOTIFICATION_BASE_ID = 100 // + slot
        private const val LEAK_REALERT_MS = 6L * 60 * 60 * 1000

        private const val WEATHER_REFRESH_SECONDS = 3L * 60 * 60
        // A reading older than this no longer says how much water there is.
        private const val FRESH_READING_MS = 30L * 60 * 1000
        // A forecast older than this is not credited to the autonomy.
        private const val RAIN_MAX_AGE_MS = 12L * 60 * 60 * 1000

        /** The running instance, or null. Used by the ViewModel for commands. */
        @Volatile
        var instance: LoRaBlueService? = null
            private set

        fun startScan(context: Context) {
            val intent = Intent(context, LoRaBlueService::class.java)
                .setAction(ACTION_START_SCAN)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LoRaBlueService::class.java))
        }
    }

    private var initialized = false

    private lateinit var ble: BleManager
    private lateinit var mqttPublisher: MqttPublisher
    private lateinit var mqttConfigStore: MqttConfigStore
    private lateinit var tankDepthConfigStore: TankDepthConfigStore
    private lateinit var telemetryStore: TelemetryStore
    private lateinit var history: TelemetryHistory
    private lateinit var ioExecutor: ExecutorService
    private lateinit var analysisExecutor: ScheduledExecutorService
    private lateinit var weatherExecutor: ScheduledExecutorService
    private lateinit var weatherConfigStore: WeatherConfigStore
    private lateinit var weatherRepository: WeatherRepository

    // Latest valid volume (timestamp, litres) per UI slot, for the autonomy.
    private val latestLiters = ConcurrentHashMap<Int, Pair<Long, Double>>()
    @Volatile private var tankDemandLph: Double? = null
    @Volatile private var rainForecast: RainForecast? = null

    // Latest node id seen for each UI slot; the consumption analysis
    // reads that node's history.
    @Volatile private var cisternNodeId: Int? = null
    @Volatile private var tankNodeId: Int? = null

    // Touched only by the analysis thread.
    private val lastLeakStatus = HashMap<Int, LeakStatus>()
    private val lastLeakAlertAt = HashMap<Int, Long>()

    // Every ENABLED platform config; refreshed by reloadMqttConfigs().
    @Volatile
    private var mqttConfigs: List<MqttConfig> = emptyList()

    @Volatile private var connectionText = "Starting…"
    @Volatile private var lastReadingAt: Long? = null

    // =====================================================================
    // LIFECYCLE
    // =====================================================================
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            // E.g. Bluetooth permissions missing on Android 14+.
            Log.e(TAG, "Cannot enter foreground: ${e.message}")
            stopSelf()
            return
        }

        mqttConfigStore = MqttConfigStore(applicationContext)
        tankDepthConfigStore = TankDepthConfigStore(applicationContext)
        telemetryStore = TelemetryStore(applicationContext)
        history = TelemetryHistory(applicationContext)
        mqttPublisher = MqttPublisher()
        ioExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor = Executors.newSingleThreadScheduledExecutor()
        analysisExecutor.scheduleWithFixedDelay({ runAnalysis() }, 30, 60, TimeUnit.SECONDS)

        weatherConfigStore = WeatherConfigStore(applicationContext)
        weatherRepository = WeatherRepository(applicationContext)
        rainForecast = weatherRepository.cached()
        rainForecast?.let { GatewayHub.rain.postValue(it) }
        // Its own thread: a slow request must not delay the analysis.
        weatherExecutor = Executors.newSingleThreadScheduledExecutor()
        weatherExecutor.scheduleWithFixedDelay(
            { refreshWeather(notifySuccess = false) }, 10, WEATHER_REFRESH_SECONDS, TimeUnit.SECONDS
        )
        mqttConfigs = mqttConfigStore.loadAllEnabled()

        mqttPublisher.onPublishResult = { _, message -> GatewayHub.mqttStatus.postValue(message) }

        ble = BleManager(applicationContext)
        ble.onStateChanged = { state ->
            GatewayHub.connectionState.postValue(state)
            connectionText = describe(state)
            refreshNotification()
        }
        ble.onMessageReceived = { message -> handleMessage(message) }

        instance = this
        initialized = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) return START_NOT_STICKY

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // ACTION_START_SCAN, or a null intent when the system restarted the
        // service after killing it (START_STICKY) — both mean "be connected".
        try {
            ble.startScan()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        if (initialized) {
            ble.stop()
            analysisExecutor.shutdownNow()
            weatherExecutor.shutdownNow()
            ioExecutor.shutdown()
            history.close()
        }
        GatewayHub.connectionState.postValue(BleConnectionState.Disconnected)
        super.onDestroy()
    }

    // =====================================================================
    // COMMANDS FROM THE UI
    // =====================================================================
    fun sendCommand(command: String) {
        if (initialized) ble.sendCommand(command)
    }

    /** Fetches a forecast right away, after the weather settings were saved. */
    fun reloadWeather() {
        if (initialized) weatherExecutor.execute { refreshWeather(notifySuccess = true) }
    }

    /** Re-reads the enabled MQTT platforms after MqttSettingsActivity saved. */
    fun reloadMqttConfigs() {
        if (initialized) mqttConfigs = mqttConfigStore.loadAllEnabled()
    }

    // =====================================================================
    // DATA PATH
    // =====================================================================
    private fun handleMessage(message: BleMessage) {
        when (message) {
            is BleMessage.Telemetry -> handleTelemetry(message.reading)
            is BleMessage.Debug -> GatewayHub.debugLog.postValue(message.text)
            is BleMessage.Unknown -> { /* logged inside JsonParser already */ }
            is BleMessage.Malformed -> { /* logged inside JsonParser already */ }
        }
    }

    private fun handleTelemetry(reading: TelemetryReading) {
        val now = System.currentTimeMillis()

        when (reading) {
            is TelemetryReading.Cistern -> {
                cisternNodeId = reading.nodeId
                GatewayHub.cisternData.postValue(reading)
            }
            is TelemetryReading.Tank -> {
                tankNodeId = reading.nodeId
                GatewayHub.tankData.postValue(reading)
            }
        }

        // Raw TOF distance -> water column height + fill percentage, once
        // per reading, shared by every enabled platform.
        val waterColumn = toWaterColumn(reading)
        if (reading.waterValid) {
            val slot = when (reading) {
                is TelemetryReading.Cistern -> TelemetryReading.DEVICE_ID_CISTERN
                is TelemetryReading.Tank -> TelemetryReading.DEVICE_ID_TANK
            }
            waterColumn.liters?.let { latestLiters[slot] = now to it }
        }

        mqttConfigs.forEach { config ->
            mqttPublisher.publish(config, reading, waterColumn)
        }

        // Disk writes happen off the BLE callback thread.
        ioExecutor.execute {
            try {
                // The chart only plots trustworthy water levels.
                if (reading.waterValid) telemetryStore.record(toSample(reading, waterColumn, now))
                history.insert(toHistoryRow(reading, waterColumn, now))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist reading: ${e.message}")
            }
        }

        lastReadingAt = now
        refreshNotification()
    }

    private fun toWaterColumn(reading: TelemetryReading): WaterColumnReading {
        val (slot, sensorDistance) = when (reading) {
            is TelemetryReading.Cistern -> TelemetryReading.DEVICE_ID_CISTERN to reading.waterLevel
            is TelemetryReading.Tank -> TelemetryReading.DEVICE_ID_TANK to reading.waterLevel
        }
        val totalDepth = tankDepthConfigStore.getTotalDepth(slot)
        // Invalid sensor reading: no column/percent (MQTT then sends null).
        if (!reading.waterValid) return WaterColumnReading(sensorDistance, totalDepth, null, null)
        return WaterLevelCalculator.compute(
            sensorDistance, totalDepth, tankDepthConfigStore.getCapacityLiters(slot)
        )
    }

    /** Chart sample: converted column when depth is known, raw distance otherwise. */
    private fun toSample(
        reading: TelemetryReading,
        waterColumn: WaterColumnReading,
        now: Long
    ): TelemetrySample = when (reading) {
        is TelemetryReading.Cistern -> TelemetrySample(
            deviceId = TelemetryReading.DEVICE_ID_CISTERN,
            timestamp = now,
            waterLevel = waterColumn.columnMeters ?: reading.waterLevel,
            batteryPercent = reading.batteryPercent,
            rssiDbm = reading.rssiDbm,
            pumpOn = reading.pumpOn
        )
        is TelemetryReading.Tank -> TelemetrySample(
            deviceId = TelemetryReading.DEVICE_ID_TANK,
            timestamp = now,
            waterLevel = waterColumn.columnMeters ?: reading.waterLevel,
            batteryPercent = reading.batteryPercent,
            rssiDbm = reading.rssiDbm
        )
    }

    /** History row: always keeps the RAW distance, plus the converted values. */
    private fun toHistoryRow(
        reading: TelemetryReading,
        waterColumn: WaterColumnReading,
        now: Long
    ): HistoryRow = when (reading) {
        is TelemetryReading.Cistern -> HistoryRow(
            timestamp = now,
            nodeId = reading.nodeId,
            nodeType = TelemetryReading.NODE_TYPE_CISTERN,
            sensorMeters = reading.waterLevel,
            columnMeters = waterColumn.columnMeters,
            percent = waterColumn.percent,
            pumpOn = reading.pumpOn,
            batteryPercent = reading.batteryPercent,
            rssiDbm = reading.rssiDbm,
            waterValid = reading.waterValid
        )
        is TelemetryReading.Tank -> HistoryRow(
            timestamp = now,
            nodeId = reading.nodeId,
            nodeType = TelemetryReading.NODE_TYPE_TANK,
            sensorMeters = reading.waterLevel,
            columnMeters = waterColumn.columnMeters,
            percent = waterColumn.percent,
            pumpOn = null,
            batteryPercent = reading.batteryPercent,
            rssiDbm = reading.rssiDbm,
            waterValid = reading.waterValid
        )
    }

    // =====================================================================
    // CONSUMPTION ANALYSIS (RF04)
    // =====================================================================
    /** Runs every minute on its own thread; publishes to the UI via GatewayHub. */
    private fun runAnalysis() {
        try {
            cisternNodeId?.let { analyzeNode(TelemetryReading.DEVICE_ID_CISTERN, it) }
            tankNodeId?.let { analyzeNode(TelemetryReading.DEVICE_ID_TANK, it) }
            updateAutonomy()
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed: ${e.message}")
        }
    }

    /**
     * Builds the volume series of one node from its raw history using the
     * CURRENT depth/capacity (so correcting the configuration also corrects
     * the past) and derives from it both the consumption rate (RF04) and
     * the leak verdict (RF06). Without depth and capacity both stay unknown.
     */
    private fun analyzeNode(slot: Int, nodeId: Int) {
        val depth = tankDepthConfigStore.getTotalDepth(slot)
        val capacity = tankDepthConfigStore.getCapacityLiters(slot)

        var consumption = ConsumptionSummary.UNKNOWN
        var leak = LeakAssessment.UNKNOWN

        if (depth != null && depth > 0.0 && capacity != null) {
            val now = System.currentTimeMillis()
            val points = history.query(nodeId, now - 24L * 60 * 60 * 1000)
                .filter { it.waterValid }
                .map {
                    LevelPoint(
                        timestamp = it.timestamp,
                        liters = WaterLevelCalculator.litersFor(it.sensorMeters, depth, capacity),
                        pumpOn = it.pumpOn == true
                    )
                }

            val noiseLiters = capacity / depth * ConsumptionAnalyzer.SENSOR_NOISE_METERS
            consumption = ConsumptionAnalyzer.summarize(points, noiseLiters, now)

            val role = if (slot == TelemetryReading.DEVICE_ID_CISTERN) {
                ReservoirRole.CISTERN
            } else {
                ReservoirRole.TANK
            }
            leak = LeakDetector.evaluate(
                ConsumptionAnalyzer.flowWindows(points, noiseLiters), role, now
            )
        }

        // The household demand is what the tank loses: the cistern only
        // empties into the tank (pump windows are excluded from its rate).
        if (slot == TelemetryReading.DEVICE_ID_TANK) tankDemandLph = consumption.last24h.rateLph

        if (slot == TelemetryReading.DEVICE_ID_CISTERN) {
            GatewayHub.cisternConsumption.postValue(consumption)
            GatewayHub.cisternLeak.postValue(leak)
        } else {
            GatewayHub.tankConsumption.postValue(consumption)
            GatewayHub.tankLeak.postValue(leak)
        }
        updateLeakAlert(slot, leak)
    }

    // =====================================================================
    // WEATHER (RF05) AND AUTONOMY (RF07)
    // =====================================================================
    private fun refreshWeather(notifySuccess: Boolean) {
        val config = weatherConfigStore.load()
        if (!config.isConfigured) return

        try {
            val forecast = weatherRepository.refresh(config)
            rainForecast = forecast
            GatewayHub.rain.postValue(forecast)
            if (notifySuccess) {
                GatewayHub.weatherStatus.postValue(
                    String.format(Locale.getDefault(), "Weather updated: %.1f mm expected in 24 h", forecast.expectedMm24h)
                )
            }
        } catch (e: Exception) {
            // Offline is a normal state for this app: keep the last forecast.
            Log.w(TAG, "Weather update failed: ${e.message}")
            GatewayHub.weatherStatus.postValue("Weather update failed: ${e.message}")
        }
    }

    /**
     * Days of water left: what is stored in both reservoirs, plus the rain
     * expected to reach the cistern, over the household demand. Reservoirs
     * without a configured capacity cannot be counted, and neither can
     * readings that are no longer fresh.
     */
    private fun updateAutonomy() {
        val now = System.currentTimeMillis()
        fun fresh(slot: Int): Double? =
            latestLiters[slot]?.takeIf { now - it.first < FRESH_READING_MS }?.second

        val cistern = fresh(TelemetryReading.DEVICE_ID_CISTERN)
        val tank = fresh(TelemetryReading.DEVICE_ID_TANK)
        if (cistern == null && tank == null) {
            GatewayHub.autonomy.postValue(AutonomyEstimate.UNKNOWN)
            return
        }

        val rain = rainForecast?.takeIf { now - it.fetchedAtMs < RAIN_MAX_AGE_MS }
        val cisternCapacity = tankDepthConfigStore.getCapacityLiters(TelemetryReading.DEVICE_ID_CISTERN)
        val cisternFree = if (cistern != null && cisternCapacity != null) cisternCapacity - cistern else null

        val harvest = if (rain != null) {
            AutonomyCalculator.harvestLiters(
                rain.expectedMm24h, weatherConfigStore.load().roofAreaM2, cisternFree
            )
        } else 0.0

        GatewayHub.autonomy.postValue(
            AutonomyCalculator.estimate((cistern ?: 0.0) + (tank ?: 0.0), tankDemandLph, harvest)
        )
    }

    // =====================================================================
    // LEAK ALERTS
    // =====================================================================
    /**
     * Raises a heads-up notification when a reservoir turns SUSPECTED and
     * withdraws it when the suspicion goes away. To avoid spamming when the
     * verdict flaps around the threshold, a new alert for the same
     * reservoir is not raised again within [LEAK_REALERT_MS] (the status
     * line on the card keeps showing the current state either way).
     */
    private fun updateLeakAlert(slot: Int, leak: LeakAssessment) {
        val previous = lastLeakStatus.put(slot, leak.status)
        val manager = getSystemService(NotificationManager::class.java)
        val id = LEAK_NOTIFICATION_BASE_ID + slot

        if (leak.status == LeakStatus.SUSPECTED) {
            val now = System.currentTimeMillis()
            val lastAlert = lastLeakAlertAt[slot]
            if (previous != LeakStatus.SUSPECTED &&
                (lastAlert == null || now - lastAlert > LEAK_REALERT_MS)
            ) {
                lastLeakAlertAt[slot] = now
                manager.notify(id, buildLeakNotification(slot, leak))
            }
        } else if (previous == LeakStatus.SUSPECTED) {
            manager.cancel(id)
        }
    }

    private fun buildLeakNotification(slot: Int, leak: LeakAssessment): Notification {
        val label = if (slot == TelemetryReading.DEVICE_ID_CISTERN) "Cistern" else "Tank"
        val lph = leak.estimatedLph ?: 0.0
        val why = when (leak.reason) {
            LeakReason.QUIET_PERIOD_FLOW -> "water keeps draining when it should be idle"
            LeakReason.CONTINUOUS_FLOW -> "water has been flowing for hours without a pause"
            null -> ""
        }
        val text = String.format(
            Locale.getDefault(),
            "%s: possible leak, about %.1f L/h (≈ %.0f L/day) — %s.",
            label, lph, lph * 24, why
        )

        val openApp = PendingIntent.getActivity(
            this, slot,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Possible water leak")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
    }

    // =====================================================================
    // NOTIFICATION
    // =====================================================================
    private fun describe(state: BleConnectionState): String = when (state) {
        is BleConnectionState.Disconnected -> "Disconnected — reconnecting automatically"
        is BleConnectionState.Scanning -> "Scanning for the gateway…"
        is BleConnectionState.Connecting -> "Connecting…"
        is BleConnectionState.NegotiatingMtu -> "Negotiating MTU…"
        is BleConnectionState.Connected -> "Connected"
        is BleConnectionState.ScanFailed -> "Scan failed (${state.reason})"
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val text = lastReadingAt?.let {
            "$connectionText • last reading ${String.format(Locale.getDefault(), "%tT", it)}"
        } ?: connectionText

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("LoRaBlue gateway")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gateway connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps collecting telemetry from the LoRa bridge"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Leak alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Warns when a reservoir seems to be losing water" }
        )
    }
}