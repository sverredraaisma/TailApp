package com.tailapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.model.BleDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class BleScanner(context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner get() = bluetoothAdapter?.bluetoothLeScanner

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Non-null when the last [startScan] could not be started; cleared on the next attempt. */
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, BleDevice>()

    private val handler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable {
        Log.d(TAG, "scan timed out after ${SCAN_TIMEOUT_MS}ms")
        stopScan()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            record(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { record(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            // Without this the UI would spin on a scan that never started.
            Log.e(TAG, "onScanFailed: errorCode=$errorCode")
            _isScanning.value = false
            _scanError.value = scanFailureMessage(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(result: ScanResult) {
        val device = BleDevice(
            name = result.device.name,
            address = result.device.address,
            rssi = result.rssi
        )
        deviceMap[device.address] = device
        _devices.value = deviceMap.values
            .sortedWith(compareByDescending<BleDevice> { it.isTailController }.thenByDescending { it.rssi })
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        // Turning Bluetooth off mid-scan produces no onScanFailed, so the flag
        // would stay true forever and every later tap would early-return here.
        // Reconcile it against the adapter before believing it.
        if (_isScanning.value) {
            if (isAdapterOn()) return
            Log.w(TAG, "startScan: adapter is off, clearing stale scanning state")
            stopScan()
        }
        _scanError.value = null

        val activeScanner = if (isAdapterOn()) scanner else null
        if (activeScanner == null) {
            Log.w(TAG, "startScan: no LE scanner (Bluetooth off?)")
            _scanError.value = "Bluetooth is turned off"
            return
        }

        deviceMap.clear()
        _devices.value = emptyList()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(CharacteristicUuids.SERVICE))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Bluetooth can be switched off between the check and the call, and the
        // scan permission can be revoked from Settings while the app is alive —
        // that one arrives as a SecurityException and, uncaught, takes the
        // process down from the screen's onDispose.
        try {
            activeScanner.startScan(filters, settings, scanCallback)
            _isScanning.value = true
            // Nothing else ever ends a scan that simply finds nothing; the UI
            // offers "Scan again" for exactly this state.
            handler.removeCallbacks(scanTimeout)
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startScan failed", e)
            _scanError.value = "Bluetooth is turned off"
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan denied", e)
            _scanError.value = "Bluetooth scan permission was denied"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!_isScanning.value) return
        _isScanning.value = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopScan failed (adapter off?)", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan denied (permission revoked?)", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isAdapterOn(): Boolean = bluetoothAdapter?.isEnabled == true

    private fun scanFailureMessage(errorCode: Int): String = when (errorCode) {
        SCAN_FAILED_ALREADY_STARTED -> "A scan is already running"
        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Could not register the scan with the system"
        SCAN_FAILED_FEATURE_UNSUPPORTED -> "This device does not support the requested scan"
        SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
        else -> "Scan failed (code $errorCode)"
    }

    private companion object {
        const val TAG = "BleScanner"
        const val SCAN_TIMEOUT_MS = 30_000L
        const val SCAN_FAILED_ALREADY_STARTED = 1
        const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
        const val SCAN_FAILED_INTERNAL_ERROR = 3
        const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
    }
}
