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

data class SystemInfo(
    val protocolVersion: Int,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwarePatch: Int,
    val servos: List<ServoConfig>,
    val imus: List<ImuConfig>,
    val capabilities: Capabilities?
) {
    val firmwareVersion: String get() = "$firmwareMajor.$firmwareMinor.$firmwarePatch"

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
    /** Most recent FF09 acknowledgement — used to surface rejected commands. */
    val lastCommandResult: CommandResult? = null
) {
    val capabilities: Capabilities get() = systemInfo?.effectiveCapabilities ?: Capabilities.DEFAULT
}
