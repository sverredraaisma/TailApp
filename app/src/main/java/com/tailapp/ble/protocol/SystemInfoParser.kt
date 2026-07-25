package com.tailapp.ble.protocol

import com.tailapp.model.BondedPeer
import com.tailapp.model.Capabilities
import com.tailapp.model.FirmwareVersion
import com.tailapp.model.MotionTuning
import com.tailapp.model.ImuConfig
import com.tailapp.model.MotionLimits
import com.tailapp.model.MotionSystemState
import com.tailapp.model.OtaInfo
import com.tailapp.model.PidGains
import com.tailapp.model.ServoConfig
import com.tailapp.model.SystemInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the FF06 read payload.
 *
 * Protocol v1 layout — note `protocol_version` is prepended, so every field is
 * shifted one byte versus pre-v1 firmware:
 *
 * ```
 * [protocol_version u8][fw_major u8][fw_minor u8][fw_patch u8][num_servos u8]
 * per servo (16 B): [axis u8][half u8][invert u8][mux_ch u8][kp f32][ki f32][kd f32]
 * [num_imus u8]
 * per imu  (2 B):  [mux_ch u8][tap_enabled u8]
 * -- capabilities --
 * [num_patterns u8][pattern_ids...]
 * [num_effects u8][effect_ids...]
 * [num_blend_modes u8][blend_ids...]
 * [max_layers u8][max_servos u8][max_imus u8][max_led_rings u8][image_max_dim u8]
 * -- motion (protocol v4) --
 * [motors_enabled u8]
 * per motor (13 B): [max_vel f32][max_accel f32][max_jerk f32][stall_thresh u8]
 * -- motion tuning (MOT-2/4/8) --
 * per motor: [units_per_deg_per_sec f32]
 * [gentle_scale f32][keyframe_slot u8][sequence_slots_occupied u8]
 * -- OTA version/rollback (SYS-2) --
 * [running 3 x u8][pending_verify u8][other_valid u8][other 3 x u8]
 * -- per-IMU tap config and axis mix: walked past, not modelled --
 * -- identity (SYS-6) --
 * [name_len u8][name UTF-8 name_len B]
 * [num_bonds u8] per bond (7 B): [addr_type u8][addr 6 B]
 * ```
 *
 * Every trailing block is optional: firmware that predates one still yields a
 * valid [SystemInfo], with that block null. Absence has to stay distinguishable
 * from a value — a null motion block must not be reported to the user as a
 * stall, and a null bond list is not "no bonds".
 */
object SystemInfoParser {

    private const val SERVO_ENTRY_SIZE = 16
    private const val IMU_ENTRY_SIZE = 2
    private const val MOTION_ENTRY_SIZE = 13
    private const val HEADER_SIZE = 5

    // Blocks the device emits that nothing here reads. They are skipped by their
    // exact lengths purely so the blocks *after* them are findable at all: the
    // FF06 payload is not framed or length-prefixed, so a block's position is as
    // much a part of the contract as its contents, and every one of these
    // lengths mirrors `app_bridge.cpp::app_update_ble_state`.

    /** Per motor: `units_per_deg_per_sec f32`, then gentle scale, keyframe slot, occupancy. */
    private const val TUNING_ENTRY_SIZE = 4
    private const val TUNING_TRAILER_SIZE = 6

    /** Per IMU: `[engine][threshold][sensitivity][quiet_time_ms u16]`. */
    private const val TAP_ENTRY_SIZE = 5

    /** `[rotation_deg f32][gain_x f32][gain_y f32][invert_x u8][invert_y u8]`. */
    private const val AXIS_MIX_BLOCK_SIZE = 14

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
            val kp = buf.float
            val ki = buf.float
            val kd = buf.float
            ServoConfig(axis, half, invert, muxChannel, PidGains(kp, ki, kd))
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

