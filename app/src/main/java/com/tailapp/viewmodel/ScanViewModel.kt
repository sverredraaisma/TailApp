package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import com.tailapp.ble.BleScanner
import com.tailapp.model.BleDevice
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.StateFlow

class ScanViewModel(
    private val bleScanner: BleScanner,
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val devices: StateFlow<List<BleDevice>> = bleScanner.devices
    val isScanning: StateFlow<Boolean> = bleScanner.isScanning

    fun startScan() = bleScanner.startScan()
    fun stopScan() = bleScanner.stopScan()

    fun connect(address: String) {
        bleScanner.stopScan()
        deviceRepository.connect(address)
    }
}
