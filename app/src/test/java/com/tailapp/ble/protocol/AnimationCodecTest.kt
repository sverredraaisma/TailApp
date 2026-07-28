package com.tailapp.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The LED-5 animation blob, against the file layout in TailFirmware
 * `main/ble/ble_protocol.h` that `AnimationClip::validate` enforces.
 *
 * What is uploaded is the whole stored file, header included, so these bytes are
 * what ends up in flash — a header field one byte out is an animation the device
 * refuses at finalize, after the whole transfer.
 */
class AnimationCodecTest {

    private fun frame(width: Int, height: Int, fill: Int) =
        ByteArray(width * height * 3) { fill.toByte() }

    @Test
    fun `the header is magic, version, geometry, duration, reserved and a frame crc`() {
        val frames = listOf(frame(4, 4, 0x11), frame(4, 4, 0x22))
        val blob = AnimationCodec.encode(4, 4, frames, frameDurationMs = 80)

        assertEquals(Protocol.ANIMATION_HEADER_SIZE + 4 * 4 * 3 * 2, blob.size)
        assertEquals(AnimationCodec.blobSize(4, 4, 2), blob.size)

        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Protocol.ANIMATION_MAGIC, buf.int)              // "TANM"
        assertEquals(Protocol.ANIMATION_FORMAT_VERSION, buf.get().toInt())
        assertEquals(4, buf.get().toInt())                           // width
        assertEquals(4, buf.get().toInt())                           // height
        assertEquals(2, buf.get().toInt())                           // frame count
        assertEquals(80, buf.short.toInt())                          // duration ms
        // Reserved: the device refuses a blob with bits set here.
        assertEquals(0, buf.short.toInt())

        // The header CRC covers the frame data only — it cannot cover itself.
        val pixels = blob.copyOfRange(Protocol.ANIMATION_HEADER_SIZE, blob.size)
        assertEquals(Crc32.compute(pixels), buf.int)
    }

    @Test
    fun `frames are concatenated in order, row-major`() {
        val blob = AnimationCodec.encode(
            2, 1, listOf(frame(2, 1, 0xAA), frame(2, 1, 0xBB)), frameDurationMs = 100
        )
        val pixels = blob.copyOfRange(Protocol.ANIMATION_HEADER_SIZE, blob.size)
        assertEquals(12, pixels.size)
        for (i in 0 until 6) assertEquals(0xAA.toByte(), pixels[i])
        for (i in 6 until 12) assertEquals(0xBB.toByte(), pixels[i])
    }

    @Test
    fun `blobSize is the arithmetic the device cross-checks BEGIN against`() {
        assertEquals(16 + 8 * 8 * 3, AnimationCodec.blobSize(8, 8, 1))
        assertEquals(16 + 32 * 32 * 3 * 5, AnimationCodec.blobSize(32, 32, 5))
        // 5 frames at 32x32 is the largest that fits the device's 16 KB staging
        // buffer, which is what the ceiling is sized around.
        assertTrue(AnimationCodec.blobSize(32, 32, 5) <= Protocol.ANIMATION_MAX_BYTES)
        assertTrue(AnimationCodec.blobSize(32, 32, 6) > Protocol.ANIMATION_MAX_BYTES)
    }

    @Test
    fun `a frame of the wrong size is refused before the transfer, not at finalize`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnimationCodec.encode(4, 4, listOf(frame(4, 4, 0), frame(2, 2, 0)), 100)
        }
    }

    @Test
    fun `geometry the device would reject never gets encoded`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnimationCodec.encode(0, 4, emptyList(), 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnimationCodec.encode(4, 4, emptyList(), 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnimationCodec.encode(33, 4, List(1) { frame(33, 4, 0) }, 100)
        }
        // Past the 16 KB the device stages the whole upload in.
        assertThrows(IllegalArgumentException::class.java) {
            AnimationCodec.encode(32, 32, List(6) { frame(32, 32, 0) }, 100)
        }
    }

    @Test
    fun `a zero frame duration is refused - the device requires at least one ms`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnimationCodec.encode(2, 2, listOf(frame(2, 2, 0)), frameDurationMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnimationCodec.encode(2, 2, listOf(frame(2, 2, 0)), frameDurationMs = 65536)
        }
    }

    @Test
    fun `chunk size leaves room for the command id and its u16 offset`() {
        // The ATT payload is mtu - 3, and the chunk command spends 3 more.
        assertEquals(241, AnimationCodec.chunkSizeForMtu(247))
        assertEquals(511, AnimationCodec.chunkSizeForMtu(517))
        // Before the connection reports an MTU, the default 23-byte one's payload
        // is the floor: slow, never wrong.
        assertEquals(17, AnimationCodec.chunkSizeForMtu(23))
    }
}
