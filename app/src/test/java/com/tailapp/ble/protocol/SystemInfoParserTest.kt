package com.tailapp.ble.protocol

import com.tailapp.model.BlendMode
import com.tailapp.model.Capabilities
import com.tailapp.model.LedEffect
import com.tailapp.model.MotionPattern
import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInfoParserTest {

    @Test
    fun `parses protocol version firmware version servos and imus`() {
        val info = SystemInfoParser.parse(FirmwarePayloads.systemInfo())
        assertNotNull(info)
        requireNotNull(info)

        assertEquals(Protocol.SUPPORTED_PROTOCOL_VERSION, info.protocolVersion)
        assertEquals("1.0.0", info.firmwareVersion)
        assertEquals(4, info.servos.size)
        assertEquals(2, info.imus.size)
    }

    @Test
    fun `servo fields land on the right offsets`() {
        // Regression: before the protocol_version byte was accounted for, every
        // field here was shifted by one and num_servos parsed as 0.
        val info = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo()))

        val second = info.servos[1]
        assertEquals(0, second.axis)
        assertEquals(1, second.half)
        assertTrue(second.invert)
        assertEquals(1, second.muxChannel)
        assertEquals(2.0f, second.pid.kp, 0f)
        assertEquals(0.1f, second.pid.ki, 1e-6f)
        assertEquals(0.5f, second.pid.kd, 0f)

        val fourth = info.servos[3]
        assertEquals(1, fourth.axis)
        assertEquals(1, fourth.half)
        assertEquals(3, fourth.muxChannel)
        assertEquals(4.0f, fourth.pid.kp, 0f)
    }

    @Test
    fun `imu fields land on the right offsets`() {
        val info = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo()))
        assertEquals(4, info.imus[0].muxChannel)
        assertTrue(info.imus[0].tapEnabled)
        assertEquals(5, info.imus[1].muxChannel)
        assertFalse(info.imus[1].tapEnabled)
    }

    @Test
    fun `parses the capability block`() {
        val info = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo()))
        val caps = requireNotNull(info.capabilities)

        assertEquals(MotionPattern.entries.map { it.id }, caps.patternIds)
        assertEquals(LedEffect.entries.map { it.id }, caps.effectIds)
        assertEquals(BlendMode.entries.map { it.id }, caps.blendModeIds)
        assertEquals(8, caps.maxLayers)
        assertEquals(4, caps.maxServos)
        assertEquals(2, caps.maxImus)
        assertEquals(20, caps.maxLedRings)
        assertEquals(32, caps.imageMaxDim)
    }

    @Test
    fun `payload size tracks the capability lists it carries`() {
        // The capability block is variable-length by construction - it lists
        // whatever ids this firmware supports - so pinning one number would
        // just have to be re-pinned every time an effect is added. Pin the
        // arithmetic instead.
        val caps = Capabilities.DEFAULT
        val capsBytes = 3 + caps.patternIds.size + caps.effectIds.size +
            caps.blendModeIds.size + 5
        val header = 5 + 4 * 16 + 1 + 2 * 2 // proto+fw+servos+imus
        val motion = 1 + 4 * 13

        assertEquals(header + capsBytes, FirmwarePayloads.systemInfo(motion = null).size)
        assertEquals(header + capsBytes + motion, FirmwarePayloads.systemInfo().size)
    }

    @Test
    fun `capabilities map back to app enums`() {
        val info = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo()))
        val caps = requireNotNull(info.capabilities)
        assertEquals(MotionPattern.entries.toList(), caps.patterns)
        assertEquals(LedEffect.entries.toList(), caps.effects)
        assertEquals(BlendMode.entries.toList(), caps.blendModes)
    }

    @Test
    fun `unknown capability ids are skipped rather than crashing`() {
        val caps = Capabilities.DEFAULT.copy(
            patternIds = listOf<Byte>(0x00, 0x01, 0x02, 0x7F),
            effectIds = listOf<Byte>(0x00, 0x63)
        )
        val info = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo(capabilities = caps)))
        val parsed = requireNotNull(info.capabilities)

        assertEquals(4, parsed.patternIds.size)
        assertEquals(3, parsed.patterns.size)
        assertEquals(listOf(LedEffect.RAINBOW), parsed.effects)
    }

    @Test
    fun `payload without a capability block still parses`() {
        val info = SystemInfoParser.parse(FirmwarePayloads.systemInfo(capabilities = null))
        assertNotNull(info)
        requireNotNull(info)
        assertEquals(4, info.servos.size)
        assertEquals(2, info.imus.size)
        assertNull(info.capabilities)
        // Falls back to the built-in defaults so the UI still has limits to use.
        assertEquals(Capabilities.DEFAULT, info.effectiveCapabilities)
    }

    @Test
    fun `payload without an imu block still parses`() {
        val info = SystemInfoParser.parse(FirmwarePayloads.systemInfo(imus = null))
        assertNotNull(info)
        assertEquals(4, requireNotNull(info).servos.size)
        assertTrue(info.imus.isEmpty())
    }

    @Test
    fun `truncated capability block is dropped instead of throwing`() {
        // Truncate a payload that ends at the capability block, so this cuts
        // into the capabilities rather than the motion block that follows them.
        val full = FirmwarePayloads.systemInfo(motion = null)
        val truncated = full.copyOfRange(0, full.size - 3)
        val info = SystemInfoParser.parse(truncated)
        assertNotNull(info)
        assertNull(requireNotNull(info).capabilities)
    }

    @Test
    fun `a dropped capability block takes the motion block with it`() {
        // The motion block sits after the capabilities and is only locatable by
        // walking them, so it must not be parsed from whatever bytes follow.
        val full = FirmwarePayloads.systemInfo(motion = null)
        val info = requireNotNull(SystemInfoParser.parse(full.copyOfRange(0, full.size - 3)))
        assertNull(info.capabilities)
        assertNull(info.motion)
    }

    @Test
    fun `payload shorter than the header is rejected`() {
        assertNull(SystemInfoParser.parse(ByteArray(4)))
        assertNull(SystemInfoParser.parse(ByteArray(0)))
    }

    @Test
    fun `payload claiming more servos than it carries is rejected`() {
        val data = byteArrayOf(1, 1, 0, 0, 4) + ByteArray(16) // says 4 servos, carries 1
        assertNull(SystemInfoParser.parse(data))
    }

    @Test
    fun `protocol version drives the compatibility flag`() {
        val current = requireNotNull(
            SystemInfoParser.parse(
                FirmwarePayloads.systemInfo(protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION)
            )
        )
        assertTrue(current.isProtocolSupported)

        // v2 devices compute the image CRC with a different polynomial, so an
        // older device is incompatible in a way the user has to be told about.
        val older = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo(protocolVersion = 2)))
        assertFalse(older.isProtocolSupported)
        assertEquals(2, older.protocolVersion)

        val future = requireNotNull(
            SystemInfoParser.parse(
                FirmwarePayloads.systemInfo(
                    protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION + 1
                )
            )
        )
        assertFalse(future.isProtocolSupported)
    }

    @Test
    fun `parses the motion block appended after the capabilities`() {
        val info = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo()))
        val motion = requireNotNull(info.motion)

        assertTrue(motion.motorsEnabled)
        assertEquals(4, motion.limits.size)
        assertEquals(720f, motion.limits[0].maxVelocity, 0f)
        assertEquals(3600f, motion.limits[0].maxAcceleration, 0f)
        assertEquals(36000f, motion.limits[0].maxJerk, 0f)
        assertEquals(0, motion.limits[0].stallThreshold)
        // Motors 2 and 3 carry a different, stall-armed profile, so this would
        // catch the whole block being parsed from one motor's bytes.
        assertEquals(360f, motion.limits[2].maxVelocity, 0f)
        assertEquals(60, motion.limits[2].stallThreshold)
        assertTrue(motion.limits[2].stallDetectionEnabled)
        assertFalse(motion.limits[0].stallDetectionEnabled)
    }

    @Test
    fun `motors latched off is reported as stalled`() {
        val latched = FirmwarePayloads.DEFAULT_MOTION.copy(motorsEnabled = false)
        val info = requireNotNull(
            SystemInfoParser.parse(FirmwarePayloads.systemInfo(motion = latched))
        )
        assertTrue(info.motorsStalled)
    }

    @Test
    fun `firmware without a motion block is not reported as stalled`() {
        // Absence of evidence is not a stall: pre-v4 firmware publishes nothing
        // here, and rendering that as "motors stopped" would be a lie.
        val info = requireNotNull(
            SystemInfoParser.parse(FirmwarePayloads.systemInfo(motion = null))
        )
        assertNull(info.motion)
        assertFalse(info.motorsStalled)
    }

    @Test
    fun `a truncated motion block is dropped rather than half-parsed`() {
        val full = FirmwarePayloads.systemInfo()
        val truncated = full.copyOf(full.size - 5)
        val info = requireNotNull(SystemInfoParser.parse(truncated))

        assertNotNull(info.capabilities) // everything before it still parsed
        assertNull(info.motion)
        assertFalse(info.motorsStalled)
    }
}
