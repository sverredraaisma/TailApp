package com.tailapp.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The device recomputes its checksum over the staged image and rejects a
 * mismatch, so the app must match `config_manager.cpp::crc32` byte for byte.
 *
 * As of protocol v3 that is standard CRC-32 (IEEE 802.3 / zlib, reflected
 * polynomial `0xEDB88320`). Firmware ≤ v2 used a non-standard `0xEDB88420`
 * variant; `rejectsTheLegacyV2Polynomial` keeps that from creeping back.
 */
class Crc32Test {

    /**
     * Reference implementation transcribed from the firmware, parameterised by
     * polynomial so both the v3 and the legacy v2 constant can be exercised.
     */
    private fun firmwareCrc32(data: ByteArray, polynomial: Long = STANDARD_POLYNOMIAL): Int {
        var crc = 0xFFFFFFFFL
        for (byte in data) {
            crc = crc xor (byte.toLong() and 0xFF)
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor polynomial else (crc ushr 1)
            }
        }
        return (crc.inv() and 0xFFFFFFFFL).toInt()
    }

    @Test
    fun `matches the documented check value`() {
        // ble-protocol.md pins crc32("123456789") == 0xCBF43926.
        assertEquals(0xCBF43926.toInt(), Crc32.compute("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `rejects the legacy v2 polynomial`() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertNotEquals(firmwareCrc32(data, LEGACY_V2_POLYNOMIAL), Crc32.compute(data))
    }

    @Test
    fun `matches the firmware reference for an empty buffer`() {
        assertEquals(firmwareCrc32(ByteArray(0)), Crc32.compute(ByteArray(0)))
        assertEquals(0, Crc32.compute(ByteArray(0)))
    }

    @Test
    fun `matches the firmware reference for a full image buffer`() {
        val image = ByteArray(Protocol.IMAGE_UPLOAD_MAX) { (it * 7 % 251).toByte() }
        assertEquals(firmwareCrc32(image), Crc32.compute(image))
    }

    @Test
    fun `matches the firmware reference for all-zero and all-ff buffers`() {
        val zeros = ByteArray(64)
        val ones = ByteArray(64) { 0xFF.toByte() }
        assertEquals(firmwareCrc32(zeros), Crc32.compute(zeros))
        assertEquals(firmwareCrc32(ones), Crc32.compute(ones))
    }

    @Test
    fun `matches the firmware reference for every single-byte value`() {
        // Catches sign-extension errors on bytes above 0x7F.
        for (value in 0..255) {
            val data = byteArrayOf(value.toByte())
            assertEquals("byte $value", firmwareCrc32(data), Crc32.compute(data))
        }
    }

    @Test
    fun `detects a single flipped bit`() {
        val original = ByteArray(300) { it.toByte() }
        val corrupted = original.copyOf().also { it[123] = (it[123].toInt() xor 0x01).toByte() }
        assertNotEquals(Crc32.compute(original), Crc32.compute(corrupted))
    }

    @Test
    fun `detects reordered chunks`() {
        val a = ByteArray(64) { it.toByte() }
        val b = ByteArray(64) { (it + 64).toByte() }
        assertNotEquals(Crc32.compute(a + b), Crc32.compute(b + a))
    }

    @Test
    fun `honours offset and length`() {
        val payload = "123456789".toByteArray(Charsets.US_ASCII)
        val padded = byteArrayOf(9, 9) + payload + byteArrayOf(9)
        assertEquals(Crc32.compute(payload), Crc32.compute(padded, offset = 2, length = 9))
    }

    @Test
    fun `rejects an out-of-bounds range`() {
        var threw = false
        try {
            Crc32.compute(ByteArray(4), offset = 2, length = 8)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertEquals(true, threw)
    }

    private companion object {
        const val STANDARD_POLYNOMIAL = 0xEDB88320L
        const val LEGACY_V2_POLYNOMIAL = 0xEDB88420L
    }
}
