package com.tailapp.testutil

import com.tailapp.ble.BleTransport
import com.tailapp.ble.CharacteristicUpdate
import com.tailapp.ble.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/** In-memory [BleTransport] that records traffic and replays canned reads. */
class FakeBleTransport : BleTransport {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _characteristicUpdate = MutableSharedFlow<CharacteristicUpdate>(extraBufferCapacity = 64)
    override val characteristicUpdate: SharedFlow<CharacteristicUpdate> = _characteristicUpdate.asSharedFlow()

    private val _negotiatedMtu = MutableStateFlow(23)
    override val negotiatedMtu: StateFlow<Int> = _negotiatedMtu

    /** Every write-with-response, in order. */
    val writes = mutableListOf<Write>()

    /** Every write-without-response (FF05 FFT frames), in order. */
    val writesWithoutResponse = mutableListOf<Write>()

    /** UUIDs that had notifications enabled, in order. */
    val enabledNotifications = mutableListOf<UUID>()

    /** UUIDs read, in order (duplicates preserved). */
    val readLog = mutableListOf<UUID>()

    /** Canned read responses; a missing entry reads as null. */
    val readResponses = mutableMapOf<UUID, ByteArray>()

    var writeResult: Boolean = true
    var discoverServicesResult: Boolean = true

    /** Virtual delay applied to each read, for exercising slow-setup behaviour. */
    var readDelayMs: Long = 0
    var requestedMtu: Int? = null
    var connectedAddress: String? = null
    var disconnectCount: Int = 0

    data class Write(val uuid: UUID, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Write) return false
            return uuid == other.uuid && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * uuid.hashCode() + data.contentHashCode()
    }

    override fun connect(address: String) {
        connectedAddress = address
        _connectionState.value = ConnectionState.CONNECTING
    }

    override fun disconnect() {
        disconnectCount++
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun requestMtu(mtu: Int): Int {
        requestedMtu = mtu
        _negotiatedMtu.value = mtu
        return mtu
    }

    override suspend fun discoverServices(): Boolean = discoverServicesResult

    override suspend fun readCharacteristic(uuid: UUID): ByteArray? {
        if (readDelayMs > 0) delay(readDelayMs)
        readLog.add(uuid)
        return readResponses[uuid]
    }

    override suspend fun writeCharacteristic(uuid: UUID, data: ByteArray): Boolean {
        writes.add(Write(uuid, data))
        return writeResult
    }

    override fun writeWithoutResponse(uuid: UUID, data: ByteArray) {
        writesWithoutResponse.add(Write(uuid, data))
    }

    override suspend fun enableNotifications(uuid: UUID): Boolean {
        enabledNotifications.add(uuid)
        return true
    }

    // --- test controls ---

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    suspend fun notify(uuid: UUID, value: ByteArray) {
        _characteristicUpdate.emit(CharacteristicUpdate(uuid, value))
    }

    fun setMtu(mtu: Int) {
        _negotiatedMtu.value = mtu
    }

    fun writesTo(uuid: UUID): List<ByteArray> = writes.filter { it.uuid == uuid }.map { it.data }

    fun readCountFor(uuid: UUID): Int = readLog.count { it == uuid }

    fun clearTraffic() {
        writes.clear()
        writesWithoutResponse.clear()
        readLog.clear()
    }
}