        val capabilities = parseCapabilities(buf)
        val motion = if (capabilities != null) parseMotion(buf, numServos) else null
        val tuning = if (motion != null) parseTuning(buf, numServos) else null
        val ota = if (tuning != null) parseOta(buf) else null
        val identity = if (ota != null) parseIdentity(buf, numImus) else null
        return SystemInfo(
            protocolVersion, major, minor, patch, servos, imus, capabilities, motion,
            deviceName = identity?.name,
            bonds = identity?.bonds,
            ota = ota,
            tuning = tuning
        )
    }

    /**
     * Reads the motion-tuning block (MOT-2 / MOT-4 / MOT-8), which sits right
     * after the motion block and before the OTA block. Advancing the buffer here
     * is what lets the blocks after it be read by walking forward rather than by
     * counting lengths back from the payload's end.
     *
     * Returns null on firmware that predates the block; the OTA block sits behind
     * it on the wire, so its absence means there is nothing further to read.
     */
    private fun parseTuning(buf: ByteBuffer, numServos: Int): MotionTuning? {
        if (buf.remaining() < numServos * TUNING_ENTRY_SIZE + TUNING_TRAILER_SIZE) return null
        val scales = List(numServos) { buf.float }
        val gentleScale = buf.float
        val keyframeSlot = buf.u8()
        val occupancy = buf.u8()
        return MotionTuning(scales, gentleScale, keyframeSlot, occupancy)
    }

    /**
     * Reads the OTA version/rollback block (SYS-2). The tuning block before it
     * has already been consumed by [parseTuning], so this reads from the OTA
     * block's own offset.
     *
     * Walked forward from the front rather than counted back from the end of the
     * payload. Counting back is correct exactly until the device appends
     * something — and then it silently reports the tap block's bytes as a
     * firmware version, with no length or tag anywhere in FF06 to catch it.
     * Returns null on firmware that predates the block, which is not the same as
     * a device running 0.0.0.
     */
    private fun parseOta(buf: ByteBuffer): OtaInfo? {
        if (buf.remaining() < Protocol.OTA_INFO_BLOCK_SIZE) return null

        val runningMajor = buf.u8()
        val runningMinor = buf.u8()
        val runningPatch = buf.u8()
        val pendingVerify = buf.u8() != 0
        val otherValid = buf.u8() != 0
        val otherMajor = buf.u8()
        val otherMinor = buf.u8()
        val otherPatch = buf.u8()

        return OtaInfo(
            running = FirmwareVersion(runningMajor, runningMinor, runningPatch),
            pendingVerify = pendingVerify,
            other = if (otherValid) FirmwareVersion(otherMajor, otherMinor, otherPatch) else null
        )
    }

    private class Identity(val name: String, val bonds: List<BondedPeer>)

    /**
     * Reads the device name and bond list from the end of the payload (SYS-6).
     *
     * They sit behind the tap and axis-mix blocks, which this app has no model
     * for, so getting there means skipping those by their exact lengths — a
     * positional assumption, and a wrong one would invent bonded peers out of
     * somebody else's floats. The whole block is therefore required to *fit
     * exactly*: a plausible name length, at most [Protocol.MAX_BOND_SLOTS]
     * bonds, and not one byte left over. Anything else is read as "this firmware
     * does not publish an identity block" rather than guessed at.
     */
    private fun parseIdentity(buf: ByteBuffer, numImus: Int): Identity? {
        val preceding = numImus * TAP_ENTRY_SIZE + AXIS_MIX_BLOCK_SIZE
        if (buf.remaining() <= preceding) return null
        buf.position(buf.position() + preceding)

        val nameLength = buf.u8()
        if (nameLength > Protocol.MAX_DEVICE_NAME_LEN || buf.remaining() < nameLength + 1) return null
        val name = ByteArray(nameLength).also { buf.get(it) }.toString(Charsets.UTF_8)

        val bondCount = buf.u8()
        if (bondCount > Protocol.MAX_BOND_SLOTS) return null
        if (buf.remaining() != bondCount * Protocol.BOND_ADDR_RECORD_SIZE) return null

        val bonds = List(bondCount) { index ->
            val addressType = buf.u8()
            val raw = ByteArray(6).also { buf.get(it) }
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
     * Returns null when the motion block is absent (pre-v4 firmware) or
     * truncated. It sits after the capability block, so it is only reachable
     * once that parsed.
     */
    private fun parseMotion(buf: ByteBuffer, numServos: Int): MotionSystemState? {
        if (buf.remaining() < 1 + numServos * MOTION_ENTRY_SIZE) return null
        val motorsEnabled = buf.u8() != 0
        val limits = List(numServos) {
            val maxVelocity = buf.float
            val maxAcceleration = buf.float
            val maxJerk = buf.float
            MotionLimits(maxVelocity, maxAcceleration, maxJerk, buf.u8())
        }
        return MotionSystemState(motorsEnabled, limits)
    }

    /** Returns null (rather than throwing) when the capability block is absent or truncated. */
    private fun parseCapabilities(buf: ByteBuffer): Capabilities? {
        val patterns = buf.readIdList() ?: return null
        val effects = buf.readIdList() ?: return null
        val blendModes = buf.readIdList() ?: return null
        if (buf.remaining() < 5) return null
        return Capabilities(
            patternIds = patterns,
            effectIds = effects,
            blendModeIds = blendModes,
            maxLayers = buf.u8(),
            maxServos = buf.u8(),
            maxImus = buf.u8(),
            maxLedRings = buf.u8(),
            imageMaxDim = buf.u8()
        )
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF

    private fun ByteBuffer.readIdList(): List<Byte>? {
        if (remaining() < 1) return null
        val count = get().toInt() and 0xFF
        if (remaining() < count) return null
        return List(count) { get() }
    }
}
