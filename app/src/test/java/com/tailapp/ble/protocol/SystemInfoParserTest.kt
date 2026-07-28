package com.tailapp.ble.protocol

import com.tailapp.model.BlendMode
import com.tailapp.model.Capabilities
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.LedEffect
import com.tailapp.model.MotionPattern
import com.tailapp.testutil.FirmwarePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The FF06 read is protocol v6: a fixed preamble with a 4-byte servo record (no
 * PID), then framed `[tag][len]` blocks located by tag rather than position.
 * These tests assert numbers off a hand-built payload — including one whose
 * blocks are out of declaration order with an unknown tag between them — and
 * that a v5-style unframed payload is refused rather than mis-read.
 */
class SystemInfoParserTest {

    // FF06_BLK_* tags (TailFirmware main/ble/ble_protocol.h).
    private val CAPS = 0x01
    private val MOTION = 0x02
    private val TUNING = 0x03
    private val OTA = 0x04
    private val IDENTITY = 0x07

    private fun f32(v: Float): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()

    /** `[tag][len u16 LE][payload]` — one framed FF06 block. */
    private fun frame(tag: Int, payload: ByteArray): ByteArray =
        byteArrayOf(tag.toByte(), (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte()) +
            payload

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
    fun `the servo record is four bytes of assignment with no pid`() {
        // Regression: the v6 servo record dropped the 12 bytes of PID gains, so a
        // reader still striding 16 bytes would read every field past servo 0 out
        // of the wrong place. The mux channel is what catches that.
        val info = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo()))

        val second = info.servos[1]
        assertEquals(0, second.axis)
        assertEquals(1, second.half)
        assertTrue(second.invert)
        assertEquals(1, second.muxChannel)

