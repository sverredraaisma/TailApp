package com.tailapp.ble.protocol

import com.tailapp.model.ParamDescriptorKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ── FF01 motion tuning (0x0A, 0x0B, 0x14, 0x15, 0x16) ──────────

    @Test
    fun `setMotorScale is command, servo and one float`() {
        // config_manager.cpp requires len >= 6: [cmd][servo_id][f32].
        val cmd = MotionCommands.setMotorScale(servoId = 2, unitsPerDegPerSec = 1.5f)
        assertEquals(6, cmd.size)
        assertEquals(0x0A.toByte(), cmd[0])
        assertEquals(0x02.toByte(), cmd[1])
        assertEquals(1.5f, cmd.f32At(2), 0f)
    }

    @Test
    fun `setMotorScale refuses what the device would reject, NaN included`() {
        // The firmware's `!(x >= min)` form rejects NaN too; a plain `x < min`
        // would wave it through into every velocity command.
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setMotorScale(0, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setMotorScale(0, Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setMotorScale(0, Protocol.MOTOR_SCALE_MAX + 1f)
        }
        assertNull(MotionCommands.motorScaleError(Protocol.MOTOR_SCALE_MIN))
        assertNull(MotionCommands.motorScaleError(Protocol.MOTOR_SCALE_MAX))
    }

    @Test
    fun `setGentleScale is command and one float`() {
        val cmd = MotionCommands.setGentleScale(0.5f)
        assertEquals(5, cmd.size)
        assertEquals(0x0B.toByte(), cmd[0])
        assertEquals(0.5f, cmd.f32At(1), 0f)
    }

    @Test
    fun `setGentleScale bounds match the firmware's, including the 1_0 ceiling`() {
        assertNull(MotionCommands.gentleScaleError(Protocol.GENTLE_SCALE_MIN))
        assertNull(MotionCommands.gentleScaleError(1.0f))
        // Above 1.0 would raise limits past what the app configured per motor,
        // which is not what "gentle" means.
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setGentleScale(1.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setGentleScale(0.04f)
        }
    }

    @Test
    fun `setTapConfig is six bytes with a little-endian quiet time`() {
        // [cmd][imu_id][threshold][sensitivity][quiet_time u16 LE], len >= 6.
        val cmd = MotionCommands.setTapConfig(
            imuId = 1, threshold = 40, sensitivity = 5, quietTimeMs = 300
        )
        assertEquals(6, cmd.size)
        assertEquals(0x14.toByte(), cmd[0])
        assertEquals(0x01.toByte(), cmd[1])
        assertEquals(40.toByte(), cmd[2])
        assertEquals(5.toByte(), cmd[3])
        assertEquals(300, cmd.u16At(4))
    }

    @Test
    fun `setTapConfig treats zero as keep-the-default, not as out of range`() {
        // 0 is the one value below the quiet-time floor that is not a mistake:
        // an app changing only the threshold leaves the other at 0.
        assertNull(MotionCommands.tapConfigError(0, 0, 0))
        val cmd = MotionCommands.setTapConfig(0, threshold = 0, sensitivity = 0, quietTimeMs = 0)
        assertEquals(0, cmd.u16At(4))

        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setTapConfig(0, 40, 5, Protocol.TAP_QUIET_TIME_MIN_MS - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setTapConfig(0, 40, 5, Protocol.TAP_QUIET_TIME_MAX_MS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setTapConfig(0, 40, Protocol.TAP_SENSITIVITY_MAX + 1, 200)
        }
    }

    @Test
    fun `setAxisMix is three floats then two flags`() {
        // [cmd][rotation f32][gain_x f32][gain_y f32][invert_x][invert_y], len >= 15.
        val cmd = MotionCommands.setAxisMix(
            rotationDeg = 45f, gainX = 1.5f, gainY = 0.5f, invertX = true, invertY = false
        )
        assertEquals(15, cmd.size)
        assertEquals(0x15.toByte(), cmd[0])
        assertEquals(45f, cmd.f32At(1), 0f)
        assertEquals(1.5f, cmd.f32At(5), 0f)
        assertEquals(0.5f, cmd.f32At(9), 0f)
        assertEquals(1.toByte(), cmd[13])
        assertEquals(0.toByte(), cmd[14])
    }

    @Test
    fun `setAxisMix rejects a gain of zero, which would make the mix non-invertible`() {
        // A zero gain collapses a logical axis onto nothing, and FF02 could then
        // no longer report a logical position at all — AxisMixer::is_valid.
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setAxisMix(0f, gainX = 0f, gainY = 1f, invertX = false, invertY = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setAxisMix(0f, gainX = 1f, gainY = 0f, invertX = false, invertY = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setAxisMix(181f, 1f, 1f, invertX = false, invertY = false)
        }
        // ±180° inclusive is accepted, as is a negative rotation.
        assertNull(MotionCommands.axisMixError(-180f, 1f, 1f))
        assertNull(MotionCommands.axisMixError(180f, 1f, 1f))
    }

    @Test
    fun `setEncoderConfig is a flag then two floats`() {
        // [cmd][enabled][rate f32][rehome f32], len >= 10.
        val cmd = MotionCommands.setEncoderConfig(
            enabled = true, correctionRate = 0.25f, rehomeThresholdDeg = 15f
        )
        assertEquals(10, cmd.size)
        assertEquals(0x16.toByte(), cmd[0])
        assertEquals(1.toByte(), cmd[1])
        assertEquals(0.25f, cmd.f32At(2), 0f)
        assertEquals(15f, cmd.f32At(6), 0f)
    }

    @Test
    fun `setEncoderConfig holds the firmware's rate and rehome bounds`() {
        assertNull(
            MotionCommands.encoderConfigError(Protocol.ENCODER_RATE_MAX, Protocol.ENCODER_REHOME_MAX_DEG)
        )
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setEncoderConfig(true, correctionRate = 1.5f, rehomeThresholdDeg = 15f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setEncoderConfig(true, correctionRate = 0.25f, rehomeThresholdDeg = 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionCommands.setEncoderConfig(true, correctionRate = Float.NaN, rehomeThresholdDeg = 15f)
        }
    }

    // ── FF03 frame rate and animation upload (0x0C-0x0F) ───────────

    @Test
    fun `setFrameRate is two bytes`() {
        assertArrayEquals(byteArrayOf(0x0C, 30), LedCommands.setFrameRate(30))
    }

    @Test
    fun `setFrameRate refuses out-of-range rather than clamping`() {
        // The device rejects with OUT_OF_RANGE rather than clamping precisely so
        // an app that asked for 120 fps is told it did not get it; clamping here
        // would recreate the confusion the firmware avoided.
        assertThrows(IllegalArgumentException::class.java) { LedCommands.setFrameRate(120) }
        assertThrows(IllegalArgumentException::class.java) { LedCommands.setFrameRate(4) }
        assertNull(LedCommands.frameRateError(Protocol.LED_FRAME_RATE_MIN))
        assertNull(LedCommands.frameRateError(Protocol.LED_FRAME_RATE_MAX))
    }

    @Test
    fun `beginAnimation carries slot, geometry, length and crc`() {
        // [cmd][slot][w][h][frames][total_len u16][crc32 u32], len >= 11.
        val length = AnimationCodec.blobSize(8, 8, 4)
        val cmd = LedCommands.beginAnimation(
            slot = 1, width = 8, height = 8, frames = 4,
            totalLength = length, crc32 = 0x12345678
        )
        assertEquals(11, cmd.size)
        assertEquals(0x0D.toByte(), cmd[0])
        assertEquals(1.toByte(), cmd[1])
        assertEquals(8.toByte(), cmd[2])
        assertEquals(8.toByte(), cmd[3])
        assertEquals(4.toByte(), cmd[4])
        assertEquals(length, cmd.u16At(5))
        assertEquals(0x12345678, cmd.i32At(7))
        // The device recomputes this; a header the app got wrong is refused at
        // BEGIN rather than after thirty packets.
        assertEquals(16 + 8 * 8 * 3 * 4, length)
    }

    @Test
    fun `beginAnimation refuses a length that disagrees with the geometry`() {
        assertThrows(IllegalArgumentException::class.java) {
            LedCommands.beginAnimation(0, 8, 8, 4, totalLength = 100, crc32 = 0)
        }
    }

    @Test
    fun `beginAnimation holds the device's geometry and staging limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            LedCommands.beginAnimation(0, 33, 8, 1, AnimationCodec.blobSize(33, 8, 1), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LedCommands.beginAnimation(0, 8, 8, 0, AnimationCodec.blobSize(8, 8, 0), 0)
        }
        // 6 frames of 32x32 is 18448 bytes — past the 16 KB the device can stage.
        assertNotNull(LedCommands.animationError(32, 32, 6))
        assertNull(LedCommands.animationError(32, 32, 5))
    }

    @Test
    fun `uploadAnimationChunk is command, u16 offset then payload`() {
        val data = ByteArray(16) { (it + 1).toByte() }
        val cmd = LedCommands.uploadAnimationChunk(offset = 512, data = data)
        assertEquals(19, cmd.size)
        assertEquals(0x0E.toByte(), cmd[0])
        assertEquals(512, cmd.u16At(1))
        assertArrayEquals(data, cmd.copyOfRange(3, cmd.size))
    }

    @Test
    fun `finalizeAnimation is command and slot`() {
        assertArrayEquals(byteArrayOf(0x0F, 0x02), LedCommands.finalizeAnimation(2))
    }

    // ── FF06 descriptor selection (0x07) ───────────────────────────

    @Test
    fun `selectDescriptors is command, kind and id`() {
        assertArrayEquals(
            byteArrayOf(0x07, 0x00, 0x03),
            SystemCommands.selectDescriptors(ParamDescriptorKind.PATTERN, 0x03)
        )
        assertArrayEquals(
            byteArrayOf(0x07, 0x01, 0x06),
            SystemCommands.selectDescriptors(ParamDescriptorKind.EFFECT, 0x06)
        )
    }

    @Test
    fun `selectDescriptors refuses the device's own nothing-selected sentinel`() {
        // PARAM_DESC_KIND_NONE is what the device reports before anything has
        // been selected, not something an app can ask for — the firmware answers
        // OUT_OF_RANGE.
        assertThrows(IllegalArgumentException::class.java) {
            SystemCommands.selectDescriptors(ParamDescriptorKind.NONE, 0x00)
        }
    }
}
