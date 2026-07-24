package com.tailapp.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte-for-byte checks of the FF0A packet builder against TailFirmware
 * `docs/ble-protocol.md` ("FF0A: LED Direct Stream") and `led_matrix.cpp`.
 */
class DirectPixelFrameTest {

    private fun ByteArray.u16At(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    // ── build() byte layout ─────────────────────────────────────────

    @Test
    fun `build lays out start_index little-endian then rgb bytes`() {
        val rgb = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9) // 3 LEDs
        val frame = DirectPixelFrame.build(startIndex = 0x1234, rgb = rgb, offset = 0, ledCount = 3)

        assertEquals(2 + 9, frame.size)
        assertEquals(0x1234, frame.u16At(0))
        assertArrayEquals(rgb, frame.copyOfRange(2, frame.size))
    }

    @Test
    fun `build encodes start_index above 32767 without sign extension`() {
        // Same u16-not-i16 hazard as LCMD_UPLOAD_IMAGE_CHUNK's offset field.
        val frame = DirectPixelFrame.build(startIndex = 40000, rgb = ByteArray(3), offset = 0, ledCount = 1)
        assertEquals(40000, frame.u16At(0))
    }

    @Test
    fun `build reads the requested slice of the source buffer at a non-zero offset`() {
        val rgb = ByteArray(30) { it.toByte() }
        val frame = DirectPixelFrame.build(startIndex = 5, rgb = rgb, offset = 9, ledCount = 2)

        assertEquals(5, frame.u16At(0))
        assertArrayEquals(rgb.copyOfRange(9, 15), frame.copyOfRange(2, frame.size))
    }

    @Test
    fun `build with zero ledCount produces just the header`() {
        val frame = DirectPixelFrame.build(startIndex = 0, rgb = ByteArray(0), offset = 0, ledCount = 0)
        assertEquals(2, frame.size)
        assertEquals(0, frame.u16At(0))
    }

    // ── maxLedsPerPacket ─────────────────────────────────────────────

    @Test
    fun `maxLedsPerPacket at mtu 23`() {
        // (23 - 3 ATT header - 2 FF0A header) / 3 = 18 / 3 = 6
        assertEquals(6, DirectPixelFrame.maxLedsPerPacket(23))
    }

    @Test
    fun `maxLedsPerPacket at mtu 185`() {
        // (185 - 3 - 2) / 3 = 180 / 3 = 60
        assertEquals(60, DirectPixelFrame.maxLedsPerPacket(185))
    }

    @Test
    fun `maxLedsPerPacket at mtu 247 differs from the doc's MTU-3 over-3 approximation`() {
        // Exact: (247 - 3 - 2) / 3 = 242 / 3 = 80 (floor).
        // The doc's (MTU-3)/3 approximation gives (247-3)/3 = 81 -- one LED too
        // many, because it never subtracts the 2-byte start_index header.
        assertEquals(80, DirectPixelFrame.maxLedsPerPacket(247))
    }

    @Test
    fun `maxLedsPerPacket at mtu 517`() {
        // Exact: (517 - 3 - 2) / 3 = 512 / 3 = 170 (floor).
        // Doc approximation: (517-3)/3 = 171 -- again one LED too many.
        assertEquals(170, DirectPixelFrame.maxLedsPerPacket(517))
    }

    @Test
    fun `maxLedsPerPacket never drops below one even at a tiny mtu`() {
        assertEquals(1, DirectPixelFrame.maxLedsPerPacket(5))
        assertEquals(1, DirectPixelFrame.maxLedsPerPacket(0))
        assertTrue(DirectPixelFrame.maxLedsPerPacket(-100) >= 1)
    }

    // ── Validation / rejection ──────────────────────────────────────

    @Test
    fun `build rejects a negative startIndex`() {
        try {
            DirectPixelFrame.build(startIndex = -1, rgb = ByteArray(3), offset = 0, ledCount = 1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `build rejects a startIndex that does not fit the u16 wire field`() {
        // 65536 would wrap to 0 through toShort() and land the pixels on the wrong
        // LED, silently, since FF0A is unacknowledged. Reject it instead.
        try {
            DirectPixelFrame.build(startIndex = 0x10000, rgb = ByteArray(3), offset = 0, ledCount = 1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `build accepts the largest startIndex the u16 field can hold`() {
        val frame = DirectPixelFrame.build(startIndex = 0xFFFF, rgb = ByteArray(3), offset = 0, ledCount = 1)
        assertEquals(0xFFFF, frame.u16At(0))
    }

    @Test
    fun `build rejects an rgb range that runs off the end of the array`() {
        try {
            DirectPixelFrame.build(startIndex = 0, rgb = ByteArray(6), offset = 3, ledCount = 2)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected: offset 3 + 2*3 = 9 bytes, but the buffer is only 6 bytes
        }
    }

    @Test
    fun `build rejects a negative offset`() {
        try {
            DirectPixelFrame.build(startIndex = 0, rgb = ByteArray(6), offset = -1, ledCount = 1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `build rejects a ledCount that would exceed the packet budget`() {
        val tooMany = DirectPixelFrame.maxLedsPerPacket(Protocol.MAX_ATT_MTU) + 1
        try {
            DirectPixelFrame.build(
                startIndex = 0,
                rgb = ByteArray(tooMany * 3),
                offset = 0,
                ledCount = tooMany
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected: no single FF0A write can carry this many LEDs, even at
            // the largest MTU this app will ever negotiate.
        }
    }

    @Test
    fun `build accepts ledCount exactly at the packet budget`() {
        val exact = DirectPixelFrame.maxLedsPerPacket(Protocol.MAX_ATT_MTU)
        val frame = DirectPixelFrame.build(
            startIndex = 0,
            rgb = ByteArray(exact * 3),
            offset = 0,
            ledCount = exact
        )
        assertEquals(2 + exact * 3, frame.size)
    }
}
