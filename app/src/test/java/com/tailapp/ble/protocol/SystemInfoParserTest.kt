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

    // ── identity block: device name + bonds (SYS-6) ─────────────────

    @Test
    fun `parses the device name and bond list from the end of the payload`() {
        val payload = FirmwarePayloads.systemInfo(
            deviceName = "Foxtail",
            bonds = listOf(
                FirmwarePayloads.BondRecord(
                    addressType = 1,
                    address = listOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
                ),
                FirmwarePayloads.BondRecord(
                    addressType = 0,
                    address = listOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
                )
            )
        )
        val info = requireNotNull(SystemInfoParser.parse(payload))

        assertEquals("Foxtail", info.deviceName)
        val bonds = requireNotNull(info.bonds)
        assertEquals(2, bonds.size)

        // NimBLE hands out ble_addr_t.val least-significant byte first, so the
        // displayed address is the reverse of the wire order.
        assertEquals(0, bonds[0].index)
        assertEquals(1, bonds[0].addressType)
        assertEquals("random", bonds[0].addressTypeName)
        assertEquals("66:55:44:33:22:11", bonds[0].address)

        assertEquals(1, bonds[1].index)
        assertEquals(0, bonds[1].addressType)
        assertEquals("public", bonds[1].addressTypeName)
        assertEquals("0F:0E:0D:0C:0B:0A", bonds[1].address)
    }

    @Test
    fun `the identity block sits exactly where the device puts it`() {
        // Pin the arithmetic rather than a magic size: the block is only findable
        // by walking the four blocks the app does not model, so a wrong length
        // for any of them would move the bond list without anything noticing.
        val caps = Capabilities.DEFAULT
        val upToMotion = 5 + 4 * 16 + 1 + 2 * 2 +
            (3 + caps.patternIds.size + caps.effectIds.size + caps.blendModeIds.size + 5) +
            (1 + 4 * 13)
        val unmodelled = (4 * 4 + 6) + 8 + (2 * 5) + 14
        val identity = 1 + "Foxtail".toByteArray(Charsets.UTF_8).size + 1 + 2 * 7

        assertEquals(
            upToMotion + unmodelled + identity,
            FirmwarePayloads.systemInfo(
                deviceName = "Foxtail",
                bonds = List(2) { FirmwarePayloads.BondRecord() }
            ).size
        )
    }

    @Test
    fun `an empty name means the firmware default, not an absent block`() {
        // The device stores a zero-length name to mean "advertise the built-in
        // name". That is a real answer and must not read as "not reported".
        val info = requireNotNull(
            SystemInfoParser.parse(FirmwarePayloads.systemInfo(deviceName = ""))
        )
        assertEquals("", info.deviceName)
        assertEquals(emptyList<Any>(), info.bonds)
    }

    @Test
    fun `no bonds and no bond block are different answers`() {
        val none = requireNotNull(
            SystemInfoParser.parse(FirmwarePayloads.systemInfo(deviceName = "Tail"))
        )
        assertEquals(emptyList<Any>(), none.bonds)

        // Firmware that predates SYS-6 publishes nothing here. Reporting that as
        // "no phone is paired" would be a claim the device never made.
        val absent = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo()))
        assertNull(absent.bonds)
        assertNull(absent.deviceName)
    }

    @Test
    fun `an identity block that does not fit exactly is dropped, not guessed`() {
        // Getting to the block means skipping four blocks by their exact lengths,
        // so a misalignment would otherwise invent bonded peers out of somebody
        // else's floats. Requiring an exact fit is what makes that impossible.
        val full = FirmwarePayloads.systemInfo(
            deviceName = "Foxtail",
            bonds = List(2) { FirmwarePayloads.BondRecord() }
        )

        val short = requireNotNull(SystemInfoParser.parse(full.copyOf(full.size - 1)))
        assertNull(short.bonds)
        assertNull(short.deviceName)

        val long = requireNotNull(SystemInfoParser.parse(full + byteArrayOf(0x00)))
        assertNull(long.bonds)
        assertNull(long.deviceName)

        // Everything before it still parsed; only the block that did not add up
        // was discarded.
        assertNotNull(short.capabilities)
        assertNotNull(short.motion)
    }

    @Test
    fun `a bond count past the device's slot limit is rejected`() {
        val payload = FirmwarePayloads.systemInfo(
            deviceName = "Tail",
            bonds = List(Protocol.MAX_BOND_SLOTS + 1) { FirmwarePayloads.BondRecord() }
        )
        val info = requireNotNull(SystemInfoParser.parse(payload))
        assertNull(info.bonds)
    }

    @Test
    fun `the motion tuning block is read, not just walked past`() {
        // The device already reports these; the app showing a slider at its own
        // guess while the tail runs on the reported value is the drift this closes.
        val info = SystemInfoParser.parse(
            FirmwarePayloads.systemInfo(
                deviceName = "Tail",
                tuning = FirmwarePayloads.TuningBlock(
                    motorScales = listOf(0.62f, 0.62f, 1.25f, 0.62f),
                    gentleScale = 0.75f,
                    keyframeSlot = 2,
                    sequenceOccupancy = 0b0000_1010
                )
            )
        )
        requireNotNull(info)
        val tuning = requireNotNull(info.tuning)
        assertEquals(listOf(0.62f, 0.62f, 1.25f, 0.62f), tuning.motorScales)
        assertEquals(0.75f, tuning.gentleScale, 1e-6f)
        assertEquals(2, tuning.keyframeSlot)
        // Slots 1 and 3 occupied, 0 and 2 free — the bitmask the editor reads.
        assertFalse(tuning.isSequenceSlotOccupied(0))
        assertTrue(tuning.isSequenceSlotOccupied(1))
        assertFalse(tuning.isSequenceSlotOccupied(2))
        assertTrue(tuning.isSequenceSlotOccupied(3))
    }

    @Test
    fun `firmware without the tuning block reports null tuning, not zeros`() {
        // A motion-only payload (no identity block) predates the tuning block.
        // Null keeps "not reported" distinct from "every motor on the default".
        val info = SystemInfoParser.parse(FirmwarePayloads.systemInfo(deviceName = null))
        requireNotNull(info)
        assertNull(info.tuning)
    }

    @Test
    fun `the OTA block still parses correctly once tuning is consumed separately`() {
        // parseOta stopped skipping the tuning block; this guards the seam
        // between the two so a version does not get read from tuning bytes.
        val info = SystemInfoParser.parse(
            FirmwarePayloads.systemInfo(
                deviceName = "Tail",
                ota = FirmwarePayloads.OtaBlock(running = Triple(2, 3, 4))
            )
        )
        requireNotNull(info)
        assertEquals("2.3.4", info.runningFirmwareVersion.toString())
    }

    @Test
    fun `the longest name the device accepts still parses`() {
        val name = "a".repeat(Protocol.MAX_DEVICE_NAME_LEN)
        val info = requireNotNull(
            SystemInfoParser.parse(FirmwarePayloads.systemInfo(deviceName = name))
        )
        assertEquals(name, info.deviceName)
    }
}
