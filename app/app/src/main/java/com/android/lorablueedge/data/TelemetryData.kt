package com.android.lorablueedge.data

/**
 * Telemetry forwarded by the LoRa Bridge, identified by "node_id".
 *   Cistern: node_id, node_type, water_lvl, water_pump, batt_lvl, rssi_lvl
 *   Tank:    node_id, node_type, water_lvl, batt_lvl, rssi_lvl
 *
 * "node_type" (1 = Cistern, 2 = Tank) tells JsonParser which reading to
 * build. Both types share water level, battery and RSSI; the pump
 * state is the only type-specific field.
 */
sealed class TelemetryReading {

    abstract val nodeId: Int

    /** False when the node's distance sensor gave no reliable reading. */
    abstract val waterValid: Boolean

    data class Cistern(
        override val nodeId: Int,
        val waterLevel: Double,
        val pumpOn: Boolean,
        val batteryPercent: Double,
        val rssiDbm: Double,
        override val waterValid: Boolean = true
    ) : TelemetryReading()

    data class Tank(
        override val nodeId: Int,
        val waterLevel: Double,
        val batteryPercent: Double,
        val rssiDbm: Double,
        override val waterValid: Boolean = true
    ) : TelemetryReading()

    companion object {
        // Values of "node_type" in the Bridge JSON (match DEVICE_TYPE_* in radio.h).
        const val NODE_TYPE_CISTERN = 1
        const val NODE_TYPE_TANK = 2

        // Keys for the two UI slots (cards, depth config, chart screens).
        // They are NOT the firmware node_id anymore — see nodeId above.
        const val DEVICE_ID_CISTERN = 1
        const val DEVICE_ID_TANK = 2
    }
}

/**
 * Sealed result of parsing one complete (\n-delimited) BLE message.
 * Keeps JsonParser decoupled from the UI — it never touches a TextView.
 */
sealed class BleMessage {
    data class Telemetry(val reading: TelemetryReading) : BleMessage()
    data class Debug(val text: String) : BleMessage()
    data class Unknown(val raw: String) : BleMessage()
    data class Malformed(val raw: String, val reason: String?) : BleMessage()
}