        val fourth = info.servos[3]
        assertEquals(1, fourth.axis)
        assertEquals(1, fourth.half)
        assertEquals(3, fourth.muxChannel)
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
    fun `payload size tracks the framed blocks it carries`() {
        // Pin the framing arithmetic rather than a magic size: the capability
        // block is variable-length by construction, and each block now costs a
        // 3-byte [tag][len] prefix.
        val caps = Capabilities.DEFAULT
        val preamble = 5 + 4 * 4 + 1 + 2 * 2 // proto+fw+servos(4B each)+imus
        val capsBlock = 3 + (3 + caps.patternIds.size + caps.effectIds.size + caps.blendModeIds.size + 5)
        val motionBlock = 3 + (1 + 4 * 13)

        assertEquals(preamble + capsBlock, FirmwarePayloads.systemInfo(motion = null).size)
        assertEquals(preamble + capsBlock + motionBlock, FirmwarePayloads.systemInfo().size)
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

    // ── framing: order-independence and unknown-tag skipping ────────

    @Test
    fun `framed blocks are found by tag regardless of order, unknown tag skipped`() {
        // The blocks below are deliberately out of declaration order — identity
        // first, an unknown tag next, then motion, and capabilities LAST — to
        // prove the reader locates each by tag and skips the block it does not
        // model by its length rather than depending on where it sits.
        val preamble = byteArrayOf(
            6,          // protocol v6
            1, 2, 3,    // firmware 1.2.3
            2,          // num servos
            0, 0, 0, 5, // servo0: axis0 half0 invert0 mux5
            1, 1, 1, 6, // servo1: axis1 half1 invert1 mux6
            1,          // num imus
            7, 1        // imu0: mux7 tap on
        )
        val capsPayload = byteArrayOf(1, 0, 1, 0, 1, 0, 8, 4, 2, 20, 32)
        val motionPayload = byteArrayOf(1) +
            f32(720f) + f32(3600f) + f32(36000f) + byteArrayOf(0) +
            f32(360f) + f32(1800f) + f32(18000f) + byteArrayOf(60)
        val identityPayload = byteArrayOf(2, 'H'.code.toByte(), 'i'.code.toByte(), 0)
        val unknownPayload = byteArrayOf(9, 9, 9)

        val data = preamble +
            frame(IDENTITY, identityPayload) +
            frame(0x42, unknownPayload) +   // a block this build does not model
            frame(MOTION, motionPayload) +
            frame(CAPS, capsPayload)

        val info = requireNotNull(SystemInfoParser.parse(data))

        assertEquals(6, info.protocolVersion)
        assertEquals("1.2.3", info.firmwareVersion)
        assertEquals(2, info.servos.size)
        assertEquals(6, info.servos[1].muxChannel)

        val caps = requireNotNull(info.capabilities)
        assertEquals(listOf<Byte>(0), caps.patternIds)
        assertEquals(8, caps.maxLayers)

        val motion = requireNotNull(info.motion)
        assertTrue(motion.motorsEnabled)
        assertEquals(2, motion.limits.size)
        assertEquals(720f, motion.limits[0].maxVelocity, 0f)
        assertEquals(60, motion.limits[1].stallThreshold)

        assertEquals("Hi", info.deviceName)
    }

    @Test
    fun `a malformed block is skipped by its length, later blocks still parse`() {
        // A correctly-framed capability block whose payload is too short to be a
        // capability block, then a valid motion block. The bad block is dropped
        // to null and skipped by its declared length, so the motion block after
        // it is still located — a truncated payload would instead end the walk.
        val preamble = byteArrayOf(
            6, 1, 0, 0, 1, /* servo0 */ 0, 0, 0, 0, /* imus */ 1, 3, 0
        )
        val badCaps = frame(CAPS, byteArrayOf(5)) // readIdList wants 5 ids, has 0
        val motionPayload = byteArrayOf(1) + f32(720f) + f32(3600f) + f32(36000f) + byteArrayOf(0)

        val info = requireNotNull(SystemInfoParser.parse(preamble + badCaps + frame(MOTION, motionPayload)))

        assertNull(info.capabilities)
        val motion = requireNotNull(info.motion)
        assertEquals(1, motion.limits.size)
        assertEquals(720f, motion.limits[0].maxVelocity, 0f)
    }

    @Test
    fun `a block whose length runs past the payload ends the walk cleanly`() {
        // A frame that claims more bytes than remain is a framing error, not a
        // field: the walk stops rather than reading a partial block, so nothing
        // after the break is invented.
        val preamble = byteArrayOf(6, 1, 0, 0, 1, 0, 0, 0, 0, 1, 3, 0)
        val overlong = byteArrayOf(CAPS.toByte(), 0xFF.toByte(), 0x00) + byteArrayOf(1, 2, 3)

        val info = requireNotNull(SystemInfoParser.parse(preamble + overlong))
        assertNull(info.capabilities)
        assertNull(info.motion)
    }

    // ── v5 rejection ────────────────────────────────────────────────

    @Test
    fun `a v5-style unframed payload is refused as unsupported, not mis-parsed`() {
        // v5 carried a 16-byte servo record (with PID) and unframed trailing
        // blocks. Fed to the v6 reader — which strides 4 bytes per servo and then
        // expects frames — those bytes read as different servos and as a frame
        // whose length overshoots, so the walk stops. What must hold is that this
        // is refused (version says v5) and that no block is reconstructed from the
        // PID floats: a v5 device is now unsupported, and that has to be visible.
        val v5 = buildV5SystemInfo()
        val info = requireNotNull(SystemInfoParser.parse(v5))

        assertEquals(5, info.protocolVersion)
        assertFalse(info.isProtocolSupported)
        // servo 0's first four bytes are the same in both layouts; everything
        // past it is misaligned, so the record is not silently accepted.
        assertEquals(63, info.servos[1].muxChannel) // a byte of servo 0's kp float, not the v5 mux
        // Nothing trailing is fabricated out of the 16-byte body.
        assertNull(info.capabilities)
        assertNull(info.motion)
        assertNull(info.bonds)
        assertNull(info.deviceName)
    }

    /** A representative v5 FF06 read: 16-byte servo records, unframed blocks. */
    private fun buildV5SystemInfo(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) { out.add((v and 0xFF).toByte()) }
        fun f(v: Float) { f32(v).forEach { out.add(it) } }
        u8(5); u8(1); u8(0); u8(0); u8(4) // proto v5, fw, num servos
        val servos = listOf(
            intArrayOf(0, 0, 0, 0), intArrayOf(0, 1, 1, 1),
            intArrayOf(1, 0, 0, 2), intArrayOf(1, 1, 1, 3)
        )
        val gains = listOf(1.0f, 2.0f, 3.0f, 4.0f)
        servos.forEachIndexed { i, s ->
            u8(s[0]); u8(s[1]); u8(s[2]); u8(s[3]); f(gains[i]); f(0f); f(0f) // 16 bytes each
        }
        u8(2); u8(4); u8(1); u8(5); u8(0) // imus
        // Unframed v5 capability block, positional as v5 had it.
        u8(11); (0..10).forEach { u8(it) }
        u8(17); (0..16).forEach { u8(it) }
        u8(7); (0..6).forEach { u8(it) }
        u8(8); u8(4); u8(2); u8(20); u8(32)
        return out.toByteArray()
    }

