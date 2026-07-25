package com.tailapp.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte-for-byte checks of every command this app can send, against the layouts
 * in TailFirmware `docs/ble-protocol.md` / `main/ble/ble_protocol.h`.
 */
class CommandBuilderTest {

    private fun ByteArray.f32At(offset: Int): Float =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    private fun ByteArray.u16At(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun ByteArray.i32At(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    // ── FF01 motion ────────────────────────────────────────────────

    @Test
    fun `selectPattern is two bytes`() {
        assertArrayEquals(byteArrayOf(0x01, 0x02), MotionCommands.selectPattern(0x02))
    }

    @Test
    fun `setPatternParam is six bytes little-endian`() {
        val cmd = MotionCommands.setPatternParam(paramId = 0, value = 2.0f)
        assertEquals(6, cmd.size)
        assertEquals(0x02.toByte(), cmd[0])
        assertEquals(0x00.toByte(), cmd[1])
        assertEquals(2.0f, cmd.f32At(2), 0f)
        // Doc example: 2.0f encodes as 00 00 00 40
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x40), cmd.copyOfRange(2, 6))
    }

    @Test
    fun `setServoConfig omits mux channel when not supplied`() {
        val cmd = MotionCommands.setServoConfig(servoId = 0, axis = 0, half = 0, invert = 0)
        assertArrayEquals(byteArrayOf(0x03, 0x00, 0x00, 0x00, 0x00), cmd)
    }

    @Test
    fun `setServoConfig appends mux channel as optional sixth byte`() {
        val cmd = MotionCommands.setServoConfig(servoId = 1, axis = 1, half = 0, invert = 1, muxChannel = 2)
        assertArrayEquals(byteArrayOf(0x03, 0x01, 0x01, 0x00, 0x01, 0x02), cmd)
    }

    @Test
    fun `calibrateZero has no payload`() {
        assertArrayEquals(byteArrayOf(0x05), MotionCommands.calibrateZero())
    }

    @Test
    fun `setAxisLimits is ten bytes`() {
        val cmd = MotionCommands.setAxisLimits(axis = 1, min = -90f, max = 90f)
        assertEquals(10, cmd.size)
        assertEquals(0x06.toByte(), cmd[0])
        assertEquals(1.toByte(), cmd[1])
        assertEquals(-90f, cmd.f32At(2), 0f)
        assertEquals(90f, cmd.f32At(6), 0f)
    }

    @Test
    fun `setImuTap is three bytes`() {
        assertArrayEquals(byteArrayOf(0x07, 0x01, 0x01), MotionCommands.setImuTap(1, true))
        assertArrayEquals(byteArrayOf(0x07, 0x00, 0x00), MotionCommands.setImuTap(0, false))
    }

    @Test
    fun `setMotionLimits packs three floats and a stall threshold`() {
        val cmd = MotionCommands.setMotionLimits(
            servoId = 2,
            maxVelocity = 360f,
            maxAcceleration = 1800f,
            maxJerk = 18000f,
            stallThreshold = 60
        )

        assertEquals(15, cmd.size)
        assertEquals(0x08.toByte(), cmd[0])
        assertEquals(0x02.toByte(), cmd[1])
        assertEquals(360f, cmd.f32At(2), 0f)
        assertEquals(1800f, cmd.f32At(6), 0f)
        assertEquals(18000f, cmd.f32At(10), 0f)
        assertEquals(60.toByte(), cmd[14])
    }

    @Test
    fun `enableMotors is two bytes`() {
        assertArrayEquals(byteArrayOf(0x09, 0x01), MotionCommands.enableMotors(true))
        assertArrayEquals(byteArrayOf(0x09, 0x00), MotionCommands.enableMotors(false))
    }

    @Test
    fun `beginSequence is eight bytes with slot, u16 length and u32 crc`() {
        val cmd = MotionCommands.beginSequence(slot = 3, totalLength = 1544, crc32 = 0x12345678)
        assertEquals(8, cmd.size)
        assertEquals(0x0C.toByte(), cmd[0])
        assertEquals(0x03.toByte(), cmd[1])
        assertEquals(1544, cmd.u16At(2))
        assertEquals(0x12345678, cmd.i32At(4))
    }

    @Test
    fun `beginSequence keeps the high bit of a large crc`() {
        val cmd = MotionCommands.beginSequence(slot = 0, totalLength = 20, crc32 = -1)
        assertEquals(-1, cmd.i32At(4))
    }

