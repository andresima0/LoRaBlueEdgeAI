package com.android.lorablueedge.mqtt

import android.util.Log
import com.android.lorablueedge.data.TelemetryReading
import com.android.lorablueedge.data.WaterColumnReading
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Publishes telemetry to the configured IoT platform (ThingsBoard or Konker).
 *
 * Platform differences handled here:
 *
 *  ThingsBoard — both devices share the fixed topic "v1/devices/me/telemetry";
 *    device identity comes from the access token passed as MQTT Username.
 *    Two separate connects are issued (one per device) with different tokens.
 *
 *  Konker — shared Username/Password auth; device identity comes from the
 *    topic (each device has its own topic configured in the dialog).
 *
 * Water level fields: the payload sends the already-converted water
 * column height ("water_dpt", meters) and fill percentage ("water_pct"),
 * not the raw TOF sensor distance. Both are computed by
 * WaterLevelCalculator from the user-configured tank/cistern total depth
 * (see TankDepthConfigStore) and passed in via [WaterColumnReading]. If
 * the total depth hasn't been configured yet for a device, both fields
 * are sent as JSON null — there's nothing meaningful to report.
 *
 * Connect-per-publish pattern: opens a fresh connection, publishes one
 * message, then disconnects. Acceptable because LoRa telemetry arrives every
 * few seconds at most. Switch to a persistent client if frequency increases.
 *
 * Always runs on a background thread — MqttClient.connect()/publish() block.
 */
class MqttPublisher {

    companion object {
        private const val TAG = "MQTT_PUB"
    }

    var onPublishResult: (success: Boolean, message: String) -> Unit = { _, _ -> }

    fun publish(config: MqttConfig, reading: TelemetryReading, waterColumn: WaterColumnReading) {
        when (config) {
            is MqttConfig.ThingsBoard -> publishThingsBoard(config, reading, waterColumn)
            is MqttConfig.Konker      -> publishKonker(config, reading, waterColumn)
        }
    }

    // ── ThingsBoard ──────────────────────────────────────────────────────────

    private fun publishThingsBoard(
        config: MqttConfig.ThingsBoard,
        reading: TelemetryReading,
        waterColumn: WaterColumnReading
    ) {
        val token: String
        val payload: String

        when (reading) {
            is TelemetryReading.Cistern -> {
                if (!config.isCisternConfigured) {
                    Log.w(TAG, "[TB] Publish skipped — Cistern token not configured")
                    return
                }
                token   = config.cisternToken
                payload = buildCisternPayload(reading, waterColumn)  // no "id" field — token is the identity
            }
            is TelemetryReading.Tank -> {
                if (!config.isTankConfigured) {
                    Log.w(TAG, "[TB] Publish skipped — Tank token not configured")
                    return
                }
                token   = config.tankToken
                payload = buildTankPayload(reading, waterColumn)
            }
        }

        doPublish(
            brokerUrl = config.brokerUrl,
            topic     = config.telemetryTopic,
            username  = token,
            password  = null,           // ThingsBoard ignores the password with token auth
            payload   = payload,
            label     = "ThingsBoard"
        )
    }

    // ── Konker ───────────────────────────────────────────────────────────────

    private fun publishKonker(
        config: MqttConfig.Konker,
        reading: TelemetryReading,
        waterColumn: WaterColumnReading
    ) {
        val topic: String
        val payload: String

        when (reading) {
            is TelemetryReading.Cistern -> {
                if (!config.isCisternConfigured) {
                    Log.w(TAG, "[Konker] Publish skipped — Cistern topic not configured")
                    return
                }
                topic   = config.cisternTopic
                payload = buildCisternPayload(reading, waterColumn, includeId = true)
            }
            is TelemetryReading.Tank -> {
                if (!config.isTankConfigured) {
                    Log.w(TAG, "[Konker] Publish skipped — Tank topic not configured")
                    return
                }
                topic   = config.tankTopic
                payload = buildTankPayload(reading, waterColumn, includeId = true)
            }
        }

        doPublish(
            brokerUrl = config.brokerUrl,
            topic     = topic,
            username  = config.user,
            password  = config.pass.ifBlank { null },
            payload   = payload,
            label     = "Konker"
        )
    }

    // ── Shared publish logic ─────────────────────────────────────────────────

    private fun doPublish(
        brokerUrl: String,
        topic: String,
        username: String,
        password: String?,
        payload: String,
        label: String
    ) {
        thread {
            val result = runCatching {
                val clientId = "lorablue-${System.currentTimeMillis()}"
                val client   = MqttClient(brokerUrl, clientId, MemoryPersistence())
                val opts = MqttConnectOptions().apply {
                    isCleanSession    = true
                    connectionTimeout = 15
                    keepAliveInterval = 20
                    if (username.isNotBlank()) {
                        userName = username
                        if (password != null) this.password = password.toCharArray()
                    }
                }
                client.connect(opts)
                client.publish(topic, MqttMessage(payload.toByteArray()).apply { qos = 1 })
                client.disconnect()
                client.close()
                payload
            }

            result.fold(
                onSuccess = {
                    Log.d(TAG, "[$label] Published [$topic]: $it")
                    onPublishResult(true, "[$label] Published [$topic]")
                },
                onFailure = { e ->
                    val msg = formatError(e, topic, label)
                    Log.e(TAG, msg)
                    onPublishResult(false, msg)
                }
            )
        }
    }

    // ── Payload builders ─────────────────────────────────────────────────────

    /**
     * ThingsBoard does not need an "id" field — the token identifies the device.
     * Konker uses the topic for routing but also accepts (and stores) "id" as a
     * data field, so [includeId] is true for Konker publishes.
     *
     * "water_dpt" (water column height, meters) and "water_pct" (fill
     * percentage) replace the old raw sensor distance field. Both are sent
     * as JSON null when the tank/cistern total depth hasn't been
     * configured yet (see WaterColumnReading).
     */
    private fun buildCisternPayload(
        reading: TelemetryReading.Cistern,
        waterColumn: WaterColumnReading,
        includeId: Boolean = false
    ): String = JSONObject().apply {
        if (includeId) put("id", TelemetryReading.DEVICE_ID_CISTERN)
        put("water_dpt",  waterColumn.columnMeters ?: JSONObject.NULL)
        put("water_pct",  waterColumn.percent ?: JSONObject.NULL)
        put("water_pump", reading.pumpOn)
        put("batt_lvl",   reading.batteryPercent)
        put("rssi_lvl",   reading.rssiDbm)
    }.toString()

    private fun buildTankPayload(
        reading: TelemetryReading.Tank,
        waterColumn: WaterColumnReading,
        includeId: Boolean = false
    ): String = JSONObject().apply {
        if (includeId) put("id", TelemetryReading.DEVICE_ID_TANK)
        put("water_dpt", waterColumn.columnMeters ?: JSONObject.NULL)
        put("water_pct", waterColumn.percent ?: JSONObject.NULL)
        put("batt_lvl",  reading.batteryPercent)
        put("rssi_lvl",  reading.rssiDbm)
    }.toString()

    private fun formatError(e: Throwable, topic: String, label: String): String {
        val reason = (e as? MqttException)?.let { "reasonCode=${it.reasonCode} " } ?: ""
        return "[$label] MQTT error [$topic]: ${e.javaClass.simpleName} $reason${e.message ?: ""}"
    }
}