    // ── preamble validation ─────────────────────────────────────────

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
        // Cut into the capability block's framed payload: its declared length now
        // overshoots what is left, so the walk stops and the block is null.
        val full = FirmwarePayloads.systemInfo(motion = null)
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
        // Says 4 servos (16 bytes at 4 B each) but carries 15.
        val data = byteArrayOf(6, 1, 0, 0, 4) + ByteArray(15)
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

        // v5 is now unsupported: its unframed FF06 and 16-byte servo record do
        // not match this build, and the mismatch must surface rather than be
        // parsed on a best-effort basis.
        val v5 = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo(protocolVersion = 5)))
        assertFalse(v5.isProtocolSupported)

        val future = requireNotNull(
            SystemInfoParser.parse(
                FirmwarePayloads.systemInfo(
                    protocolVersion = Protocol.SUPPORTED_PROTOCOL_VERSION + 1
                )
            )
        )
        assertFalse(future.isProtocolSupported)
    }

    // ── motion block ────────────────────────────────────────────────

    @Test
    fun `parses the framed motion block`() {
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
        // Absence of evidence is not a stall: firmware that publishes no motion
        // block reports nothing here, and rendering that as "motors stopped"
        // would be a lie.
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

        assertNotNull(info.capabilities) // the block before it still parsed
        assertNull(info.motion)
        assertFalse(info.motorsStalled)
    }

    // ── identity block: device name + bonds (SYS-6) ─────────────────

    @Test
    fun `parses the device name and bond list from the identity block`() {
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
    fun `the framed payload is the sum of its blocks`() {
        // Pin the framing arithmetic: every block costs its 3-byte prefix, and
        // the identity block's own length is name + bonds. A wrong length on any
        // block would move the total.
        val caps = Capabilities.DEFAULT
        val preamble = 5 + 4 * 4 + 1 + 2 * 2
        val capsBlock = 3 + (3 + caps.patternIds.size + caps.effectIds.size + caps.blendModeIds.size + 5)
        val motionBlock = 3 + (1 + 4 * 13)
        val tuningBlock = 3 + (4 * 4 + 6)
        val otaBlock = 3 + 8
        val tapBlock = 3 + (2 * 5)
        val axisMixBlock = 3 + 14
        val identityBlock = 3 + (1 + "Foxtail".toByteArray(Charsets.UTF_8).size + 1 + 2 * 7)

        assertEquals(
            preamble + capsBlock + motionBlock + tuningBlock + otaBlock + tapBlock + axisMixBlock + identityBlock,
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

        // Firmware that publishes no identity block says nothing here. Reporting
        // that as "no phone is paired" would be a claim the device never made.
        val absent = requireNotNull(SystemInfoParser.parse(FirmwarePayloads.systemInfo()))
        assertNull(absent.bonds)
        assertNull(absent.deviceName)
    }

    @Test
    fun `a truncated identity block is dropped while earlier blocks survive`() {
        // Cutting the last byte off leaves the identity frame claiming one more
        // byte than remains, so the walk stops there: the identity block is null
        // and never guessed, but every block emitted before it still parsed.
        val full = FirmwarePayloads.systemInfo(
            deviceName = "Foxtail",
            bonds = List(2) { FirmwarePayloads.BondRecord() }
        )
        val short = requireNotNull(SystemInfoParser.parse(full.copyOf(full.size - 1)))

        assertNull(short.bonds)
        assertNull(short.deviceName)
        assertNotNull(short.capabilities)
        assertNotNull(short.motion)
        assertNotNull(short.tuning)
        assertNotNull(short.ota)
    }

    @Test
    fun `a trailing partial frame after the identity block is ignored`() {
        // Framing tolerates trailing bytes it cannot read as a whole frame: a
        // stray byte after the last block is dropped, and the identity block —
        // which fit — is still parsed. (Under the old positional layout this same
        // extra byte would have discarded the bond list.)
        val full = FirmwarePayloads.systemInfo(
            deviceName = "Foxtail",
            bonds = List(2) { FirmwarePayloads.BondRecord() }
        )
        val info = requireNotNull(SystemInfoParser.parse(full + byteArrayOf(0x00)))
        assertEquals("Foxtail", info.deviceName)
        assertEquals(2, requireNotNull(info.bonds).size)
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

    // ── tuning and OTA blocks ───────────────────────────────────────

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
        // A payload with no identity block does not carry the tuning block
        // either. Null keeps "not reported" distinct from "every motor on default".
        val info = SystemInfoParser.parse(FirmwarePayloads.systemInfo(deviceName = null))
        requireNotNull(info)
        assertNull(info.tuning)
    }

    @Test
    fun `the OTA block parses from its own frame`() {
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
    fun `the OTA flags byte is read as a bitfield, not a boolean`() {
        // Bit 0 is pending-verify; bit 1 says the version triplet is a zero
        // placeholder the device could not read from a real descriptor. Reading
        // the whole byte as `!= 0` conflated them, so a device that could not
        // describe itself looked like one whose image was on probation.
        val placeholder = requireNotNull(
            SystemInfoParser.parse(
                FirmwarePayloads.systemInfo(
                    deviceName = "Tail",
                    ota = FirmwarePayloads.OtaBlock(
                        running = Triple(0, 0, 0),
                        pendingVerify = false,
                        runningVersionUnknown = true
                    )
                )
            )
        )
        val ota = requireNotNull(placeholder.ota)
        assertFalse(ota.pendingVerify)
        assertTrue(ota.runningVersionUnknown)
        // The placeholder must not be offered as a version to compare against.
        assertNull(ota.knownRunning)
        assertEquals("0.0.0", ota.running.toString())
    }

    @Test
    fun `both OTA flag bits can be set at once`() {
        val info = requireNotNull(
            SystemInfoParser.parse(
                FirmwarePayloads.systemInfo(
                    deviceName = "Tail",
                    ota = FirmwarePayloads.OtaBlock(
                        pendingVerify = true,
                        runningVersionUnknown = true
                    )
                )
            )
        )
        val ota = requireNotNull(info.ota)
        assertTrue(ota.pendingVerify)
        assertTrue(ota.runningVersionUnknown)
    }

    @Test
    fun `a plain pending-verify flag still reads exactly as it always did`() {
        // The firmware kept bit 0's meaning and position precisely so this stays
        // true; the reserved bits are zero, so the byte is still 0x01.
        val info = requireNotNull(
            SystemInfoParser.parse(
                FirmwarePayloads.systemInfo(
                    deviceName = "Tail",
                    ota = FirmwarePayloads.OtaBlock(
                        running = Triple(2, 3, 4),
                        pendingVerify = true
                    )
                )
            )
        )
        val ota = requireNotNull(info.ota)
        assertTrue(ota.pendingVerify)
        assertFalse(ota.runningVersionUnknown)
        assertEquals(FirmwareVersion(2, 3, 4), ota.knownRunning)
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
