package com.tailapp.model

import com.tailapp.ble.ConnectionState
import com.tailapp.ble.protocol.CommandResult
import com.tailapp.ble.protocol.Protocol

data class PidGains(
    val kp: Float,
    val ki: Float,
    val kd: Float
)

data class ServoConfig(
    val axis: Int,
    val half: Int,
    val invert: Boolean,
    val muxChannel: Int,
    val pid: PidGains
)

data class ImuConfig(
    val muxChannel: Int,
    val tapEnabled: Boolean
)

/**
 * Runtime capability enumeration from the FF06 read (protocol v1). Lets the UI
 * offer exactly what the connected firmware supports instead of hard-coding it.
 */
data class Capabilities(
    val patternIds: List<Byte>,
    val effectIds: List<Byte>,
    val blendModeIds: List<Byte>,
    val maxLayers: Int,
    val maxServos: Int,
    val maxImus: Int,
    val maxLedRings: Int,
    val imageMaxDim: Int
) {
    val patterns: List<MotionPattern> get() = patternIds.mapNotNull { MotionPattern.fromId(it) }
    val effects: List<LedEffect> get() = effectIds.mapNotNull { LedEffect.fromId(it) }
    val blendModes: List<BlendMode> get() = blendModeIds.mapNotNull { BlendMode.fromId(it) }

    companion object {
        /** Fallback for firmware that does not publish a capability block. */
        val DEFAULT = Capabilities(
            patternIds = MotionPattern.entries.map { it.id },
            effectIds = LedEffect.entries.map { it.id },
            blendModeIds = BlendMode.entries.map { it.id },
            maxLayers = 8,
            maxServos = 4,
            maxImus = 2,
            maxLedRings = 20,
            imageMaxDim = 32
        )
    }
}

/**
 * Open-loop motion limits and stall sensitivity for one motor (FF06 motion
 * block, protocol v4).
 *
 * These replaced PID as the way motion is shaped: the motors are TMC2209
 * steppers driven open-loop through a jerk-limited profile, so velocity,
 * acceleration and jerk are the real controls. A value of `0` means the
 * firmware substitutes its own default.
 */
data class MotionLimits(
    val maxVelocity: Float,
    val maxAcceleration: Float,
    val maxJerk: Float,
    /** TMC2209 SGTHRS; `0` = stall detection off for this motor. */
    val stallThreshold: Int
) {
    val stallDetectionEnabled: Boolean get() = stallThreshold > 0

    companion object {
        /** What the firmware's profile falls back to when a limit is 0. */
        val FIRMWARE_DEFAULT = MotionLimits(720f, 3600f, 36000f, 0)
    }
}

/** Live motor state from the FF06 motion block (protocol v4). */
data class MotionSystemState(
    /**
     * False while the motors are latched off after a stall. Nothing will move
     * until the app sends `enableMotors(true)`.
     */
    val motorsEnabled: Boolean,
    val limits: List<MotionLimits>
)

data class SystemInfo(
    val protocolVersion: Int,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwarePatch: Int,
    val servos: List<ServoConfig>,
    val imus: List<ImuConfig>,
    val capabilities: Capabilities?,
    /** Null on firmware older than protocol v4, which does not publish it. */
    val motion: MotionSystemState? = null
) {
    val firmwareVersion: String get() = "$firmwareMajor.$firmwareMinor.$firmwarePatch"

    /**
     * True only when the device explicitly reports its motors latched off.
     * Firmware that predates the motion block reports nothing, and absence of
     * evidence must not render as a stall banner.
     */
    val motorsStalled: Boolean get() = motion?.motorsEnabled == false

    /** Capability block if the device published one, otherwise conservative defaults. */
    val effectiveCapabilities: Capabilities get() = capabilities ?: Capabilities.DEFAULT

    /** False when the device speaks a wire format this build was not written against. */
    val isProtocolSupported: Boolean
        get() = protocolVersion == Protocol.SUPPORTED_PROTOCOL_VERSION
}

/** One of the firmware's profile slots, as reported by the FF08 read. */
data class ProfileSlot(
    val index: Int,
    val occupied: Boolean,
    val name: String?
) {
    /** Name to show in the UI — falls back to the slot number when unnamed. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Slot $index"
}

data class DeviceState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val systemInfo: SystemInfo? = null,
    val motionState: MotionState? = null,
    val ledState: LedState? = null,
    val profiles: List<ProfileSlot> = emptyList(),
    val fftStreamActive: Boolean = false,
    /**
     * Whether FF0A direct pixel streaming is bypassing the effect stack. Mirrors
     * transient session state on the device (FF03 `0x09` Set Direct Mode) — not
     * persisted, and the firmware reverts it on its own on disconnect, so this
     * is reset alongside the rest of [DeviceState] in `onDisconnected`.
     */
    val directModeActive: Boolean = false,
    /** Most recent FF09 acknowledgement — used to surface rejected commands. */
    val lastCommandResult: CommandResult? = null
) {
    val capabilities: Capabilities get() = systemInfo?.effectiveCapabilities ?: Capabilities.DEFAULT
}
