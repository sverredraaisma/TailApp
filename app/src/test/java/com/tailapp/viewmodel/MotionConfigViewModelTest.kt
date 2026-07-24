package com.tailapp.viewmodel

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FirmwarePayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The manual-drive pad (FF0B). Its whole job is turning a drag into motor
 * targets, so the assertions are on the exact floats that reach the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MotionConfigViewModelTest {

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

    /** The four LE floats of an FF0B frame, in wire order. */
    private fun targetsOf(data: ByteArray): List<Float> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return List(4) { buf.getFloat() }
    }

    private suspend fun TestScope.newViewModel(
        transport: FakeBleTransport,
        xLimits: Pair<Float, Float> = -90f to 90f,
        yLimits: Pair<Float, Float> = -45f to 45f
    ): MotionConfigViewModel {
        val scope = CoroutineScope(dispatcher)
        repositoryScope = scope
        val repository = DeviceRepository(transport, scope)
        // The repository only subscribes to notifications once connected, and
        // the fake's flow has no replay - emit before it is listening and the
        // state is simply lost.
        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceUntilIdle()
        transport.notify(
            CharacteristicUuids.MOTION_STATE,
            FirmwarePayloads.motionState(xLimits = xLimits, yLimits = yLimits)
        )
        advanceUntilIdle()
        return MotionConfigViewModel(repository)
    }

    @Test
    fun `a corner drag drives each axis to the end of its own configured travel`() =
        runTest(dispatcher) {
            val transport = FakeBleTransport()
            val viewModel = newViewModel(transport, xLimits = -90f to 90f, yLimits = -45f to 45f)

            viewModel.startPuppet(1f, -1f)
            advanceTimeBy(1)

            // Both halves of an axis get the same target: the pad steers the
            // tail as one piece, not each segment independently.
            assertEquals(
                listOf(90f, 90f, -45f, -45f),
                targetsOf(transport.writesWithoutResponse.first().data)
            )
            viewModel.stopPuppet()
        }

    @Test
    fun `centre maps to the midpoint of an asymmetric range, not to zero`() =
        runTest(dispatcher) {
            // A mechanism trimmed to 0..60 has no zero position, so a centred
            // pad has to mean 30 - the middle of what the tail can reach.
            val transport = FakeBleTransport()
            val viewModel = newViewModel(transport, xLimits = 0f to 60f, yLimits = -20f to 40f)

            viewModel.startPuppet(0f, 0f)
            advanceTimeBy(1)

            assertEquals(
                listOf(30f, 30f, 10f, 10f),
                targetsOf(transport.writesWithoutResponse.first().data)
            )
            viewModel.stopPuppet()
        }

    @Test
    fun `a held pose keeps re-sending faster than the device times it out`() =
        runTest(dispatcher) {
            val transport = FakeBleTransport()
            val viewModel = newViewModel(transport)

            viewModel.startPuppet(0.5f, 0f)
            advanceTimeBy(501)

            // The device drops streamed targets after 500 ms and hands the tail
            // back to its pattern. One write inside that window would be a
            // twitch; holding still has to keep the stream alive.
            assertTrue(
                "expected repeats within the device's 500 ms timeout, got " +
                    transport.writesWithoutResponse.size,
                transport.writesWithoutResponse.size >= 5
            )
            val distinct = transport.writesWithoutResponse.map { targetsOf(it.data) }.distinct()
            assertEquals(1, distinct.size)
            viewModel.stopPuppet()
        }

    @Test
    fun `releasing stops the stream rather than sending a centre position`() =
        runTest(dispatcher) {
            val transport = FakeBleTransport()
            val viewModel = newViewModel(transport)

            viewModel.startPuppet(0.5f, 0.5f)
            advanceTimeBy(250)
            viewModel.stopPuppet()
            val sentWhileHeld = transport.writesWithoutResponse.size
            advanceTimeBy(1000)

            // Letting go should return the tail to whatever its pattern was
            // doing, which is what the device's timeout does on its own. An
            // explicit "go to centre" would instead park it.
            assertEquals(sentWhileHeld, transport.writesWithoutResponse.size)
        }

    @Test
    fun `a new drag position replaces the previous one instead of racing it`() =
        runTest(dispatcher) {
            val transport = FakeBleTransport()
            val viewModel = newViewModel(transport)

            viewModel.startPuppet(-1f, 0f)
            advanceTimeBy(150)
            viewModel.startPuppet(1f, 0f)
            advanceTimeBy(150)
            viewModel.stopPuppet()

            // Two live loops would interleave two different poses and the tail
            // would judder between them.
            assertEquals(listOf(90f, 90f, 0f, 0f), targetsOf(transport.writesWithoutResponse.last().data))
            val tail = transport.writesWithoutResponse.takeLast(2).map { targetsOf(it.data) }.distinct()
            assertEquals(1, tail.size)
        }

    @Test
    fun `dragging before the device has reported its limits sends nothing`() =
        runTest(dispatcher) {
            // Without limits there is no scale to map the pad onto, and guessing
            // one risks commanding the tail past its stops.
            val transport = FakeBleTransport()
            val scope = CoroutineScope(dispatcher)
            repositoryScope = scope
            val viewModel = MotionConfigViewModel(DeviceRepository(transport, scope))

            viewModel.startPuppet(1f, 1f)
            advanceTimeBy(500)

            assertTrue(transport.writesWithoutResponse.isEmpty())
        }
}
