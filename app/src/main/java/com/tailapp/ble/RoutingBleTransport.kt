package com.tailapp.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * A [BleTransport] that hands off to one of two backends per connection: the
 * real GATT stack, or the in-app [VirtualTailTransport].
 *
 * [DeviceRepository] depends on a single transport, so rather than teach it
 * about two, the choice is made here at [connect] time from the address — a
 * virtual address (see [VirtualTailTransport.ADDRESS]) selects the simulator,
 * anything else the radio. Everything downstream is identical either way, which
 * is the whole point of the [BleTransport] seam.
 *
 * The observable flows follow whichever backend is active, so a switch from a
 * real device to the virtual one (or back) is seamless to the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingBleTransport(
    private val real: BleTransport,
    private val virtual: BleTransport,
    scope: CoroutineScope
) : BleTransport {

    private val active = MutableStateFlow(real)

    override val connectionState: StateFlow<ConnectionState> =
        active.flatMapLatest { it.connectionState }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

    // The buffer is not optional. shareIn with replay = 0 gives the relay no
    // extra capacity and SUSPEND semantics, so a busy collector back-pressures
    // all the way into BleConnectionManager's tryEmit — which does not wait, it
    // drops. Buffering here is what makes that 64-slot backing store mean
    // anything; dropping the oldest is right for a 20 Hz state stream.
    override val characteristicUpdate: SharedFlow<CharacteristicUpdate> =
        active.flatMapLatest { it.characteristicUpdate }
            .buffer(64, BufferOverflow.DROP_OLDEST)
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override val negotiatedMtu: StateFlow<Int> =
        active.flatMapLatest { it.negotiatedMtu }
            .stateIn(scope, SharingStarted.Eagerly, real.negotiatedMtu.value)

    override fun connect(address: String) {
        val transport = if (VirtualTailTransport.isVirtualAddress(address)) virtual else real
        // Switching backends without this strands the one being left: nothing
        // downstream observes it any more, so a real BluetoothGatt would stay
        // open for the life of the process once the virtual tail took over.
        val previous = active.value
        if (previous !== transport) previous.disconnect()
        active.value = transport
        transport.connect(address)
    }

    override fun disconnect() = active.value.disconnect()

    override suspend fun requestMtu(mtu: Int): Int = active.value.requestMtu(mtu)

    override suspend fun discoverServices(): Boolean = active.value.discoverServices()

    override suspend fun readCharacteristic(uuid: UUID): ByteArray? =
        active.value.readCharacteristic(uuid)

    override suspend fun writeCharacteristic(uuid: UUID, data: ByteArray): Boolean =
        active.value.writeCharacteristic(uuid, data)

    override fun writeWithoutResponse(uuid: UUID, data: ByteArray) =
        active.value.writeWithoutResponse(uuid, data)

    override suspend fun enableNotifications(uuid: UUID): Boolean =
        active.value.enableNotifications(uuid)
}
