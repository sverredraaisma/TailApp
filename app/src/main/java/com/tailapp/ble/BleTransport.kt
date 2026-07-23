package com.tailapp.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

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

/**
 * The GATT operations [com.tailapp.repository.DeviceRepository] depends on.
 *
 * Split out from [BleConnectionManager] so the repository can be exercised
 * against a fake transport in unit tests — the real implementation touches
 * Android's Bluetooth stack in its constructor.
 */
interface BleTransport {
    val connectionState: StateFlow<ConnectionState>
    val characteristicUpdate: SharedFlow<CharacteristicUpdate>
    val negotiatedMtu: StateFlow<Int>

    fun connect(address: String)
    fun disconnect()

    suspend fun requestMtu(mtu: Int): Int
    suspend fun discoverServices(): Boolean
    suspend fun readCharacteristic(uuid: UUID): ByteArray?
    suspend fun writeCharacteristic(uuid: UUID, data: ByteArray): Boolean
    fun writeWithoutResponse(uuid: UUID, data: ByteArray)
    suspend fun enableNotifications(uuid: UUID): Boolean
}