    @Test
    fun `uploadSequenceChunk prefixes a little-endian u16 offset`() {
        val payload = byteArrayOf(9, 8, 7)
        val cmd = MotionCommands.uploadSequenceChunk(offset = 1200, data = payload)
        assertEquals(0x0D.toByte(), cmd[0])
        assertEquals(1200, cmd.u16At(1))
        assertArrayEquals(payload, cmd.copyOfRange(3, cmd.size))
    }

    @Test
    fun `finalizeSequence and selectSequence are two bytes`() {
        assertArrayEquals(byteArrayOf(0x0E, 0x01), MotionCommands.finalizeSequence(1))
        assertArrayEquals(byteArrayOf(0x0F, 0x02), MotionCommands.selectSequence(2))
    }

    // ── FF03 LED ───────────────────────────────────────────────────

    @Test
    fun `setLayerEffect is four bytes`() {
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x05, 0x05), LedCommands.setLayerEffect(0, 5, 5))
    }

    @Test
    fun `setEffectParam is seven bytes`() {
        val cmd = LedCommands.setEffectParam(layer = 2, paramId = 4, value = 5.0f)
        assertEquals(7, cmd.size)
        assertArrayEquals(byteArrayOf(0x02, 0x02, 0x04), cmd.copyOfRange(0, 3))
        assertEquals(5.0f, cmd.f32At(3), 0f)
    }

    @Test
    fun `setLayerTransform is six bytes with mirror before flip in the payload`() {
        val cmd = LedCommands.setLayerTransform(1, flipX = true, flipY = false, mirrorX = false, mirrorY = true)
        assertArrayEquals(byteArrayOf(0x04, 0x01, 0x01, 0x00, 0x00, 0x01), cmd)
    }

    @Test
    fun `uploadImageChunk prefixes a little-endian u16 offset`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val cmd = LedCommands.uploadImageChunk(offset = 513, data = payload)
        assertEquals(0x05.toByte(), cmd[0])
        assertEquals(513, cmd.u16At(1))
        assertArrayEquals(payload, cmd.copyOfRange(3, cmd.size))
    }

    @Test
    fun `uploadImageChunk handles offsets above 32767 without sign extension`() {
        // The staging buffer is 3072 bytes today, but the field is a u16 — a
        // signed conversion here would corrupt the destination address.
        val cmd = LedCommands.uploadImageChunk(offset = 40000, data = byteArrayOf(7))
        assertEquals(40000, cmd.u16At(1))
    }

    @Test
    fun `finalizeImage is four bytes`() {
        assertArrayEquals(byteArrayOf(0x06, 32, 32, 1), LedCommands.finalizeImage(32, 32, 1))
    }

    @Test
    fun `setLayerEnabled is three bytes`() {
        assertArrayEquals(byteArrayOf(0x07, 0x03, 0x00), LedCommands.setLayerEnabled(3, false))
    }

    @Test
    fun `beginImage is seven bytes with u16 length and u32 crc`() {
        val cmd = LedCommands.beginImage(totalLength = 3072, crc32 = 0x12345678)
        assertEquals(7, cmd.size)
        assertEquals(0x08.toByte(), cmd[0])
        assertEquals(3072, cmd.u16At(1))
        assertEquals(0x12345678, cmd.i32At(3))
    }

    @Test
    fun `beginImage keeps the high bit of a large crc`() {
        val cmd = LedCommands.beginImage(totalLength = 12, crc32 = -1)
        assertEquals(-1, cmd.i32At(3))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            cmd.copyOfRange(3, 7)
        )
    }

    @Test
    fun `setDirectMode is two bytes`() {
        assertArrayEquals(byteArrayOf(0x09, 0x01), LedCommands.setDirectMode(true))
        assertArrayEquals(byteArrayOf(0x09, 0x00), LedCommands.setDirectMode(false))
    }

    // ── FF06 system ────────────────────────────────────────────────

    @Test
    fun `setLedMatrix matches the documented example`() {
        val cmd = SystemCommands.setLedMatrix(listOf(8, 10, 12, 10, 8).map { it.toByte() })
        assertArrayEquals(byteArrayOf(0x01, 0x05, 0x08, 0x0A, 0x0C, 0x0A, 0x08), cmd)
    }

    @Test
    fun `setLedMatrix clamps to the device ring limit`() {
        // Firmware clamps num_rings to MAX_LED_RINGS and then requires
        // len >= 2 + num_rings, so an over-long list would be rejected outright.
        val cmd = SystemCommands.setLedMatrix(List(30) { 4.toByte() }, maxRings = 20)
        assertEquals(20, cmd[1].toInt())
        assertEquals(22, cmd.size)
    }

    @Test
    fun `setDeviceName is the command byte then raw UTF-8`() {
        val cmd = SystemCommands.setDeviceName("Foxtail")
        assertArrayEquals(byteArrayOf(0x04) + "Foxtail".toByteArray(Charsets.UTF_8), cmd)
    }

    @Test
    fun `setDeviceName refuses what the device would refuse`() {
        // The firmware rejects both rather than repairing either: truncating
        // would advertise a name the user did not choose, and an empty name
        // advertises nothing findable at all. Truncating here would hide that.
        assertEquals(DeviceNameError.TOO_LONG, SystemCommands.deviceNameError("a".repeat(32)))
        assertEquals(DeviceNameError.EMPTY, SystemCommands.deviceNameError(""))
        assertNull(SystemCommands.deviceNameError("a".repeat(Protocol.MAX_DEVICE_NAME_LEN)))

        assertThrows(IllegalArgumentException::class.java) {
            SystemCommands.setDeviceName("a".repeat(Protocol.MAX_DEVICE_NAME_LEN + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { SystemCommands.setDeviceName("") }
    }

    @Test
    fun `the device name limit is counted in UTF-8 bytes not characters`() {
        // 8 emoji = 32 UTF-8 bytes, one past the 31-byte limit, while the string
        // is only 16 UTF-16 chars long.
        assertEquals(DeviceNameError.TOO_LONG, SystemCommands.deviceNameError("🦊".repeat(8)))
        assertNull(SystemCommands.deviceNameError("🦊".repeat(7)))
        assertEquals(29, SystemCommands.setDeviceName("🦊".repeat(7)).size)
    }

    @Test
    fun `bond commands are two bytes and all-bonds has its own index`() {
        assertArrayEquals(byteArrayOf(0x05, 0x02), SystemCommands.forgetBond(2))
        assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte()), SystemCommands.forgetAllBonds())
        assertEquals(Protocol.BOND_INDEX_ALL, SystemCommands.forgetAllBonds()[1])
        assertArrayEquals(byteArrayOf(0x06), SystemCommands.listBonds())
    }

    // ── FF08 profiles ──────────────────────────────────────────────

    @Test
    fun `profile commands are two bytes`() {
        assertArrayEquals(byteArrayOf(0x01, 0x02), ProfileCommands.saveProfile(2))
        assertArrayEquals(byteArrayOf(0x02, 0x03), ProfileCommands.loadProfile(3))
        assertArrayEquals(byteArrayOf(0x03), ProfileCommands.listProfiles())
        assertArrayEquals(byteArrayOf(0x04, 0x00), ProfileCommands.deleteProfile(0))
    }

    @Test
    fun `renameProfile matches the documented example`() {
        val cmd = ProfileCommands.renameProfile(0, "Wag+Rainbow")
        val expected = byteArrayOf(0x05, 0x00) + "Wag+Rainbow".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, cmd)
    }

    @Test
    fun `renameProfile truncates to the firmware name limit`() {
        val cmd = ProfileCommands.renameProfile(1, "a".repeat(40))
        assertEquals(2 + Protocol.MAX_PROFILE_NAME_LEN, cmd.size)
    }

    @Test
    fun `renameProfile never splits a multi-byte character`() {
        // 6 emoji = 24 UTF-8 bytes; the 16-byte cap must land on a code-point boundary.
        val cmd = ProfileCommands.renameProfile(0, "🦊".repeat(6))
        val name = cmd.copyOfRange(2, cmd.size)
        assertTrue(name.size <= Protocol.MAX_PROFILE_NAME_LEN)
        assertEquals(0, name.size % 4)
        val decoded = String(name, Charsets.UTF_8)
        assertEquals("🦊".repeat(name.size / 4), decoded)
    }

    @Test
    fun `renameProfile accepts an empty name`() {
        assertArrayEquals(byteArrayOf(0x05, 0x02), ProfileCommands.renameProfile(2, ""))
    }

    // ── FF05 FFT ───────────────────────────────────────────────────

    @Test
    fun `fft frame is loudness then bin count then bins`() {
        val bins = ByteArray(64) { it.toByte() }
        val frame = FftFrameBuilder.build(loudness = 200.toByte(), bins = bins)
        assertEquals(66, frame.size)
        assertEquals(200.toByte(), frame[0])
        assertEquals(64, frame[1].toInt())
        assertArrayEquals(bins, frame.copyOfRange(2, frame.size))
    }
}
