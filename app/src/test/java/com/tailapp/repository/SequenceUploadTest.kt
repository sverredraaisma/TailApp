package com.tailapp.repository

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CharacteristicUuids
import com.tailapp.ble.protocol.CommandResultCode
import com.tailapp.ble.protocol.Crc32
import com.tailapp.ble.protocol.KeyframeSequenceCodec
import com.tailapp.model.Keyframe
import com.tailapp.model.KeyframeSequence
import com.tailapp.model.TailPose
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FirmwarePayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The keyframe-sequence upload on FF01: BEGIN → chunks → FINALIZE, the same
 * shape the FF03 image upload proved out.
 *
 * The assertions are on the exact traffic, because the device's verification is
 * arithmetic on exactly these bytes — an offset that is one short reassembles
 * into a blob whose CRC does not match, and the only symptom is a `BAD_STATE`
 * at finalize.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SequenceUploadTest {

    private var repositoryScope: CoroutineScope? = null

    @After
    fun cancelRepositoryScope() {
        repositoryScope?.cancel()
        repositoryScope = null
    }

    /** Brings a repository to the fully-synced CONNECTED state. */
    private fun TestScope.connected(transport: FakeBleTransport): DeviceRepository {
        transport.readResponses[CharacteristicUuids.MOTION_STATE] = FirmwarePayloads.motionState()
        transport.readResponses[CharacteristicUuids.LED_STATE] = FirmwarePayloads.ledState()
        transport.readResponses[CharacteristicUuids.SYSTEM_CONFIG] = FirmwarePayloads.systemInfo()
        transport.readResponses[CharacteristicUuids.PROFILE_MGMT] =
            FirmwarePayloads.profileList(List(4) { false to null })
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        repositoryScope = scope
        val repository = DeviceRepository(transport, scope)
        advanceUntilIdle()
        transport.setConnectionState(ConnectionState.CONNECTED)
        advanceUntilIdle()
        transport.clearTraffic()
        return repository
    }

    /** Two keyframes; 8 + 2*12 = 32 bytes. */
    private val blob = KeyframeSequenceCodec.encode(
        KeyframeSequence(
            listOf(
                Keyframe(0, TailPose()),
                Keyframe(500, TailPose(baseX = 12.5f, baseY = -3.75f, tipX = 90f, tipY = -45f))
            )
        )
    )

    /** 8 + 40*12 = 488 bytes, so a 247-MTU chunking is more than one packet. */
    private val longBlob = KeyframeSequenceCodec.encode(
        KeyframeSequence(List(40) { Keyframe(it * 100, TailPose(baseX = it.toFloat())) })
    )

    private fun ByteArray.u16At(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun ByteArray.i32At(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun `uploadSequence sends BEGIN with slot, length and CRC, then chunks, then FINALIZE`() =
        runTest {
            val transport = FakeBleTransport()
            val repository = connected(transport)

            val progress = mutableListOf<Float>()
            val result = repository.uploadSequence(
                blob = blob, slot = 2, chunkSize = 20, onProgress = { progress.add(it) }
            )
            advanceUntilIdle()

            assertEquals(SequenceUploadResult.Success, result)
            val writes = transport.writesTo(CharacteristicUuids.MOTION_CMD)

            // BEGIN: [0x0C][slot][u16 len][u32 crc], and the CRC is the standard
            // one over the whole blob — the value Crc32Test pins the polynomial of.
            val begin = writes.first()
            assertEquals(8, begin.size)
            assertEquals(0x0C.toByte(), begin[0])
            assertEquals(2.toByte(), begin[1])
            assertEquals(32, begin.u16At(2))
            assertEquals(0x3C62A523, begin.i32At(4))
            assertEquals(Crc32.compute(blob), begin.i32At(4))

            // FINALIZE names the same slot BEGIN armed; the device refuses any other.
            assertArrayEquals(byteArrayOf(0x0E, 0x02), writes.last())

            // The chunks reassemble to exactly the blob, at the offsets claimed.
            val chunks = writes.subList(1, writes.size - 1)
            assertEquals(2, chunks.size)
            val reassembled = ByteArray(blob.size)
            chunks.forEach { chunk ->
                assertEquals(0x0D.toByte(), chunk[0])
                chunk.copyOfRange(3, chunk.size).copyInto(reassembled, chunk.u16At(1))
            }
            assertArrayEquals(blob, reassembled)
            assertEquals(listOf(20f / 32f, 1f), progress)
        }

    @Test
    fun `chunks fill an MTU-sized packet exactly, with the remainder last`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        // 247-byte MTU: 241 bytes of blob per packet, so 488 bytes is 241 + 241 + 6.
        val chunkSize = KeyframeSequenceCodec.chunkSizeForMtu(247)
        assertEquals(241, chunkSize)
        repository.uploadSequence(blob = longBlob, slot = 0, chunkSize = chunkSize)
        advanceUntilIdle()

        val writes = transport.writesTo(CharacteristicUuids.MOTION_CMD)
        val chunks = writes.subList(1, writes.size - 1)
        assertEquals(listOf(0, 241, 482), chunks.map { it.u16At(1) })
        assertEquals(listOf(241, 241, 6), chunks.map { it.size - 3 })
        // Each packet stays inside the ATT payload the MTU allows.
        assertTrue(chunks.all { it.size <= 247 - 3 })
    }

    @Test
    fun `a rejected FINALIZE is reported as the length or CRC mismatch it is`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        // BEGIN and both chunks are accepted; the device checks the bytes it
        // actually received only at finalize. That answer is the entire point of
        // the handshake, so it has to survive back to the caller.
        transport.ackResults.addAll(listOf(0x00, 0x00, 0x00, 0x05))

        val result = repository.uploadSequence(blob = blob, slot = 1, chunkSize = 20)
        advanceUntilIdle()

        assertEquals(
            SequenceUploadResult.Rejected(SequenceUploadStep.FINALIZE, CommandResultCode.BAD_STATE),
            result
        )
        assertTrue(result.message.contains("checksum"))
    }

    @Test
    fun `a rejected BEGIN aborts before any chunk is sent`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.ackResults.add(0x04) // OUT_OF_RANGE: slot or size

        val result = repository.uploadSequence(blob = blob, slot = 9, chunkSize = 20)
        advanceUntilIdle()

        assertEquals(
            SequenceUploadResult.Rejected(SequenceUploadStep.BEGIN, CommandResultCode.OUT_OF_RANGE),
            result
        )
        // Nothing is armed, so every chunk after this would be refused anyway.
        assertEquals(1, transport.writesTo(CharacteristicUuids.MOTION_CMD).size)
    }

    @Test
    fun `a chunk the device refuses stops the transfer there`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.ackResults.addAll(listOf(0x00, 0x00, 0x04))

        val result = repository.uploadSequence(blob = blob, slot = 0, chunkSize = 20)
        advanceUntilIdle()

        assertEquals(
            SequenceUploadResult.Rejected(SequenceUploadStep.CHUNK, CommandResultCode.OUT_OF_RANGE),
            result
        )
        // BEGIN + two chunks; no FINALIZE for a blob known to be incomplete.
        assertEquals(3, transport.writesTo(CharacteristicUuids.MOTION_CMD).size)
    }

    @Test
    fun `a write that never leaves the phone is not confused with a rejection`() = runTest {
        val transport = FakeBleTransport().apply { writeResult = false }
        val repository = connected(transport)

        val result = repository.uploadSequence(blob = blob, slot = 0, chunkSize = 20)
        advanceUntilIdle()

        assertEquals(SequenceUploadResult.NotWritten(SequenceUploadStep.BEGIN), result)
    }

    @Test
    fun `a silent device is reported as unanswered rather than accepted`() = runTest {
        val transport = FakeBleTransport().apply { autoAck = false }
        val repository = connected(transport)

        val result = repository.uploadSequence(blob = blob, slot = 0, chunkSize = 20)
        advanceUntilIdle()

        assertEquals(SequenceUploadResult.Unanswered(SequenceUploadStep.BEGIN), result)
    }

    @Test
    fun `selectSequence names the slot and waits for the verdict`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)

        val accepted = repository.selectSequence(3)
        advanceUntilIdle()

        assertArrayEquals(
            byteArrayOf(0x0F, 0x03),
            transport.writesTo(CharacteristicUuids.MOTION_CMD).single()
        )
        assertTrue(accepted.accepted)
    }

    @Test
    fun `selecting an empty slot comes back rejected, not merely written`() = runTest {
        val transport = FakeBleTransport()
        val repository = connected(transport)
        transport.ackResults.add(0x05) // BAD_STATE: the slot holds nothing playable

        val outcome = repository.selectSequence(1)
        advanceUntilIdle()

        assertTrue(outcome.written)
        assertTrue(outcome.rejected)
        assertEquals(CommandResultCode.BAD_STATE, outcome.result?.result)
    }
}
