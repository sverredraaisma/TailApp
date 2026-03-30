package com.tailapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
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

    private val deviceMap = ConcurrentHashMap<String, BleDevice>()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = BleDevice(
                name = result.device.name,
                address = result.device.address,
                rssi = result.rssi
            )
            deviceMap[device.address] = device
            _devices.value = deviceMap.values
                .sortedWith(compareByDescending<BleDevice> { it.isTailController }.thenByDescending { it.rssi })
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) return
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

        scanner?.startScan(filters, settings, scanCallback)
        _isScanning.value = true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
    }
}
