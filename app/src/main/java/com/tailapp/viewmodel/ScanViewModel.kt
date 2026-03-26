package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import com.tailapp.ble.BleScanner
import com.tailapp.model.BleDevice
import kotlinx.coroutines.flow.StateFlow

class ScanViewModel(private val bleScanner: BleScanner) : ViewModel() {

    val scannedDevices: StateFlow<List<BleDevice>> = bleScanner.scannedDevices
    val isScanning: StateFlow<Boolean> = bleScanner.isScanning

    fun startScan() = bleScanner.startScan()

    fun stopScan() = bleScanner.stopScan()

    override fun onCleared() {
        super.onCleared()
        bleScanner.stopScan()
    }
}
