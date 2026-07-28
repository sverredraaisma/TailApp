package com.tailapp.viewmodel

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResult
import com.tailapp.ble.protocol.Protocol
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FirmwarePayloads
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two device-administration commands whose answer the user has to see:
 * renaming the tail and forgetting a bond.
 *
 * Everything else on this screen is deliberately optimistic and reconciles from
 * the device a second later. These two cannot be — a name the tail is not
 * advertising and a phone that is still paired are both wrong in ways the next
 * refresh would not visibly correct.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceOverviewViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private var repositoryScope: CoroutineScope? = null

    @Before
    fun installMainDispatcher() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        repositoryScope?.cancel()
        repositoryScope = null
        Dispatchers.resetMain()
    }

    private fun TestScope.connectedRepository(transport: FakeBleTransport): DeviceRepository {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        repositoryScope = scope
        val repository = DeviceRepository(transport, scope)
        advanceUntilIdle()
        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceUntilIdle()
        return repository
    }

    private fun TestScope.connectedViewModel(
        transport: FakeBleTransport
    ): DeviceOverviewViewModel = DeviceOverviewViewModel(connectedRepository(transport))

    @Test
    fun `an over-long name is refused here rather than sent to be rejected`() = runTest {
        val transport = FakeBleTransport()
        val viewModel = connectedViewModel(transport)

        viewModel.renameDevice("a".repeat(Protocol.MAX_DEVICE_NAME_LEN + 1))
        advanceUntilIdle()

        val status = requireNotNull(viewModel.adminStatus.value)
        assertFalse(status.accepted)
        // The point of checking client-side: the device answers OUT_OF_RANGE,
        // which cannot say which rule was broken or what the limit is.
        assertTrue(status.message.contains("${Protocol.MAX_DEVICE_NAME_LEN} bytes"))
        assertTrue(
            "nothing should have gone to FF06",
            transport.writesTo(CharacteristicUuids.SYSTEM_CONFIG).isEmpty()
        )
    }

    @Test
    fun `an empty name is refused, because it would advertise nothing findable`() = runTest {
        val transport = FakeBleTransport()
        val viewModel = connectedViewModel(transport)

        viewModel.renameDevice("   ")
        advanceUntilIdle()

        assertFalse(requireNotNull(viewModel.adminStatus.value).accepted)
        assertTrue(transport.writesTo(CharacteristicUuids.SYSTEM_CONFIG).isEmpty())
    }

    @Test
    fun `an accepted rename writes the trimmed name and reports success`() = runTest {
        val transport = FakeBleTransport()
        val viewModel = connectedViewModel(transport)

        viewModel.renameDevice("  Foxtail  ")
        advanceUntilIdle()

        assertArrayEquals(
            byteArrayOf(0x04) + "Foxtail".toByteArray(Charsets.UTF_8),
            transport.writesTo(CharacteristicUuids.SYSTEM_CONFIG).single()
        )
        val status = requireNotNull(viewModel.adminStatus.value)
        assertTrue(status.accepted)
        assertTrue(status.message.contains("Foxtail"))
    }

    @Test
    fun `a forget-bond rejection reaches the UI state`() = runTest {
        val transport = FakeBleTransport().apply { ackResults.addLast(0x04) } // OUT_OF_RANGE
        val viewModel = connectedViewModel(transport)

        viewModel.forgetBond(3)
        advanceUntilIdle()

        val status = requireNotNull(viewModel.adminStatus.value)
        assertFalse(status.accepted)
        assertTrue(status.message.contains("Forget bond 3"))
        assertTrue(status.message.contains("Index out of range"))
    }

    @Test
    fun `an unanswered command is worded as unanswered, not as refused`() = runTest {
        // A dropped notification is not a rejection: the command may well have
        // been applied, and saying "refused" would send the user to redo work
        // the device already did.
        val transport = FakeBleTransport().apply { autoAck = false }
        val viewModel = connectedViewModel(transport)

        viewModel.forgetAllBonds()
        advanceUntilIdle()

        val status = requireNotNull(viewModel.adminStatus.value)
        assertFalse(status.accepted)
        assertTrue(status.message.contains("not acknowledged"))
    }

    @Test
    fun `the status clears once the UI has shown it`() = runTest {
        val transport = FakeBleTransport()
        val viewModel = connectedViewModel(transport)

        viewModel.renameDevice("")
        advanceUntilIdle()
        assertEquals(false, viewModel.adminStatus.value?.accepted)

        viewModel.clearAdminStatus()
        assertNull(viewModel.adminStatus.value)
    }

    /**
     * The reason the screen must not observe `lastCommandResult`: the same
     * command rejected twice is the same sticky value twice, so the second
     * rejection would show nothing and the user would take silence for success.
     */
    @Test
    fun `two identical rejections are two failures, not one`() = runTest {
        val transport = FakeBleTransport()
        val repository = connectedRepository(transport)
        val viewModel = DeviceOverviewViewModel(repository)

        val seen = mutableListOf<CommandResult>()
        requireNotNull(repositoryScope).launch { viewModel.commandFailures.collect { seen.add(it) } }
        advanceUntilIdle()

        repeat(2) {
            transport.notify(
                CharacteristicUuids.CMD_RESULT,
                FirmwarePayloads.commandResult(0x03, 0x01, 0x03) // FF03 set-layer, UNKNOWN_ID
            )
            advanceUntilIdle()
        }

        assertEquals(2, seen.size)
        // The sticky state cannot tell them apart — that is the whole point.
        assertEquals(seen[0], repository.deviceState.value.lastCommandResult)
    }

    @Test
    fun `an accepted command is not reported as a failure`() = runTest {
        val transport = FakeBleTransport()
        val repository = connectedRepository(transport)
        val viewModel = DeviceOverviewViewModel(repository)

        val seen = mutableListOf<CommandResult>()
        requireNotNull(repositoryScope).launch { viewModel.commandFailures.collect { seen.add(it) } }
        advanceUntilIdle()

        transport.notify(
            CharacteristicUuids.CMD_RESULT,
            FirmwarePayloads.commandResult(0x03, 0x01, 0x00) // OK
        )
        advanceUntilIdle()

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `connect targets the address the route named, not whatever was last connected`() = runTest {
        val transport = FakeBleTransport()
        val repository = connectedRepository(transport)
        val viewModel = DeviceOverviewViewModel(repository, routeAddress = "AA:BB:CC:DD:EE:FF")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals("AA:BB:CC:DD:EE:FF", transport.connectedAddress)
    }

    @Test
    fun `isConnected follows the transport`() = runTest {
        val transport = FakeBleTransport()
        val viewModel = connectedViewModel(transport)
        advanceUntilIdle()
        assertTrue(viewModel.isConnected.value)

        transport.setConnectionState(ConnectionState.DISCONNECTED)
        advanceUntilIdle()
        assertFalse(viewModel.isConnected.value)
    }
}
