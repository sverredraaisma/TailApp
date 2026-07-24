package com.tailapp.viewmodel

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.model.BehaviorTable
import com.tailapp.model.BehaviorTriggerSource
import com.tailapp.model.MotionPattern
import com.tailapp.repository.BehaviorTableStore
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FakeSharedPreferences
import com.tailapp.testutil.FirmwarePayloads
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The behavior editor's traffic and the state it shows.
 *
 * The device answers every one of these writes on FF09 and publishes the
 * engine's live state on FF02, so both directions are assertable against
 * [FakeBleTransport] with no device and no radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BehaviorConfigViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private var repositoryScope: CoroutineScope? = null
    private val prefs = FakeSharedPreferences()

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

    private class Fixture(
        val transport: FakeBleTransport,
        val repository: DeviceRepository,
        val store: BehaviorTableStore,
        val viewModel: BehaviorConfigViewModel
    )

    private fun TestScope.connected(transport: FakeBleTransport = FakeBleTransport()): Fixture {
        transport.readResponses[CharacteristicUuids.MOTION_STATE] = FirmwarePayloads.motionState()
        val scope = CoroutineScope(dispatcher)
        repositoryScope = scope
        val repository = DeviceRepository(transport, scope)
        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceUntilIdle()
        transport.clearTraffic()
        val store = BehaviorTableStore(prefs)
        return Fixture(transport, repository, store, BehaviorConfigViewModel(repository, store))
    }

    private fun FakeBleTransport.motionWrites(): List<ByteArray> =
        writesTo(CharacteristicUuids.MOTION_CMD)

    @Test
    fun `editing a state writes that record and nothing else`() = runTest(dispatcher) {
        val f = connected()

        f.viewModel.editState(0) { it.copy(minDwellMs = 900) }
        advanceUntilIdle()

        // State 0 of the factory table is Idle: Idle Sway, no parameter
        // overrides, falling back to itself.
        val expected = byteArrayOf(
            0x12, 0x00,
            MotionPattern.IDLE_SWAY.id,
            0x01,                 // enabled
            0x00,                 // param_mask
            0x00,                 // fallback_state
            0x84.toByte(), 0x03,  // min_dwell_ms = 900
            0x00, 0x00            // timeout_ms
        ) + ByteArray(32)

        assertEquals(1, f.transport.motionWrites().size)
        assertArrayEquals(expected, f.transport.motionWrites().first())
        assertEquals(900, f.viewModel.table.value.states[0].minDwellMs)
    }

    @Test
    fun `editing a trigger writes that row`() = runTest(dispatcher) {
        val f = connected()

        f.viewModel.editTrigger(0) { it.copy(count = 2, windowMs = 1500) }
        advanceUntilIdle()

        // Row 0 is the tip grab: any state, straight to Startled.
        val expected = byteArrayOf(
            0x13, 0x00,
            0x02,                    // BEH_TRIG_TAP_TIP
            0x00,                    // from_mask = any
            0x05,                    // to_state = Startled
            0x02,                    // count
            0x00,                    // flags
            0x00, 0x00, 0x00,        // _reserved[3]
            0x00, 0x00, 0x00, 0x00,  // threshold
            0xDC.toByte(), 0x05,     // window_ms = 1500
            0x00, 0x00               // _reserved2
        )
        assertArrayEquals(expected, f.transport.motionWrites().single())
    }

    @Test
    fun `an edit the device would repair never leaves the phone`() = runTest(dispatcher) {
        val f = connected()

        f.viewModel.editState(0) { it.copy(fallbackState = 9) }
        advanceUntilIdle()

        assertTrue(f.transport.motionWrites().isEmpty())
        assertEquals("State 0: the fallback state must be 0-7", f.viewModel.message.value)
        // ...and the table is left as it was, rather than holding a record that
        // could never be sent.
        assertEquals(0, f.viewModel.table.value.states[0].fallbackState)
    }

    @Test
    fun `a threshold the device would clamp to zero is refused`() = runTest(dispatcher) {
        val f = connected()

        f.viewModel.editTrigger(5) { it.copy(threshold = -1f) }
        advanceUntilIdle()

        assertTrue(f.transport.motionWrites().isEmpty())
        assertEquals("Trigger 5: the threshold must be zero or more", f.viewModel.message.value)
    }

    @Test
    fun `a rejection on FF09 reaches both the screen and the device state`() =
        runTest(dispatcher) {
            val transport = FakeBleTransport()
            // RESULT_OUT_OF_RANGE, the answer to a state index the device does
            // not have.
            transport.ackResults.add(0x04)
            val f = connected(transport)

            f.viewModel.previewState(7)
            advanceUntilIdle()

            assertEquals("Forcing state 7: Index out of range", f.viewModel.message.value)
            assertEquals(
                CommandResultCode.OUT_OF_RANGE,
                f.repository.deviceState.value.lastCommandResult?.result
            )
        }

    @Test
    fun `a write nobody answers is reported as unanswered, not as accepted`() =
        runTest(dispatcher) {
            val transport = FakeBleTransport()
            transport.autoAck = false
            val f = connected(transport)

            f.viewModel.setEngineEnabled(true)
            advanceUntilIdle()

            assertEquals("The engine switch: the device did not answer", f.viewModel.message.value)
            // The switch reflects the device, so an unanswered write must not
            // leave the screen claiming the engine is on.
            assertTrue(!f.viewModel.table.value.engineEnabled)
        }

    @Test
    fun `previewing a state forces it`() = runTest(dispatcher) {
        val f = connected()

        f.viewModel.previewState(3)
        advanceUntilIdle()

        assertArrayEquals(byteArrayOf(0x11, 0x03), f.transport.motionWrites().single())
    }

    @Test
    fun `enabling the engine is remembered for the next time the screen opens`() =
        runTest(dispatcher) {
            val f = connected()

            f.viewModel.setEngineEnabled(true)
            advanceUntilIdle()

            assertArrayEquals(byteArrayOf(0x10, 0x01), f.transport.motionWrites().single())
            assertTrue(f.viewModel.table.value.engineEnabled)
            // The device publishes no read of its table, so the store is the
            // only thing that survives the screen closing.
            assertTrue(BehaviorTableStore(prefs).load().engineEnabled)
        }

    @Test
    fun `pushing the table installs every record, then the engine switch`() =
        runTest(dispatcher) {
            val f = connected()

            f.viewModel.pushTable()
            advanceUntilIdle()

            val writes = f.transport.motionWrites()
            val table = BehaviorTable.FIRMWARE_DEFAULT
            assertEquals(table.states.size + table.triggers.size + 1, writes.size)
            assertTrue(writes.take(table.states.size).all { it[0] == 0x12.toByte() })
            assertTrue(
                writes.drop(table.states.size).dropLast(1).all { it[0] == 0x13.toByte() }
            )
            assertArrayEquals(byteArrayOf(0x10, 0x00), writes.last())
            // Records are indexed by position, not by order of arrival.
            assertEquals(listOf<Byte>(0, 1, 2, 3, 4, 5), writes.take(6).map { it[1] })
        }

    @Test
    fun `a push stops at the first refusal instead of writing on regardless`() =
        runTest(dispatcher) {
            val transport = FakeBleTransport()
            transport.ackResults.add(0x00)
            transport.ackResults.add(0x05)  // RESULT_BAD_STATE on the second record
            val f = connected(transport)

            f.viewModel.pushTable()
            advanceUntilIdle()

            assertEquals(2, f.transport.motionWrites().size)
            assertTrue(requireNotNull(f.viewModel.message.value).startsWith("State 1:"))
        }

    @Test
    fun `resetting discards the stored table and reinstalls the factory one`() =
        runTest(dispatcher) {
            val f = connected()
            f.viewModel.editState(0) { it.copy(minDwellMs = 900) }
            advanceUntilIdle()
            f.transport.clearTraffic()

            f.viewModel.resetToFirmwareDefaults()
            advanceUntilIdle()

            assertEquals(BehaviorTable.FIRMWARE_DEFAULT, f.viewModel.table.value)
            assertEquals(BehaviorTable.FIRMWARE_DEFAULT, BehaviorTableStore(prefs).load())
            assertEquals(16, f.transport.motionWrites().size)
        }

    @Test
    fun `transitions are reconstructed from the FF02 block`() = runTest(dispatcher) {
        val f = connected()

        f.transport.notify(
            CharacteristicUuids.MOTION_STATE,
            FirmwarePayloads.motionState(
                behavior = FirmwarePayloads.BehaviorBlock(stateIndex = 0, reason = 0x83, flags = 0x01)
            )
        )
        advanceUntilIdle()
        f.transport.notify(
            CharacteristicUuids.MOTION_STATE,
            FirmwarePayloads.motionState(
                behavior = FirmwarePayloads.BehaviorBlock(
                    stateIndex = 3,
                    reason = BehaviorTriggerSource.LOUDNESS.code.toInt(),
                    flags = 0x01,
                    drivingPatternId = MotionPattern.EXCITED_WAG.id.toInt()
                )
            )
        )
        advanceUntilIdle()

        val transitions = f.viewModel.transitions.value
        assertEquals(1, transitions.size)
        assertEquals(0, transitions.first().fromState)
        assertEquals(3, transitions.first().toState)
        assertEquals("trigger: Loudness", transitions.first().reason.description)
    }

    @Test
    fun `the first block seen is a starting point, not a transition`() = runTest(dispatcher) {
        val f = connected()

        f.transport.notify(
            CharacteristicUuids.MOTION_STATE,
            FirmwarePayloads.motionState(
                behavior = FirmwarePayloads.BehaviorBlock(stateIndex = 2, reason = 0x83, flags = 0x01)
            )
        )
        advanceUntilIdle()

        assertTrue(f.viewModel.transitions.value.isEmpty())
        assertEquals(2, f.repository.deviceState.value.motionState?.behavior?.stateIndex)
    }

    @Test
    fun `firmware without the block leaves the live state unknown`() = runTest(dispatcher) {
        val f = connected()

        f.transport.notify(CharacteristicUuids.MOTION_STATE, FirmwarePayloads.motionState())
        advanceUntilIdle()

        assertNull(f.repository.deviceState.value.motionState?.behavior)
        assertTrue(f.viewModel.transitions.value.isEmpty())
    }
}
