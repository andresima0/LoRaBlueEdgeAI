package com.android.lorablueedge.ble

/**
 * Connection lifecycle state exposed to the UI layer. Carrying the reason
 * string inside Disconnected/ScanFailed avoids the UI having to know about
 * Android's raw error codes.
 */
sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    object Connecting : BleConnectionState()
    object NegotiatingMtu : BleConnectionState()
    data class Connected(val mtu: Int) : BleConnectionState()
    data class ScanFailed(val reason: String) : BleConnectionState()
}