package com.tailapp.repository

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResult
import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.ble.protocol.CommandResultParser
import com.tailapp.model.LedEffect
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FirmwarePayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FF09 acknowledgement correlation (roadmap A5-3).
 *
 * The device answers commands in the order it processed them and stamps each
 * answer with a counter. That counter is the only thing that makes two identical
 * in-flight commands — two `SET_EFFECT_PARAM` writes from one slider drag —
 * distinguishable, and the only way a dropped notification can be told apart
 * from an answer that simply belongs to the next command. Everything here is
 * asserted against exact commands and exact result codes, because "an answer
 * arrived" was never the property in question; "the *right* answer arrived" is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandAckTrackerTest {

    private val ff03 = CharacteristicUuids.shortId(CharacteristicUuids.LED_CMD)
    private val ff01 = CharacteristicUuids.shortId(CharacteristicUuids.MOTION_CMD)

    /** Built through the firmware payload and the real parser, not by hand. */
    private fun ack(
        characteristic: Int,
        command: Int,
        result: Int = OK,
        sequence: Int? = 0
    ): CommandResult = requireNotNull(
        CommandResultParser.parse(
            FirmwarePayloads.commandResult(characteristic, command, result, sequence)
        )
    )

    private suspend fun CommandAckTracker.register(characteristic: Byte, command: Int) =
        send(characteristic, command.toByte()) { true }

    // ── Correlation ────────────────────────────────────────────────

    @Test
    fun `answers are credited to the commands that caused them, in order`() = runTest {
        val tracker = CommandAckTracker()
        val effect = tracker.register(ff03, 0x01)
        val pattern = tracker.register(ff01, 0x01)

        tracker.onResult(ack(0x03, 0x01, OK, sequence = 0))
        tracker.onResult(ack(0x01, 0x01, UNKNOWN_ID, sequence = 1))

        assertEquals(CommandResultCode.OK, effect.await(TIMEOUT)?.result)
        assertEquals(CommandResultCode.UNKNOWN_ID, pattern.await(TIMEOUT)?.result)
        assertEquals(0, tracker.outstanding())
    }

    @Test
    fun `two identical in-flight commands each get their own answer`() = runTest {
        // The case the sequence byte was added for: one slider drag, two
        // SET_EFFECT_PARAM writes, byte-for-byte indistinguishable on the wire.
        val tracker = CommandAckTracker()
        val first = tracker.register(ff03, 0x02)
        val second = tracker.register(ff03, 0x02)

        tracker.onResult(ack(0x03, 0x02, OK, sequence = 7))
        tracker.onResult(ack(0x03, 0x02, OUT_OF_RANGE, sequence = 8))

        assertEquals(CommandResultCode.OK, first.await(TIMEOUT)?.result)
        assertEquals(CommandResultCode.OUT_OF_RANGE, second.await(TIMEOUT)?.result)
    }

    @Test
    fun `a gap in the numbering means the next answer is not the oldest command's`() = runTest {
        // Three identical commands, the middle answer lost on the air. Without
        // the sequence byte the third answer would be handed to the second
        // command — and it happens to be the rejection, so the app would report
        // the wrong write as the one the device refused.
        val tracker = CommandAckTracker()
        val first = tracker.register(ff03, 0x02)
        val second = tracker.register(ff03, 0x02)
        val third = tracker.register(ff03, 0x02)

        tracker.onResult(ack(0x03, 0x02, OK, sequence = 0))
        tracker.onResult(ack(0x03, 0x02, BAD_STATE, sequence = 2))

        assertEquals(CommandResultCode.OK, first.await(TIMEOUT)?.result)
        assertNull("the lost answer must not be invented", second.await(TIMEOUT))
        assertEquals(CommandResultCode.BAD_STATE, third.await(TIMEOUT)?.result)
        assertEquals(0, tracker.outstanding())
    }

    @Test
    fun `the counter wrapping past 255 is not read as a gap`() = runTest {
        val tracker = CommandAckTracker()
        val first = tracker.register(ff03, 0x01)
        val second = tracker.register(ff03, 0x01)

        tracker.onResult(ack(0x03, 0x01, OK, sequence = 255))
        tracker.onResult(ack(0x03, 0x01, BAD_LENGTH, sequence = 0))

        assertEquals(CommandResultCode.OK, first.await(TIMEOUT)?.result)
        assertEquals(CommandResultCode.BAD_LENGTH, second.await(TIMEOUT)?.result)
    }

    @Test
    fun `a jump too large to be lost answers abandons nobody`() = runTest {
        // The firmware stamps acks from two counters: `g_ack_seq` for commands it
        // executed, and `bad_write_ack_seq` in ble_service.c for a write rejected
        // before dispatch. One ack from the second source lands here as an
        // arbitrary jump — and reading 199 phantom losses out of it would abandon
        // a command the device is about to answer perfectly normally.
        val tracker = CommandAckTracker()
        val first = tracker.register(ff03, 0x01)
        val second = tracker.register(ff03, 0x01)

        tracker.onResult(ack(0x03, 0x01, OK, sequence = 0))
        assertEquals(CommandResultCode.OK, first.await(TIMEOUT)?.result)

        // The baseline still moves; what does not happen is a completion with null.
        assertTrue(tracker.onResult(ack(0x03, 0x01, BAD_STATE, sequence = 200)))
        assertEquals(CommandResultCode.BAD_STATE, second.await(TIMEOUT)?.result)
        assertEquals(0, tracker.outstanding())
    }

    @Test
    fun `a plausible gap is still read as lost answers`() = runTest {
        // The device's command queue is eight deep, so a burst can lose at most
        // that many at once — inside the bound the sequence byte does its job.
        val tracker = CommandAckTracker()
        val waiters = List(3) { tracker.register(ff03, 0x01) }

        tracker.onResult(ack(0x03, 0x01, OK, sequence = 4))
        tracker.onResult(ack(0x03, 0x01, BAD_STATE, sequence = 6))

        assertEquals(CommandResultCode.OK, waiters[0].await(TIMEOUT)?.result)
        assertNull("the lost answer must not be invented", waiters[1].await(TIMEOUT))
        assertEquals(CommandResultCode.BAD_STATE, waiters[2].await(TIMEOUT)?.result)
        assertEquals(0, tracker.outstanding())
    }

    @Test
    fun `firmware without a sequence byte still matches in FIFO order`() = runTest {
        // Pre-v5 devices send three bytes. Matching still works; what is given up
        // is the loss detection above, since there is nothing left to count.
        val tracker = CommandAckTracker()
        val first = tracker.register(ff03, 0x01)
        val second = tracker.register(ff03, 0x01)

        tracker.onResult(ack(0x03, 0x01, OK, sequence = null))
        tracker.onResult(ack(0x03, 0x01, UNKNOWN_ID, sequence = null))

        assertEquals(CommandResultCode.OK, first.await(TIMEOUT)?.result)
        assertEquals(CommandResultCode.UNKNOWN_ID, second.await(TIMEOUT)?.result)
    }

    @Test
    fun `an answer for an unregistered command consumes nothing but still counts`() = runTest {
        val tracker = CommandAckTracker()
        val pending = tracker.register(ff03, 0x01)

        assertFalse(tracker.onResult(ack(0x01, 0x05, OK, sequence = 4)))
        assertEquals(1, tracker.outstanding())

        // The stray answer still advanced the counter, so the real one at 5 is
        // not read as a gap and lands on the command that is waiting.
        assertTrue(tracker.onResult(ack(0x03, 0x01, BAD_STATE, sequence = 5)))
        assertEquals(CommandResultCode.BAD_STATE, pending.await(TIMEOUT)?.result)
    }

    @Test
    fun `a write that never left the phone takes its registration back out`() = runTest {
        // Otherwise the phantom waiter would sit at the head of the queue and
        // take the *next* command's answer — with no gap to reveal it, because
        // the device never counted a command it never received.
        val tracker = CommandAckTracker()
        val failed = tracker.send(ff03, 0x01) { false }
        val real = tracker.register(ff03, 0x01)

        assertFalse(failed.written)
        assertNull(failed.await(TIMEOUT))
        assertEquals(1, tracker.outstanding())

        tracker.onResult(ack(0x03, 0x01, OK, sequence = 0))
        assertEquals(CommandResultCode.OK, real.await(TIMEOUT)?.result)
    }

    @Test
    fun `a disconnect releases every waiting caller`() = runTest {
        val tracker = CommandAckTracker()
        val first = tracker.register(ff03, 0x01)
        val second = tracker.register(ff01, 0x02)

        tracker.abandonAll()

        assertNull(first.await(TIMEOUT))
        assertNull(second.await(TIMEOUT))
        assertEquals(0, tracker.outstanding())
    }

    @Test
    fun `the counter baseline is re-established after a reconnect`() = runTest {
        // The device's counter keeps running across connections, so the first
        // answer of a new one has to set the baseline rather than be read as a
        // gap of however many commands happened while the app was away.
        val tracker = CommandAckTracker()
        tracker.register(ff03, 0x01)
        tracker.onResult(ack(0x03, 0x01, OK, sequence = 10))
        tracker.abandonAll()

        val afterReconnect = tracker.register(ff03, 0x01)
        tracker.onResult(ack(0x03, 0x01, OK, sequence = 200))

        assertEquals(CommandResultCode.OK, afterReconnect.await(TIMEOUT)?.result)
    }

    @Test
    fun `registrations for a device that never answers are bounded`() = runTest {
        val tracker = CommandAckTracker()
        val oldest = tracker.register(ff03, 0x01)
        repeat(CommandAckTracker.MAX_OUTSTANDING) { tracker.register(ff03, 0x01) }

        // Past the bound correlation is lost anyway; what must not happen is one
        // waiter leaking per command for the life of the connection.
        assertEquals(CommandAckTracker.MAX_OUTSTANDING, tracker.outstanding())
        assertNull(oldest.await(TIMEOUT))
    }

    // ── Through the repository ─────────────────────────────────────

    private var repositoryScope: CoroutineScope? = null

    @After
    fun cancelRepositoryScope() {
        repositoryScope?.cancel()
        repositoryScope = null
    }

    private fun TestScope.connected(transport: FakeBleTransport): DeviceRepository {
        transport.readResponses[CharacteristicUuids.MOTION_STATE] = FirmwarePayloads.motionState()
        transport.readResponses[CharacteristicUuids.LED_STATE] = FirmwarePayloads.ledState(
            layers = listOf(FirmwarePayloads.layer(LedEffect.RAINBOW.id))
        )
        transport.readResponses[CharacteristicUuids.SYSTEM_CONFIG] = FirmwarePayloads.systemInfo()
        transport.readResponses[CharacteristicUuids.PROFILE_MGMT] =
            FirmwarePayloads.profileList(List(4) { false to null })

        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        repositoryScope = scope
        val repository = DeviceRepository(transport, scope, POLICY)
        advanceUntilIdle()
        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceUntilIdle()
        transport.clearTraffic()
        return repository
    }

    @Test
    fun `setDirectMode reports the device's verdict rather than the write's`() = runTest {
        val transport = FakeBleTransport().apply { ackResults.add(BAD_STATE) }
        val repository = connected(transport)

        val ok = repository.setDirectMode(true)
        advanceUntilIdle()

        // The write itself succeeded; the device refused the command.
        assertEquals(1, transport.writesTo(CharacteristicUuids.LED_CMD).size)
        assertFalse(ok)
        assertFalse(repository.deviceState.value.directModeActive)
        // The existing broadcast is untouched — screens still see every result.
        assertEquals(
            CommandResultCode.BAD_STATE,
            repository.deviceState.value.lastCommandResult?.result
        )
    }

    @Test
    fun `setDirectMode gives up after the policy's timeout when nothing answers`() = runTest {
        val transport = FakeBleTransport().apply { autoAck = false }
        val repository = connected(transport)
        val startedAt = currentTime

        val ok = repository.setDirectMode(true)
        advanceUntilIdle()

        assertFalse(ok)
        assertEquals(POLICY.timeoutMs, currentTime - startedAt)
        assertFalse(repository.deviceState.value.directModeActive)
    }

    @Test
    fun `a disconnect releases a caller waiting on an answer`() = runTest {
        val transport = FakeBleTransport().apply { autoAck = false }
        val repository = connected(transport)

        var ok: Boolean? = null
        launch { ok = repository.setDirectMode(true) }
        advanceTimeBy(5)
        assertNull("still waiting for the device", ok)

        transport.setConnectionState(ConnectionState.DISCONNECTED)
        advanceTimeBy(5)

        // Released well inside the timeout: a device that is gone answers nothing.
        assertEquals(false, ok)
        assertTrue(currentTime < POLICY.timeoutMs)
    }

    @Test
    fun `a busy device is retried and the next attempt sticks`() = runTest {
        val transport = FakeBleTransport().apply { ackResults.add(BUSY) }
        val repository = connected(transport)

        val ok = repository.setDirectMode(true)
        advanceUntilIdle()

        // BUSY means the device's queue was full, not that the command was wrong.
        assertTrue(ok)
        assertEquals(2, transport.writesTo(CharacteristicUuids.LED_CMD).size)
        assertTrue(repository.deviceState.value.directModeActive)
    }

    @Test
    fun `a device that stays busy is retried exactly as many times as the policy says`() = runTest {
        val transport = FakeBleTransport().apply { repeat(6) { ackResults.add(BUSY) } }
        val repository = connected(transport)

        val ok = repository.setDirectMode(true)
        advanceUntilIdle()

        assertFalse(ok)
        assertEquals(POLICY.maxAttempts, transport.writesTo(CharacteristicUuids.LED_CMD).size)
        assertFalse(repository.deviceState.value.directModeActive)
    }

    @Test
    fun `a dropped acknowledgement is not charged to the next command`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        // One acknowledged command first: a gap can only be seen from a baseline,
        // so the very first answer of a connection is the one loss nobody can spot.
        repository.setLayerEnabled(layer = 0, enabled = true)
        advanceUntilIdle()

        // Now lose one on the air. The device still counts it.
        transport.acksToDrop = 1
        repository.setEffectParam(layer = 0, paramId = 1, value = 5f)
        advanceUntilIdle()

        val startedAt = currentTime
        val ok = repository.setDirectMode(true)
        advanceUntilIdle()

        // The gap identified the dropped answer, so direct mode got its own
        // instead of timing out behind a command that will never be answered.
        assertTrue(ok)
        assertEquals(0, currentTime - startedAt)
        assertTrue(repository.deviceState.value.directModeActive)
    }

    @Test
    fun `uploadImage reports a FINALIZE the device refused`() = runTest {
        val transport = FakeBleTransport().apply {
            ackResults.addAll(listOf(OK, OK, BAD_STATE)) // BEGIN, one chunk, FINALIZE
        }
        val repository = connected(transport)

        val ok = repository.uploadImage(
            rgb = ByteArray(12) { it.toByte() }, width = 2, height = 2, layer = 0, chunkSize = 200
        )
        advanceUntilIdle()

        // A length or CRC mismatch is the entire point of the BEGIN/FINALIZE
        // handshake; before the ACK was read it was reported as a success.
        assertFalse(ok)
        assertEquals(3, transport.writesTo(CharacteristicUuids.LED_CMD).size)
    }

    @Test
    fun `uploadImage stops at a refused BEGIN instead of streaming chunks at it`() = runTest {
        val transport = FakeBleTransport().apply { ackResults.add(OUT_OF_RANGE) }
        val repository = connected(transport)

        val ok = repository.uploadImage(
            rgb = ByteArray(120), width = 8, height = 5, layer = 0, chunkSize = 20
        )
        advanceUntilIdle()

        assertFalse(ok)
        assertEquals(1, transport.writesTo(CharacteristicUuids.LED_CMD).size)
    }

    @Test
    fun `installLayerStack stops at the first write the device refuses`() = runTest {
        val transport = FakeBleTransport().apply {
            repeat(8) { ackResults.add(OK) } // the eight clear-slot writes
            ackResults.add(UNKNOWN_ID)       // the first SET_LAYER_EFFECT
        }
        val repository = connected(transport)

        val ok = repository.installLayerStack(
            listOf(FirmwarePayloads.layer(LedEffect.STATIC_COLOR.id))
        )
        advanceUntilIdle()

        // Half a stack installed silently is worse than a reported failure.
        assertFalse(ok)
        assertEquals(9, transport.writesTo(CharacteristicUuids.LED_CMD).size)
    }

    private companion object {
        const val OK = 0x00
        const val BAD_LENGTH = 0x01
        const val UNKNOWN_ID = 0x03
        const val OUT_OF_RANGE = 0x04
        const val BAD_STATE = 0x05
        const val BUSY = 0x06

        /** Long enough that a correctly matched answer never waits on it. */
        const val TIMEOUT = 1_000L

        val POLICY = AckRetryPolicy(timeoutMs = 200L, maxAttempts = 3, retryDelayMs = 10L)
    }
}
