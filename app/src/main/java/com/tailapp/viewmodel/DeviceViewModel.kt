package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.ble.BleConnectionManager
import com.tailapp.ble.CharacteristicUpdate
import com.tailapp.ble.ConnectionState
import com.tailapp.ble.DiscoveredService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class DeviceViewModel(
    private val connectionManager: BleConnectionManager
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val discoveredServices: StateFlow<List<DiscoveredService>> = connectionManager.discoveredServices
    val characteristicUpdate: SharedFlow<CharacteristicUpdate> = connectionManager.characteristicUpdate

    private val _deviceAddress = MutableStateFlow("")
    val deviceAddress: StateFlow<String> = _deviceAddress.asStateFlow()

    fun connect(address: String) {
        _deviceAddress.value = address
        connectionManager.connect(address)
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        viewModelScope.launch {
            connectionManager.readCharacteristic(serviceUuid, charUuid)
        }
    }

    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, value: ByteArray) {
        viewModelScope.launch {
            connectionManager.writeCharacteristic(serviceUuid, charUuid, value)
        }
    }

    fun enableNotifications(serviceUuid: UUID, charUuid: UUID) {
        viewModelScope.launch {
            connectionManager.enableNotifications(serviceUuid, charUuid)
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.disconnect()
    }
}
