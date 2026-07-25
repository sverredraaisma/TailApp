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

/**
 * One peer the device has bonded with, from the FF06 bond block (SYS-6).
 *
 * [index] is the position in the device's own list and is what
 * `SCMD_FORGET_BOND` takes — it is not stable across a forget, so it is only
 * meaningful against the list it came from.
 */
data class BondedPeer(
    val index: Int,
    /** NimBLE `ble_addr_t.type`: 0 public, 1 random, 2/3 resolvable. */
    val addressType: Int,
    /** Colon-separated, most significant byte first — how a phone shows it. */
    val address: String
) {
    val addressTypeName: String
        get() = when (addressType) {
            0 -> "public"
            1 -> "random"
            2, 3 -> "resolvable"
            else -> "type $addressType"
        }
}

/**
 * The standard Device Information Service (0x180A) strings.
 *
 * The adopted service rather than fields on FF06, so a phone's own Bluetooth
 * settings and any generic BLE tool render the same identity this screen does.
 * Every field is individually optional: a device may publish some and not others.
 */
data class DeviceInformation(
    val manufacturer: String? = null,
    val modelNumber: String? = null,
    val firmwareRevision: String? = null,
    val hardwareRevision: String? = null
) {
    val isEmpty: Boolean
        get() = manufacturer == null && modelNumber == null &&
            firmwareRevision == null && hardwareRevision == null
}

/**
 * The low-battery policy the device says it is running (SYS-1).
 *
 * There is deliberately no `UNKNOWN` member: the device never reports one. A
 * board that cannot measure its pack is folded to Normal by the policy — it must
 * not behave as though it were empty — so "the device did not say" is the null
 * [BatteryStatus.policy], not a state of its own.
 */
enum class BatteryPolicy {
    NORMAL,
    LOW,
    CRITICAL;

    /**
     * What the firmware actually does at this level, from
     * `TailFirmware/main/config/config_manager.cpp::apply_battery_policy` and
     * its shipped defaults. Both derates are runtime overrides: the user's
     * configured brightness, limits and pattern are untouched and come back on
     * recovery.
     */
    val consequence: String?
        get() = when (this) {
            NORMAL -> null
            LOW ->
                "Master brightness is capped at 96 of 255 and every motor's velocity and " +
                    "acceleration limit is halved. Your saved settings are untouched and " +
                    "come back when the pack recovers."
            CRITICAL ->
                "Master brightness is capped at 32 of 255, the tail has been parked at " +
                    "neutral and the motors released to freewheel. Your saved settings are " +
                    "untouched; charging restores the pattern and re-energizes the motors."
        }
}

/**
 * Pack state, from the standard Battery Level characteristic (0x2A19) plus the
 * FF07 policy events.
 *
 * The two halves arrive separately on purpose. The percentage is a level; the
 * policy is what the device *did* about it, and only the device knows its own
 * thresholds — they are configuration, not constants — so the app never infers
 * one from the other.
 */
data class BatteryStatus(
    /**
     * 0-100, or null when the device reports `BATTERY_PERCENT_UNKNOWN`. Unknown
     * is a real state: a board whose divider is not populated knows nothing about
     * the pack, and rendering that as 0 % would tell the user their tail is flat.
     */
    val percent: Int? = null,

    /**
     * Null until the device says. The events fire on a threshold crossing only,
     * so a pack that has sat at Normal since boot has never announced anything —
     * which is not the same as the policy being off.
     */
    val policy: BatteryPolicy? = null
) {
    val isKnown: Boolean get() = percent != null

    /** True while the device is derating itself and the user deserves to know why. */
    val isDerated: Boolean get() = policy == BatteryPolicy.LOW || policy == BatteryPolicy.CRITICAL
}

/**
 * Per-motor and global motion tuning as the device reports it (FF06).
 *
 * @property motorScales velocity-command units per deg/s, one per motor. `0`
 *   means the motor is using the compile-time default rather than a calibrated
 *   value — the same convention the firmware uses on the command path.
 * @property gentleScale global multiplier on every motor's velocity and
 *   acceleration limit (MOT-4). `1.0` is full speed.
 * @property keyframeSlot the slot the keyframe pattern replays (MOT-8).
 * @property sequenceSlotsOccupied bitmask of which sequence slots hold data, so
 *   the keyframe editor can show occupancy without a read per slot.
 */
data class MotionTuning(
    val motorScales: List<Float>,
    val gentleScale: Float,
    val keyframeSlot: Int,
    val sequenceSlotsOccupied: Int
) {
    /** True if sequence [slot] (0-based) holds an uploaded sequence. */
    fun isSequenceSlotOccupied(slot: Int): Boolean =
        slot in 0..7 && (sequenceSlotsOccupied shr slot) and 1 == 1
}

data class SystemInfo(
    val protocolVersion: Int,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwarePatch: Int,
    val servos: List<ServoConfig>,
    val imus: List<ImuConfig>,
    val capabilities: Capabilities?,
    /** Null on firmware older than protocol v4, which does not publish it. */
    val motion: MotionSystemState? = null,
    /**
     * The advertised name (SYS-6). Null when the device published no identity
     * block at all; empty when it published one and the device is advertising the
     * firmware's built-in default rather than a chosen name.
     */
    val deviceName: String? = null,
    /** Null when the device published no bond block — not the same as no bonds. */
    val bonds: List<BondedPeer>? = null,

    /**
     * The OTA version/rollback block (SYS-2). Null on firmware that cannot be
     * updated over the air at all, which is why the update screen offers nothing
     * rather than assuming a running version of 0.0.0.
     */
    val ota: OtaInfo? = null,

    /**
     * The motion-tuning block (MOT-2 / MOT-4 / MOT-8). Null on firmware that
     * does not publish it. The device already reports these values, so the app
     * shows the settings that are actually in force rather than its own guesses.
     */
    val tuning: MotionTuning? = null
) {
    val firmwareVersion: String get() = "$firmwareMajor.$firmwareMinor.$firmwarePatch"

    /**
     * The version an offered image should be compared against: the running
     * image's own app descriptor, not the build-time constant at the front of
     * the FF06 read. After an update the two must agree, and this is the one
     * that cannot be stale.
     */
    val runningFirmwareVersion: FirmwareVersion?
        get() = ota?.running

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
    val lastCommandResult: CommandResult? = null,
    /** Pack level (0x2A19) and the low-power policy the device reports (FF07). */
    val battery: BatteryStatus = BatteryStatus(),
    /** The 0x180A strings, once read. Null before the first read lands. */
    val deviceInformation: DeviceInformation? = null,
    /**
     * The FF0C diagnostics snapshot (SYS-3). Null before the first read lands, and
     * again on disconnect. Live thereafter — the device notifies it once a second.
     */
    val diagnostics: Diagnostics? = null
) {
    val capabilities: Capabilities get() = systemInfo?.effectiveCapabilities ?: Capabilities.DEFAULT
}
