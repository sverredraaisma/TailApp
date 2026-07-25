package com.tailapp.ble.protocol

import com.tailapp.model.BondedPeer
import com.tailapp.model.Capabilities
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.MotionTuning
import com.tailapp.model.ImuConfig
import com.tailapp.model.MotionLimits
import com.tailapp.model.MotionSystemState
import com.tailapp.model.OtaInfo
import com.tailapp.model.ServoConfig
import com.tailapp.model.SystemInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the FF06 read payload.
 *
 * Protocol v6 layout — a fixed preamble, then a run of framed blocks:
 *
 * ```
 * -- preamble (never framed) --
 * [protocol_version u8][fw_major u8][fw_minor u8][fw_patch u8][num_servos u8]
 * per servo (4 B): [axis u8][half u8][invert u8][mux_ch u8]
 * [num_imus u8]
 * per imu  (2 B):  [mux_ch u8][tap_enabled u8]
 * -- framed blocks, to end of payload --
 * per block: [tag u8][len u16 LE][payload len B]
 *   0x01 capabilities  0x02 motion  0x03 tuning  0x04 ota
 *   0x05 tap           0x06 axis mix 0x07 identity
 * ```
 *
 * The servo record is the assignment only since v6 — the PID gains that used to
 * follow it are retired. Every trailing block is optional and located by its
 * tag, not its position: **order is not part of the contract**, so a reader
 * finds each block by tag and skips one it does not recognise by its `len`. This
 * is why a future block the app has never heard of is passed over rather than
 * mis-read as the bytes of the block that used to follow. Absence still has to
 * stay distinguishable from a value — a null motion block must not be reported
 * as a stall, and a null bond list is not "no bonds" — so an unparsed block is
 * left null rather than defaulted.
 */
object SystemInfoParser {

    private const val SERVO_ENTRY_SIZE = 4
    private const val IMU_ENTRY_SIZE = 2
    private const val MOTION_ENTRY_SIZE = 13
    private const val HEADER_SIZE = 5

    /** `[tag u8][len u16 LE]` — the per-block framing prefix (`FF06_BLK_HEADER_SIZE`). */
    private const val BLOCK_HEADER_SIZE = 3

    // FF06_BLK_* tags, mirroring TailFirmware `main/ble/ble_protocol.h`. The
    // values are the contract; the order they arrive in is not.
    private const val TAG_CAPABILITIES = 0x01
    private const val TAG_MOTION = 0x02
    private const val TAG_TUNING = 0x03
    private const val TAG_OTA = 0x04
    private const val TAG_IDENTITY = 0x07

    fun parse(data: ByteArray): SystemInfo? {
        if (data.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val protocolVersion = buf.u8()
        val major = buf.u8()
        val minor = buf.u8()
        val patch = buf.u8()
        val numServos = buf.u8()

        if (buf.remaining() < numServos * SERVO_ENTRY_SIZE) return null

        val servos = List(numServos) {
            val axis = buf.u8()
            val half = buf.u8()
            val invert = buf.u8() != 0
            val muxChannel = buf.u8()
            ServoConfig(axis, half, invert, muxChannel)
        }

        if (buf.remaining() < 1) {
            return SystemInfo(protocolVersion, major, minor, patch, servos, emptyList(), null)
        }
        val numImus = buf.u8()
        if (buf.remaining() < numImus * IMU_ENTRY_SIZE) {
            return SystemInfo(protocolVersion, major, minor, patch, servos, emptyList(), null)
        }

        val imus = List(numImus) {
            val muxChannel = buf.u8()
            val tapEnabled = buf.u8() != 0
            ImuConfig(muxChannel, tapEnabled)
        }

        // Framed blocks. Each is dispatched by tag to the block parser that has
        // always read it; only how the block is *found* changed. An unknown tag
        // is skipped by its length, and a length that runs past the payload ends
        // the walk cleanly rather than reading a partial block as fields.
        var capabilities: Capabilities? = null
        var motion: MotionSystemState? = null
        var tuning: MotionTuning? = null
        var ota: OtaInfo? = null
        var identity: Identity? = null

        while (buf.remaining() >= BLOCK_HEADER_SIZE) {
            val tag = buf.u8()
            val len = buf.u16()
            if (len > buf.remaining()) break
            val payloadStart = buf.position()
            val block = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
                limit(payloadStart + len)
            }
            when (tag) {
                TAG_CAPABILITIES -> capabilities = parseCapabilities(block)
                TAG_MOTION -> motion = parseMotion(block, numServos)
                TAG_TUNING -> tuning = parseTuning(block, numServos)
                TAG_OTA -> ota = parseOta(block)
                TAG_IDENTITY -> identity = parseIdentity(block)
                // Any other tag is a block this build does not model. Skipping it
                // by its declared length is exactly what framing buys.
            }
            buf.position(payloadStart + len)
        }

        return SystemInfo(
            protocolVersion, major, minor, patch, servos, imus, capabilities, motion,
            deviceName = identity?.name,
            bonds = identity?.bonds,
            ota = ota,
            tuning = tuning
        )
    }

