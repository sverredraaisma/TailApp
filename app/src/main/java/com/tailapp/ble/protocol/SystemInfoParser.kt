package com.tailapp.ble.protocol

import com.tailapp.model.Capabilities
import com.tailapp.model.ImuConfig
import com.tailapp.model.MotionLimits
import com.tailapp.model.MotionSystemState
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
 * ```
 *
 * Both trailing blocks are optional: firmware that predates either still yields
 * a valid [SystemInfo], with that block null. Absence has to stay
 * distinguishable from "motors are off" — a null motion block must not be
 * reported to the user as a stall.
 */
object SystemInfoParser {

    private const val SERVO_ENTRY_SIZE = 16
    private const val IMU_ENTRY_SIZE = 2
    private const val MOTION_ENTRY_SIZE = 13
    private const val HEADER_SIZE = 5

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
        return SystemInfo(protocolVersion, major, minor, patch, servos, imus, capabilities, motion)
    }

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
