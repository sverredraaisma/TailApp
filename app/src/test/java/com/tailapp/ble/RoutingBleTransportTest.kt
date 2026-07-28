package com.tailapp.ble

import com.tailapp.testutil.FakeBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingBleTransportTest {

    private val real = FakeBleTransport()
    private val virtual = FakeBleTransport()

    /**
     * A scope for the relay's own sharing coroutines.
     *
     * Deliberately not `backgroundScope`: `advanceUntilIdle` returns as soon as
     * no *foreground* work remains, so anything launched in `backgroundScope` is
     * never dispatched — and `shareIn`/`stateIn` run exactly there. Tests that
     * only assert connect/disconnect side effects survive that; the ones that
     * read the routed flows see the initial value forever. Same reasoning as
     * `DeviceRepositoryTest`.
     */
    private fun TestScope.relayScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    @Test
    fun `switching to the virtual tail disconnects the real backend`() = runTest {
        val routing = RoutingBleTransport(real, virtual, backgroundScope)
        advanceUntilIdle()

        routing.connect("AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        assertEquals(0, real.disconnectCount)

        routing.connect(VirtualTailTransport.ADDRESS)
        advanceUntilIdle()

        // Nothing observes the real transport any more, so leaving it connected
        // would strand a BluetoothGatt for the life of the process.
        assertEquals(1, real.disconnectCount)
        assertEquals(VirtualTailTransport.ADDRESS, virtual.connectedAddress)
    }

    @Test
    fun `switching back disconnects the virtual backend`() = runTest {
        val routing = RoutingBleTransport(real, virtual, backgroundScope)
        advanceUntilIdle()

        routing.connect(VirtualTailTransport.ADDRESS)
        advanceUntilIdle()
        routing.connect("AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        assertEquals(1, virtual.disconnectCount)
        assertEquals("AA:BB:CC:DD:EE:FF", real.connectedAddress)
    }

    @Test
    fun `reconnecting to the same backend does not disconnect it first`() = runTest {
        val routing = RoutingBleTransport(real, virtual, backgroundScope)
        advanceUntilIdle()

        routing.connect("AA:BB:CC:DD:EE:FF")
        routing.connect("11:22:33:44:55:66")
        advanceUntilIdle()

        // BleConnectionManager closes its own previous client; a disconnect here
        // would only race that.
        assertEquals(0, real.disconnectCount)
        assertEquals("11:22:33:44:55:66", real.connectedAddress)
    }

    @Test
    fun `a burst of notifications survives a collector that is not keeping up`() = runTest {
        val scope = relayScope()
        val routing = RoutingBleTransport(real, virtual, scope)
        val received = mutableListOf<CharacteristicUpdate>()
        scope.launch { routing.characteristicUpdate.collect { received.add(it) } }
        advanceUntilIdle()

        val uuid = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        repeat(32) { i -> real.notify(uuid, byteArrayOf(i.toByte())) }
        advanceUntilIdle()

        // A smoke test for the relay's buffering: the routed flow must not lose
        // anything the backend's own 64-slot buffer accepted.
        assertEquals(32, received.size)
        assertEquals(31, received.last().value[0].toInt())
        scope.cancel()
    }

    @Test
    fun `flows and operations follow the active backend`() = runTest {
        val scope = relayScope()
        val routing = RoutingBleTransport(real, virtual, scope)
        advanceUntilIdle()

        routing.connect(VirtualTailTransport.ADDRESS)
        advanceUntilIdle()

        val uuid = UUID.fromString("0000ff06-0000-1000-8000-00805f9b34fb")
        virtual.readResponses[uuid] = byteArrayOf(0x06)
        assertTrue(routing.readCharacteristic(uuid).contentEquals(byteArrayOf(0x06)))
        assertEquals(0, real.readLog.size)

        virtual.setMtu(247)
        advanceUntilIdle()
        assertEquals(247, routing.negotiatedMtu.value)
        scope.cancel()
    }
}
