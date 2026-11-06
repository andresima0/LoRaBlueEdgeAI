package com.android.lorablueedge.data

import android.util.Log
import org.json.JSONObject

/**
 * Reassembles BLE notification fragments into complete JSON messages and
 * parses them into [BleMessage]. This class owns the reassembly buffer
 * exclusively — nothing outside it should touch raw byte chunks.
 *
 * The firmware always terminates every JSON payload with '\n'. BLE may
 * still fragment a message across multiple notifications regardless of
 * negotiated MTU, so chunks are appended here and only flushed into
 * complete messages when a newline is found.
 *
 * Telemetry schema sent by the Bridge firmware (receiver.cpp):
 *   Cistern: {"node_id":1,"node_type":1,"water_lvl":0.52,"water_pump":true,"batt_lvl":87.0,"rssi_lvl":-45.0}
 *   Tank:    {"node_id":2,"node_type":2,"water_lvl":1.30,"batt_lvl":64.0,"rssi_lvl":-52.0}
 */
class JsonParser {

    private val buffer = StringBuilder()

    companion object {
        private const val TAG = "BLE_JSON"
        private const val MAX_BUFFER_SIZE = 2048
    }

    /**
     * Feed a raw BLE notification chunk. Returns zero or more complete
     * messages found after appending this chunk (usually zero or one,
     * but can be more if multiple JSONs arrived back-to-back).
     */
    fun feed(chunk: String): List<BleMessage> {
        buffer.append(chunk)

        // Safety valve: if the buffer grows without ever finding a newline,
        // something is wrong upstream (e.g. firmware regression). Discard
        // stale data rather than leaking memory over a long session.
        if (buffer.length > MAX_BUFFER_SIZE) {
            Log.w(TAG, "Buffer overflow (${buffer.length} bytes), clearing")
            buffer.clear()
            return emptyList()
        }

        val messages = mutableListOf<BleMessage>()
        var newlineIndex = buffer.indexOf("\n")

        while (newlineIndex != -1) {
            val fullMessage = buffer.substring(0, newlineIndex).trim()
            buffer.delete(0, newlineIndex + 1)

            if (fullMessage.isNotEmpty()) {
                Log.d("BLE_RX", "Complete packet: $fullMessage")
                messages.add(parse(fullMessage))
            }

            newlineIndex = buffer.indexOf("\n")
        }

        return messages
    }

    fun clear() = buffer.clear()

    private fun parse(message: String): BleMessage {
        return try {
            val json = JSONObject(message)

            when {
                // Telemetry packets always carry "node_id".
                json.has("node_id") -> parseTelemetry(json, message)

                json.has("debug") -> BleMessage.Debug(json.getString("debug"))

                else -> {
                    Log.w(TAG, "Unknown JSON keys in: $message")
                    BleMessage.Unknown(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discarding bad packet: '$message' — ${e.message}")
            BleMessage.Malformed(message, e.message)
        }
    }

    /**
     * "node_type" decides the reading type (1 = Cistern, 2 = Tank), so any
     * number of nodes of either type can share the Bridge. An unknown type
     * is reported as [BleMessage.Unknown] instead of being misread.
     */
    private fun parseTelemetry(json: JSONObject, raw: String): BleMessage {
        val nodeId = json.getInt("node_id")
        // Older firmware does not send "water_ok"; assume valid then.
        val waterValid = json.optBoolean("water_ok", true)

        val reading: TelemetryReading = when (val type = json.getInt("node_type")) {
            TelemetryReading.NODE_TYPE_CISTERN -> TelemetryReading.Cistern(
                nodeId = nodeId,
                waterLevel = json.getDouble("water_lvl"),
                pumpOn = json.getBoolean("water_pump"),
                batteryPercent = json.getDouble("batt_lvl"),
                rssiDbm = json.getDouble("rssi_lvl"),
                waterValid = waterValid
            )

            TelemetryReading.NODE_TYPE_TANK -> TelemetryReading.Tank(
                nodeId = nodeId,
                waterLevel = json.getDouble("water_lvl"),
                batteryPercent = json.getDouble("batt_lvl"),
                rssiDbm = json.getDouble("rssi_lvl"),
                waterValid = waterValid
            )

            else -> {
                Log.w(TAG, "Unknown node_type=$type in: $raw")
                return BleMessage.Unknown(raw)
            }
        }

        return BleMessage.Telemetry(reading)
    }
}