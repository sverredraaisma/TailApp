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
    fun `full default payload is 97 bytes as documented`() {
        assertEquals(97, FirmwarePayloads.systemInfo().size)
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
        val full = FirmwarePayloads.systemInfo()
        val truncated = full.copyOfRange(0, full.size - 3)
        val info = SystemInfoParser.parse(truncated)
        assertNotNull(info)
        assertNull(requireNotNull(info).capabilities)
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

        val future = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo(protocolVersion = 4)))
        assertFalse(future.isProtocolSupported)
    }
}
