package com.android.lorablueedge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.android.lorablueedge.data.BleMessage
import com.android.lorablueedge.data.JsonParser
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns every direct interaction with the Android BLE APIs: scanning, GATT
 * connection lifecycle, the write-operation queue, MTU negotiation,
 * notification reassembly and AUTOMATIC RECONNECTION (RNF03).
 *
 * Reconnection model: once [startScan] is called, the manager keeps the
 * link alive until [stop] is called. Any loss of connection (Bridge
 * powered off, out of range, GATT error 133, connection attempt that never
 * answers, services missing) ends in [scheduleReconnect], which restarts
 * the scan after an exponentially growing delay (2 s, 4 s, ... capped at
 * 30 s). The delay is reset as soon as a connection is fully established.
 *
 * This class has no Android UI dependency, and is owned by
 * LoRaBlueService so the link survives the Activity being closed.
 */
@SuppressLint("MissingPermission")
class BleManager(private val appContext: Context) {

    companion object {
        private const val TAG = "BLE_MGR"
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        // Covers connect + MTU negotiation + service discovery. If the
        // link is not fully usable by then, the attempt is abandoned.
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }

    // -------------------------------------------------------------------
    // Public callbacks — the service wires these up
    // -------------------------------------------------------------------
    var onStateChanged: (BleConnectionState) -> Unit = {}
    var onMessageReceived: (BleMessage) -> Unit = {}

    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var isScanning = false

    // Reconnection state
    @Volatile private var autoReconnect = false
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

    private val reconnectRunnable = Runnable { if (autoReconnect) beginScan() }

    private val connectTimeoutRunnable = Runnable {
        Log.w(TAG, "Connection attempt timed out — abandoning and retrying")
        closeGatt()
        onStateChanged(BleConnectionState.Disconnected)
        scheduleReconnect()
    }

    private val bleQueue = ConcurrentLinkedQueue<Runnable>()
    private var isBleBusy = false

    private val jsonParser = JsonParser()

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    val isConnected: Boolean
        get() = bluetoothGatt != null && rxCharacteristic != null

    // =====================================================================
    // OPERATION QUEUE — serialises GATT writes to prevent stack crashes.
    // Android's BLE stack can only have one outstanding GATT operation at
    // a time; firing writeDescriptor/writeCharacteristic concurrently is a
    // common source of silent failures and stack crashes on some OEM ROMs.
    // =====================================================================
    private fun enqueueBleOperation(operation: Runnable) {
        bleQueue.add(operation)
        if (!isBleBusy) doNextBleOperation()
    }

    private fun doNextBleOperation() {
        if (bleQueue.isEmpty()) {
            isBleBusy = false
            return
        }
        isBleBusy = true
        bleQueue.poll()?.run()
    }

    // =====================================================================
    // PUBLIC LIFECYCLE
    // =====================================================================

    /**
     * Starts (or resumes) the connection loop. Safe to call repeatedly:
     * does nothing while a scan or a connection already exists.
     */
    fun startScan() {
        autoReconnect = true
        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        mainHandler.removeCallbacks(reconnectRunnable)
        beginScan()
    }