    /**
     * Reads the motion-tuning block (MOT-2 / MOT-4 / MOT-8) from its own framed
     * payload. Returns null if the block is shorter than the fields it must
     * carry, which leaves "not reported" distinct from a device on every default.
     */
    private fun parseTuning(block: ByteBuffer, numServos: Int): MotionTuning? {
        if (block.remaining() < numServos * SERVO_ENTRY_SIZE + 6) return null
        val scales = List(numServos) { block.float }
        val gentleScale = block.float
        val keyframeSlot = block.u8()
        val occupancy = block.u8()
        return MotionTuning(scales, gentleScale, keyframeSlot, occupancy)
    }

    /**
     * Reads the OTA version/rollback block (SYS-2) from its framed payload.
     * Returns null on firmware that predates the block, which is not the same as
     * a device running 0.0.0.
     */
    private fun parseOta(block: ByteBuffer): OtaInfo? {
        if (block.remaining() < Protocol.OTA_INFO_BLOCK_SIZE) return null

        val runningMajor = block.u8()
        val runningMinor = block.u8()
        val runningPatch = block.u8()
        val pendingVerify = block.u8() != 0
        val otherValid = block.u8() != 0
        val otherMajor = block.u8()
        val otherMinor = block.u8()
        val otherPatch = block.u8()

        return OtaInfo(
            running = FirmwareVersion(runningMajor, runningMinor, runningPatch),
            pendingVerify = pendingVerify,
            other = if (otherValid) FirmwareVersion(otherMajor, otherMinor, otherPatch) else null
        )
    }

    private class Identity(val name: String, val bonds: List<BondedPeer>)

    /**
     * Reads the device name and bond list (SYS-6) from the identity block's
     * framed payload.
     *
     * The block is now its own framed unit, so getting here no longer means
     * skipping the tap and axis-mix blocks by their exact lengths — those are
     * their own tags. What remains load-bearing is that the two counts describe
     * bytes that are actually present: a [nameLength] or [bondCount] larger than
     * the payload would otherwise read a bonded peer out of nothing, so a block
     * that does not hold what it claims is dropped rather than guessed at.
     */
    private fun parseIdentity(block: ByteBuffer): Identity? {
        if (block.remaining() < 1) return null
        val nameLength = block.u8()
        if (nameLength > Protocol.MAX_DEVICE_NAME_LEN || block.remaining() < nameLength + 1) return null
        val name = ByteArray(nameLength).also { block.get(it) }.toString(Charsets.UTF_8)

        val bondCount = block.u8()
        if (bondCount > Protocol.MAX_BOND_SLOTS) return null
        if (block.remaining() < bondCount * Protocol.BOND_ADDR_RECORD_SIZE) return null

        val bonds = List(bondCount) { index ->
            val addressType = block.u8()
            val raw = ByteArray(6).also { block.get(it) }
            BondedPeer(index, addressType, formatAddress(raw))
        }
        return Identity(name, bonds)
    }

    /**
     * NimBLE hands out `ble_addr_t.val` least-significant byte first, so the
     * display order is the reverse of the wire order.
     */
    private fun formatAddress(raw: ByteArray): String =
        raw.reversed().joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * Reads the motion block (live motor-enable + per-motor open-loop limits)
     * from its framed payload. Returns null when the block is truncated.
     */
    private fun parseMotion(block: ByteBuffer, numServos: Int): MotionSystemState? {
        if (block.remaining() < 1 + numServos * MOTION_ENTRY_SIZE) return null
        val motorsEnabled = block.u8() != 0
        val limits = List(numServos) {
            val maxVelocity = block.float
            val maxAcceleration = block.float
            val maxJerk = block.float
            MotionLimits(maxVelocity, maxAcceleration, maxJerk, block.u8())
        }
        return MotionSystemState(motorsEnabled, limits)
    }

    /** Returns null (rather than throwing) when the capability block is truncated. */
    private fun parseCapabilities(block: ByteBuffer): Capabilities? {
        val patterns = block.readIdList() ?: return null
        val effects = block.readIdList() ?: return null
        val blendModes = block.readIdList() ?: return null
        if (block.remaining() < 5) return null
        return Capabilities(
            patternIds = patterns,
            effectIds = effects,
            blendModeIds = blendModes,
            maxLayers = block.u8(),
            maxServos = block.u8(),
            maxImus = block.u8(),
            maxLedRings = block.u8(),
            imageMaxDim = block.u8()
        )
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF

    private fun ByteBuffer.u16(): Int {
        val lo = get().toInt() and 0xFF
        val hi = get().toInt() and 0xFF
        return lo or (hi shl 8)
    }

    private fun ByteBuffer.readIdList(): List<Byte>? {
        if (remaining() < 1) return null
        val count = get().toInt() and 0xFF
        if (remaining() < count) return null
        return List(count) { get() }
    }
}
