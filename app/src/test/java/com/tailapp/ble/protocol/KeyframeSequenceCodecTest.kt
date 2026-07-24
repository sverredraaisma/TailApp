package com.tailapp.ble.protocol

import com.tailapp.model.Keyframe
import com.tailapp.model.KeyframeSequence
import com.tailapp.model.TailPose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Byte-for-byte checks of the keyframe blob against the layout in TailFirmware
 * `main/ble/ble_protocol.h`, as parsed by `main/motion/keyframe_sequence.cpp`.
 *
 * The expected arrays are written out by hand from that struct rather than
 * recorded from a run: the device checks the byte length against the keyframe
 * count exactly, so a record one byte wrong here is an upload that is refused
 * at finalize with nothing to point at.
 */
class KeyframeSequenceCodecTest {

    /**
     * Two keyframes, half a second apart. Every angle is exact in binary, so
     * nothing here depends on how a float rounds.
     */
    private val twoKeyframes = KeyframeSequence(
        keyframes = listOf(
            Keyframe(0, TailPose()),
            Keyframe(500, TailPose(baseX = 12.5f, baseY = -3.75f, tipX = 90f, tipY = -45f))
        ),
        loop = false
    )

    /** The same sequence, written out from the firmware's struct. */
    private val twoKeyframesBytes = byteArrayOf(
        // header: version, flags, u16 count, u32 reserved
        0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
        // keyframe 0: t = 0 ms, neutral
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        // keyframe 1: t = 500 ms (0x01F4)
        0xF4.toByte(), 0x01, 0x00, 0x00,
        // base X 12.5° = 1250 = 0x04E2, base Y -3.75° = -375 = 0xFE89
        0xE2.toByte(), 0x04, 0x89.toByte(), 0xFE.toByte(),
        // tip X 90° = 9000 = 0x2328, tip Y -45° = -4500 = 0xEE6C
        0x28, 0x23, 0x6C, 0xEE.toByte()
    )

    @Test
    fun `a two-keyframe sequence encodes to the firmware's exact bytes`() {
        assertArrayEquals(twoKeyframesBytes, KeyframeSequenceCodec.encode(twoKeyframes))
    }

    @Test
    fun `the loop flag is bit 0 of the header's flags byte`() {
        val looping = KeyframeSequenceCodec.encode(twoKeyframes.copy(loop = true))
        assertEquals(0x01.toByte(), looping[1])
        // Nothing else moves: the flag is a header bit, not a record.
        assertArrayEquals(
            twoKeyframesBytes.copyOfRange(2, twoKeyframesBytes.size),
            looping.copyOfRange(2, looping.size)
        )
    }

    @Test
    fun `the CRC-32 the upload arms is the standard one over the whole blob`() {
        // Standard CRC-32 (zlib) of the 32 bytes above. The firmware computes
        // the same polynomial as of protocol v3; a v2 device would disagree and
        // reject every finalize.
        assertEquals(0x3C62A523, Crc32.compute(KeyframeSequenceCodec.encode(twoKeyframes)))
    }

    @Test
    fun `angles are hundredths of a degree, rounded to the nearest`() {
        val sequence = KeyframeSequence(
            listOf(Keyframe(0, TailPose(baseX = 1.004f, baseY = 1.006f, tipX = -1.004f, tipY = -1.006f)))
        )
        val encoded = KeyframeSequenceCodec.encode(sequence)
        assertEquals(100, encoded.i16At(12))
        assertEquals(101, encoded.i16At(14))
        assertEquals(-100, encoded.i16At(16))
        assertEquals(-101, encoded.i16At(18))
    }

    @Test
    fun `the timestamp is a full little-endian u32, not the u16 the chunk offset is`() {
        val sequence = KeyframeSequence(
            listOf(Keyframe(0, TailPose()), Keyframe(100_000, TailPose()))
        )
        val encoded = KeyframeSequenceCodec.encode(sequence)
        // 100000 = 0x000186A0
        assertArrayEquals(
            byteArrayOf(0xA0.toByte(), 0x86.toByte(), 0x01, 0x00),
            encoded.copyOfRange(20, 24)
        )
    }

    @Test
    fun `a full sequence is exactly the device's staging buffer`() {
        val sequence = KeyframeSequence(
            List(Protocol.MAX_SEQUENCE_KEYFRAMES) { Keyframe(it * 100, TailPose()) }
        )
        val encoded = KeyframeSequenceCodec.encode(sequence)
        assertEquals(Protocol.SEQUENCE_MAX_BYTES, encoded.size)
        assertEquals(1544, encoded.size)
        assertEquals(Protocol.MAX_SEQUENCE_KEYFRAMES, encoded.u16At(2))
    }

    @Test
    fun `encoding refuses a sequence the device would refuse`() {
        // The alternative is spending the whole transfer to be told BAD_STATE.
        val backwards = KeyframeSequence(
            listOf(Keyframe(0, TailPose()), Keyframe(200, TailPose()), Keyframe(100, TailPose()))
        )
        assertThrows(IllegalArgumentException::class.java) {
            KeyframeSequenceCodec.encode(backwards)
        }
    }

    @Test
    fun `chunk size leaves room for the command id and the u16 offset`() {
        // ATT payload is MTU-3; MCMD_UPLOAD_SEQ_CHUNK spends 3 more on its
        // header, so a full-MTU packet carries MTU-6 bytes of blob.
        assertEquals(511, KeyframeSequenceCodec.chunkSizeForMtu(517))
        assertEquals(241, KeyframeSequenceCodec.chunkSizeForMtu(247))
        assertEquals(17, KeyframeSequenceCodec.chunkSizeForMtu(23))
        // Below the default MTU there is nothing sensible to compute; the floor
        // makes an upload slow rather than malformed.
        assertEquals(17, KeyframeSequenceCodec.chunkSizeForMtu(10))
    }

    private fun ByteArray.i16At(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() shl 8)).toShort().toInt()

    private fun ByteArray.u16At(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}
