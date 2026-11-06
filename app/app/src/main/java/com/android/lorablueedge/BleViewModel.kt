package com.android.lorablueedge

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.android.lorablueedge.analytics.AutonomyEstimate
import com.android.lorablueedge.analytics.ConsumptionSummary
import com.android.lorablueedge.analytics.LeakAssessment
import com.android.lorablueedge.analytics.RainForecast
import com.android.lorablueedge.ble.BleConnectionState
import com.android.lorablueedge.data.TelemetryReading
import com.android.lorablueedge.service.GatewayHub
import com.android.lorablueedge.service.LoRaBlueService

/**
 * Thin facade between the UI and [LoRaBlueService].
 *
 * The BLE connection, MQTT publishing, water-level conversion and history
 * persistence all live in the foreground service now, so they keep running
 * when this ViewModel (and the Activity) are gone. This class only:
 *   - exposes the service's state as LiveData (same names as before, so
 *     MainActivity's observers are unchanged), and
 *   - forwards user actions (connect / ping / clear / reload MQTT config).
 */
class BleViewModel(application: Application) : AndroidViewModel(application) {

    val connectionState: LiveData<BleConnectionState> = GatewayHub.connectionState
    val cisternData: LiveData<TelemetryReading.Cistern> = GatewayHub.cisternData
    val tankData: LiveData<TelemetryReading.Tank> = GatewayHub.tankData
    val cisternConsumption: LiveData<ConsumptionSummary> = GatewayHub.cisternConsumption
    val tankConsumption: LiveData<ConsumptionSummary> = GatewayHub.tankConsumption
    val cisternLeak: LiveData<LeakAssessment> = GatewayHub.cisternLeak
    val tankLeak: LiveData<LeakAssessment> = GatewayHub.tankLeak
    val autonomy: LiveData<AutonomyEstimate> = GatewayHub.autonomy
    val rain: LiveData<RainForecast> = GatewayHub.rain
    val weatherStatus: LiveData<String> = GatewayHub.weatherStatus
    val debugLog: LiveData<String> = GatewayHub.debugLog
    val mqttStatus: LiveData<String> = GatewayHub.mqttStatus

    val isBluetoothEnabled: Boolean
        get() {
            val manager = getApplication<Application>()
                .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return manager.adapter?.isEnabled == true
        }

    /** Starts the foreground service (if needed) and the connection loop. */
    fun startScan() = LoRaBlueService.startScan(getApplication())

    fun sendPing() {
        LoRaBlueService.instance?.sendCommand("PING")
    }

    fun sendClear() {
        LoRaBlueService.instance?.sendCommand("CLEAR")
    }

    /**
     * Called after MqttSettingsActivity saved. If the service is not
     * running yet there is nothing to refresh: it reads the stored
     * configuration when it starts.
     */
    fun reloadMqttConfigs() {
        LoRaBlueService.instance?.reloadMqttConfigs()
    }

    /** Called after the weather settings were saved: fetch a forecast right away. */
    fun reloadWeather() {
        LoRaBlueService.instance?.reloadWeather()
    }
}