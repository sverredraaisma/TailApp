package com.tailapp.viewmodel

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.model.Keyframe
import com.tailapp.model.SequenceProblem
import com.tailapp.model.TailPose
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The editor's own behaviour: the edits that keep a sequence uploadable, the
 * scrubbed preview, and what actually reaches the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyframeEditorViewModelTest {

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

    private suspend fun TestScope.newViewModel(
        transport: FakeBleTransport = FakeBleTransport()
    ): KeyframeEditorViewModel {
        val scope = CoroutineScope(dispatcher)
        repositoryScope = scope
        val repository = DeviceRepository(transport, scope)
        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceUntilIdle()
        transport.notify(CharacteristicUuids.MOTION_STATE, FirmwarePayloads.motionState())
        advanceUntilIdle()
        transport.clearTraffic()
        return KeyframeEditorViewModel(repository)
    }

    /** A three-second ramp: neutral at 0 s, 90° at 1 s. */
    private fun KeyframeEditorViewModel.buildRamp() {
        setPose(0, TailPose())
        scrubTo(0)
        addKeyframe()
        setKeyframeTime(1, 1000)
        setPose(1, TailPose(baseX = 90f))
    }

    private fun targetsOf(data: ByteArray): List<Float> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return List(4) { buf.getFloat() }
    }

    // ── Editing ────────────────────────────────────────────────────

    @Test
    fun `a new sequence is a single neutral keyframe the device would accept`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        assertEquals(listOf(Keyframe(0, TailPose())), viewModel.sequence.value.keyframes)
        assertTrue(viewModel.problems.value.isEmpty())
    }

    @Test
    fun `adding captures the previewed pose at the scrubbed time`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.setPose(0, TailPose(baseX = 10f))
        viewModel.scrubTo(0)
        viewModel.addKeyframe()
        viewModel.setKeyframeTime(1, 400)
        viewModel.setPose(1, TailPose(baseX = 30f))

        // Halfway between them the preview reads 20°, so a keyframe added there
        // holds 20° — the point of adding from the preview.
        viewModel.scrubTo(200)
        assertEquals(20f, viewModel.previewPose.value.baseX, 0f)
        viewModel.addKeyframe()

        assertEquals(listOf(0, 200, 400), viewModel.sequence.value.keyframes.map { it.timeMs })
        assertEquals(20f, viewModel.sequence.value.keyframes[1].pose.baseX, 0f)
        assertEquals(1, viewModel.selectedIndex.value)
    }

    @Test
    fun `adding onto an occupied instant appends past the end instead`() = runTest(dispatcher) {
        // Two keyframes at one time is a zero-length span the device refuses, so
        // the editor never creates one.
        val viewModel = newViewModel()
        viewModel.buildRamp()
        viewModel.scrubTo(1000)
        viewModel.addKeyframe()

        assertEquals(listOf(0, 1000, 1500), viewModel.sequence.value.keyframes.map { it.timeMs })
        assertTrue(viewModel.problems.value.isEmpty())
    }

    @Test
    fun `deleting the first keyframe shifts the rest back to zero`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.buildRamp()
        viewModel.scrubTo(1000)
        viewModel.addKeyframe() // 1500 ms

        viewModel.deleteKeyframe(0)

        // The device rejects a sequence whose first keyframe is not at zero;
        // rebasing keeps the edit from producing one.
        assertEquals(listOf(0, 500), viewModel.sequence.value.keyframes.map { it.timeMs })
        assertTrue(viewModel.problems.value.isEmpty())
    }

    @Test
    fun `the last keyframe cannot be deleted`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.deleteKeyframe(0)

        assertEquals(1, viewModel.sequence.value.keyframes.size)
        assertEquals("A sequence needs at least one keyframe.", viewModel.status.value)
    }

    @Test
    fun `moving a keyframe swaps poses and leaves the time grid alone`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.buildRamp()

        viewModel.moveKeyframe(1, -1)

        assertEquals(listOf(0, 1000), viewModel.sequence.value.keyframes.map { it.timeMs })
        assertEquals(90f, viewModel.sequence.value.keyframes[0].pose.baseX, 0f)
        assertEquals(0f, viewModel.sequence.value.keyframes[1].pose.baseX, 0f)
        assertEquals(0, viewModel.selectedIndex.value)
    }

    @Test
    fun `retiming re-sorts and the selection follows the keyframe`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.buildRamp()
        viewModel.scrubTo(1000)
        viewModel.addKeyframe()
        viewModel.setPose(2, TailPose(baseX = 45f))

        // Drag the last one in front of the middle one.
        viewModel.setKeyframeTime(2, 200)

        assertEquals(listOf(0, 200, 1000), viewModel.sequence.value.keyframes.map { it.timeMs })
        assertEquals(45f, viewModel.sequence.value.keyframes[1].pose.baseX, 0f)
        assertEquals(1, viewModel.selectedIndex.value)
    }

    @Test
    fun `the first keyframe stays at zero`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.buildRamp()

        viewModel.setKeyframeTime(0, 300)

        assertEquals(0, viewModel.sequence.value.keyframes.first().timeMs)
        assertEquals("The first keyframe stays at 0 s.", viewModel.status.value)
    }

    // ── Preview ────────────────────────────────────────────────────

    @Test
    fun `the preview interpolates the way the device does`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.buildRamp()

        viewModel.scrubTo(250)
        assertEquals(22.5f, viewModel.previewPose.value.baseX, 0f)
        viewModel.scrubTo(1000)
        assertEquals(90f, viewModel.previewPose.value.baseX, 0f)
    }

    @Test
    fun `playing advances the preview clock and stops at the end`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.buildRamp()
        viewModel.setKeyframeTime(1, 200)

        viewModel.scrubTo(0)
        viewModel.togglePlay()
        advanceTimeBy(101)
        // Five 20 ms steps of the device's own motion tick.
        assertEquals(100, viewModel.previewTimeMs.value)
        assertTrue(viewModel.playing.value)

        advanceTimeBy(300)
        assertEquals(200, viewModel.previewTimeMs.value)
        assertFalse(viewModel.playing.value)
    }

    @Test
    fun `a looping sequence wraps rather than stopping`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.buildRamp()
        viewModel.setKeyframeTime(1, 200)
        viewModel.setLoop(true)

        viewModel.scrubTo(0)
        viewModel.togglePlay()
        advanceTimeBy(261)

        // Thirteen steps: ten reach the 200 ms end and wrap to zero, three more
        // run from there.
        assertTrue(viewModel.playing.value)
        assertEquals(60, viewModel.previewTimeMs.value)
        viewModel.stopPlayback()
    }

    @Test
    fun `driving the tail streams the previewed pose and keeps it alive`() = runTest(dispatcher) {
        val transport = FakeBleTransport()
        val viewModel = newViewModel(transport)
        viewModel.buildRamp()
        viewModel.scrubTo(500)

        viewModel.setDriveTail(true)
        advanceTimeBy(501)

        // FF0B targets age out after 500 ms, so one write would be a twitch.
        assertTrue(transport.writesWithoutResponse.size >= 5)
        assertEquals(
            CharacteristicUuids.MOTION_TARGET,
            transport.writesWithoutResponse.first().uuid
        )
        // Both segments' X, then both segments' Y — the pose at 0.5 s of the ramp.
        assertEquals(listOf(45f, 0f, 0f, 0f), targetsOf(transport.writesWithoutResponse.first().data))
        viewModel.setDriveTail(false)
    }

    @Test
    fun `switching off the drive stops the stream`() = runTest(dispatcher) {
        val transport = FakeBleTransport()
        val viewModel = newViewModel(transport)
        viewModel.setDriveTail(true)
        advanceTimeBy(250)
        val sentWhileOn = transport.writesWithoutResponse.size
        viewModel.setDriveTail(false)
        advanceTimeBy(1000)

        assertEquals(sentWhileOn, transport.writesWithoutResponse.size)
    }

    // ── Upload ─────────────────────────────────────────────────────

    @Test
    fun `upload sends the whole handshake to the chosen slot`() = runTest(dispatcher) {
        val transport = FakeBleTransport()
        val viewModel = newViewModel(transport)
        viewModel.buildRamp()
        viewModel.selectSlot(2)

        viewModel.upload()
        advanceUntilIdle()

        val writes = transport.writesTo(CharacteristicUuids.MOTION_CMD)
        assertEquals(0x0C.toByte(), writes.first()[0])
        assertEquals(2.toByte(), writes.first()[1])
        // 8-byte header + two 12-byte records fit one packet at the negotiated MTU.
        assertEquals(3, writes.size)
        assertEquals(0x0D.toByte(), writes[1][0])
        assertArrayEquals(byteArrayOf(0x0E, 0x02), writes.last())
        assertEquals("Sequence stored on the tail.", viewModel.status.value)
        assertEquals(null, viewModel.uploadProgress.value)
    }

    @Test
    fun `upload refuses a sequence the device would refuse, and sends nothing`() =
        runTest(dispatcher) {
            val transport = FakeBleTransport()
            val viewModel = newViewModel(transport)
            // 400° would wrap the int16 to the opposite deflection.
            viewModel.setPose(0, TailPose(tipX = 400f))

            viewModel.upload()
            advanceUntilIdle()

            assertTrue(transport.writesTo(CharacteristicUuids.MOTION_CMD).isEmpty())
            assertEquals(
                SequenceProblem.AngleOutOfRange(0, 400f).message,
                viewModel.status.value
            )
        }

    @Test
    fun `a finalize rejection is reported rather than swallowed`() = runTest(dispatcher) {
        val transport = FakeBleTransport()
        val viewModel = newViewModel(transport)
        viewModel.buildRamp()
        // BEGIN and the single chunk are accepted; FINALIZE is not.
        transport.ackResults.addAll(listOf(0x00, 0x00, 0x05))

        viewModel.upload()
        advanceUntilIdle()

        assertTrue(viewModel.status.value?.contains("checksum") == true)
    }

    @Test
    fun `play on tail selects the slot before switching pattern`() = runTest(dispatcher) {
        val transport = FakeBleTransport()
        val viewModel = newViewModel(transport)
        viewModel.selectSlot(1)

        viewModel.playOnTail()
        advanceUntilIdle()

        // Selecting the pattern first would play whatever slot was chosen before.
        val writes = transport.writesTo(CharacteristicUuids.MOTION_CMD)
        assertEquals(2, writes.size)
        assertArrayEquals(byteArrayOf(0x0F, 0x01), writes[0])
        assertArrayEquals(byteArrayOf(0x01, 0x0A), writes[1])
    }

    @Test
    fun `play on tail says so when the slot is empty`() = runTest(dispatcher) {
        val transport = FakeBleTransport()
        val viewModel = newViewModel(transport)
        transport.ackResults.add(0x05) // BAD_STATE: nothing playable in the slot

        viewModel.playOnTail()
        advanceUntilIdle()

        // Only the select; the pattern is left alone rather than switched to one
        // that would hold neutral.
        assertEquals(1, transport.writesTo(CharacteristicUuids.MOTION_CMD).size)
        assertTrue(viewModel.status.value?.contains("no playable sequence") == true)
    }
}
