package com.tailapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BleConnectionManager(private val context: Context) {

    companion object {
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private val operationMutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<DiscoveredService>>(emptyList())
    val discoveredServices: StateFlow<List<DiscoveredService>> = _discoveredServices.asStateFlow()

    private val _characteristicUpdate = MutableSharedFlow<CharacteristicUpdate>(extraBufferCapacity = 16)
    val characteristicUpdate: SharedFlow<CharacteristicUpdate> = _characteristicUpdate.asSharedFlow()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _discoveredServices.value = emptyList()
                    this@BleConnectionManager.gatt?.close()
                    this@BleConnectionManager.gatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _discoveredServices.value = gatt.services.map { service ->
                    DiscoveredService(
                        uuid = service.uuid,
                        characteristics = service.characteristics.map { char ->
                            DiscoveredCharacteristic(
                                uuid = char.uuid,
                                properties = char.properties,
                                isReadable = char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                                isWritable = char.properties and (
                                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                                ) != 0,
                                isNotifiable = char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                            )
                        }
                    )
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _characteristicUpdate.tryEmit(
                    CharacteristicUpdate(characteristic.uuid, characteristic.value ?: byteArrayOf())
                )
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            _characteristicUpdate.tryEmit(
                CharacteristicUpdate(characteristic.uuid, characteristic.value ?: byteArrayOf())
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        _connectionState.value = ConnectionState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    suspend fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        operationMutex.withLock {
            val characteristic = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
            gatt?.readCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun writeCharacteristic(
        serviceUuid: UUID,
        charUuid: UUID,
        value: ByteArray,
        noResponse: Boolean = false
    ) {
        operationMutex.withLock {
            val characteristic = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
            characteristic.writeType = if (noResponse)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            gatt?.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun enableNotifications(serviceUuid: UUID, charUuid: UUID) {
        operationMutex.withLock {
            val characteristic = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
            gatt?.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt?.writeDescriptor(descriptor)
        }
    }
}

data class DiscoveredService(
    val uuid: UUID,
    val characteristics: List<DiscoveredCharacteristic>
)

data class DiscoveredCharacteristic(
    val uuid: UUID,
    val properties: Int,
    val isReadable: Boolean,
    val isWritable: Boolean,
    val isNotifiable: Boolean
)

data class CharacteristicUpdate(
    val uuid: UUID,
    val value: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharacteristicUpdate) return false
        return uuid == other.uuid && value.contentEquals(other.value)
    }

    override fun hashCode(): Int = 31 * uuid.hashCode() + value.contentHashCode()
}