    /** Stops scanning, drops the connection and disables auto-reconnect. */
    fun stop() {
        autoReconnect = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(connectTimeoutRunnable)

        if (isScanning) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan failed: ${e.message}")
            }
            isScanning = false
        }

        closeGatt()
        onStateChanged(BleConnectionState.Disconnected)
    }

    // =====================================================================
    // SCAN
    // =====================================================================
    private fun beginScan() {
        if (isScanning || bluetoothGatt != null) return

        val scanner = bluetoothAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "Bluetooth off or scanner unavailable — will retry")
            onStateChanged(BleConnectionState.Disconnected)
            scheduleReconnect()
            return
        }

        // Filtering by Service UUID is more reliable than by device name:
        // many Android phones return device.name == null during scanning,
        // and the name only resolves after bonding or a full scan response.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            onStateChanged(BleConnectionState.Scanning)
            scanner.stopScan(scanCallback)
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            // Permission revoked while running — retrying cannot help.
            Log.e(TAG, "Missing BLE permission: ${e.message}")
            isScanning = false
            autoReconnect = false
            onStateChanged(BleConnectionState.ScanFailed("permission denied"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Several results can be queued before stopScan takes effect;
            // only the first one may open a connection.
            if (!isScanning) return
            isScanning = false
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)

            onStateChanged(BleConnectionState.Connecting)
            mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
            bluetoothGatt = result.device.connectGatt(
                appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "scan already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE unsupported"
                else -> "error $errorCode"
            }
            Log.e("BLE_SCAN", "Scan failed: $reason")
            onStateChanged(BleConnectionState.ScanFailed(reason))
            scheduleReconnect()
        }
    }

    // =====================================================================
    // RECONNECT
    // =====================================================================
    private fun scheduleReconnect() {
        if (!autoReconnect) return
        mainHandler.removeCallbacks(reconnectRunnable)
        Log.d(TAG, "Reconnecting in ${reconnectDelayMs / 1000} s")
        mainHandler.postDelayed(reconnectRunnable, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        bleQueue.clear()
        isBleBusy = false
        jsonParser.clear()
    }

    // =====================================================================
    // GATT CALLBACK
    // =====================================================================
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStateChanged(BleConnectionState.NegotiatingMtu)
                    bleQueue.clear()
                    jsonParser.clear()
                    isBleBusy = false
                    gatt.requestMtu(BleConstants.PREFERRED_MTU)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    onStateChanged(BleConnectionState.Disconnected)
                    isBleBusy = false
                    jsonParser.clear()
                    rxCharacteristic = null

                    // Known Android BLE stack workaround: force-clear the
                    // GATT cache via reflection so a re-pair/reconnect picks
                    // up fresh services instead of a stale cached profile.
                    try {
                        val refreshMethod = gatt.javaClass.getMethod("refresh")
                        val result = refreshMethod.invoke(gatt) as Boolean
                        Log.d("BLE_CACHE", "GATT cache cleared: $result")
                    } catch (e: Exception) {
                        Log.e("BLE_CACHE", "Failed to clear GATT cache: ${e.message}")
                    }

                    gatt.close()
                    bluetoothGatt = null

                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BLE_MTU", "MTU negotiated: $mtu bytes, status: $status")
            onStateChanged(BleConnectionState.Connected(mtu))
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed (status=$status) — reconnecting")
                gatt.disconnect()
                return
            }

            val service = gatt.getService(BleConstants.SERVICE_UUID)
            val txChar = service?.getCharacteristic(BleConstants.TX_CHAR_UUID)
            if (service == null || txChar == null) {
                Log.e(TAG, "Gateway service/characteristic not found — reconnecting")
                gatt.disconnect()
                return
            }

            rxCharacteristic = service.getCharacteristic(BleConstants.RX_CHAR_UUID)
            gatt.setCharacteristicNotification(txChar, true)

            // Link is fully usable: stop the watchdog and reset the backoff.
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

            enqueueBleOperation(Runnable {
                val descriptor = txChar.getDescriptor(BleConstants.CCCD_UUID)
                if (descriptor == null) {
                    Log.e(TAG, "CCCD descriptor missing")
                    doNextBleOperation()
                    return@Runnable
                }
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val success = gatt.writeDescriptor(descriptor)
                if (!success) doNextBleOperation()
            })
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) = doNextBleOperation()

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = doNextBleOperation()

        // Legacy callback — required for Android < 13 (API < 33)
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleIncomingData(characteristic.uuid, value)
        }

        // Modern callback — Android 13+ (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingData(characteristic.uuid, value)
        }
    }

    private fun handleIncomingData(uuid: java.util.UUID, value: ByteArray) {
        if (uuid != BleConstants.TX_CHAR_UUID) return

        val chunk = String(value, Charsets.UTF_8)
        jsonParser.feed(chunk).forEach { onMessageReceived(it) }
    }

    // =====================================================================
    // SEND COMMAND (App → Board)
    // =====================================================================
    fun sendCommand(command: String) {
        val gatt = bluetoothGatt
        val char = rxCharacteristic
        if (gatt == null || char == null) {
            Log.w("BLE_TX", "sendCommand('$command') ignored — not connected")
            return
        }

        enqueueBleOperation(Runnable {
            val payload = "$command\n".toByteArray(Charsets.UTF_8)
            char.value = payload
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val success = gatt.writeCharacteristic(char)
            if (!success) {
                Log.e("BLE_TX", "Write failed for command: $command")
                doNextBleOperation()
            } else {
                Log.d("BLE_TX", "Command sent: $command")
            }
        })
    }
}