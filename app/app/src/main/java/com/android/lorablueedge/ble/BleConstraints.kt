package com.android.lorablueedge.ble

import java.util.UUID

/**
 * Nordic UART Service (NUS) UUIDs used by the LoRaBlue gateway firmware.
 * Centralised here so the Android side and any future module share one
 * source of truth instead of duplicating UUID literals.
 */
object BleConstants {
    const val DEVICE_NAME = "LoRaBlue_Gateway"

    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // App → Board
    val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Board → App
    val CCCD_UUID: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PREFERRED_MTU = 247
}