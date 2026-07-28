package com.tailapp.ble.protocol

/**
 * Wire-format constants shared with TailFirmware (`main/ble/ble_protocol.h`).
 *
 * The firmware reports its own protocol version as the first byte of the FF06
 * read; [SUPPORTED_PROTOCOL_VERSION] is the version this app was written against.
 */
object Protocol {
    /**
     * v3 corrected the image-upload CRC-32 to the standard polynomial. Talking to
     * a v2 device with a v3 app means every BEGIN-armed upload is rejected, so the
     * mismatch banner is load-bearing, not cosmetic.
     *
     * v4 added the stall event, motor enable/disable, per-motor motion limits,
     * and the FF06 motion block. v5 added the FF09 sequence byte and readable
     * result, the readable FF07 event ring, and `RESULT_BUSY`.
     *
     * v6 is a bundled break: it retired the vestigial PID (FF01 `0x04` now
     * answers `UNKNOWN_CMD`, the servo record shrank from 16 bytes to 4) and
     * framed the FF06 trailing blocks with a `[tag][len]` prefix. A v5 device is
     * therefore *unsupported* — its unframed FF06 and 16-byte servo record would
     * mis-parse — which is why the mismatch is surfaced as a banner rather than
     * parsed on a best-effort basis.
     */
    const val SUPPORTED_PROTOCOL_VERSION = 6

    /** Profile slots the firmware exposes on FF08 (`MAX_PROFILE_SLOTS`). */
    const val MAX_PROFILE_SLOTS = 4

    /** Longest profile name the firmware stores (`MAX_PROFILE_NAME_LEN`), in UTF-8 bytes. */
    const val MAX_PROFILE_NAME_LEN = 16

    // Device identity and bonds (SYS-6). Constants from TailFirmware
    // `main/config/config_types.h` and `main/ble/ble_protocol.h`.

    /**
     * Longest advertised name the firmware accepts (`MAX_DEVICE_NAME_LEN`), in
     * UTF-8 bytes. The device *rejects* a longer one instead of truncating —
     * truncation would advertise a name the user did not choose — so a name has
     * to be measured before it is sent, not clipped to fit.
     */
    const val MAX_DEVICE_NAME_LEN = 31

    /** `MAX_BOND_SLOTS` — the most bonds the FF06 read can carry. */
    const val MAX_BOND_SLOTS = 4

    /** `BOND_ADDR_RECORD_SIZE` — `[addr_type u8][addr 6 bytes]` per bond. */
    const val BOND_ADDR_RECORD_SIZE = 7

    /**
     * `BOND_INDEX_ALL` — forget every bond rather than one by index. Its own
     * value rather than a magic count, because the list is device-side state the
     * app can hold a stale copy of.
     */
    const val BOND_INDEX_ALL: Byte = 0xFF.toByte()

    /**
     * `BATTERY_PERCENT_UNKNOWN` — deliberately outside the 0-100 the standard
     * Battery Level characteristic allows, so a board with no sense line renders
     * as "--" and not as a flat pack.
     */
    const val BATTERY_PERCENT_UNKNOWN = 0xFF

    /** Staging buffer size for image uploads (`IMAGE_UPLOAD_MAX`) — 32x32 RGB. */
    const val IMAGE_UPLOAD_MAX = 3072

    /** `effect_id` the firmware writes into a removed layer slot. */
    const val EMPTY_EFFECT_ID: Byte = 0xFF.toByte()

    // Keyframe sequences (MOT-8). The blob layout is documented in TailFirmware
    // `main/ble/ble_protocol.h` next to MCMD_BEGIN_SEQUENCE and parsed by
    // `main/motion/keyframe_sequence.cpp`; these are its `#define`s.

    /** `SEQUENCE_FORMAT_VERSION` — byte 0 of the blob; the device refuses any other. */
    const val SEQUENCE_FORMAT_VERSION: Byte = 1

    /** `SEQUENCE_FLAG_LOOP` in the blob's flags byte. */
    const val SEQUENCE_FLAG_LOOP: Byte = 0x01

    /** `SEQUENCE_HEADER_SIZE` — version, flags, u16 count, u32 reserved. */
    const val SEQUENCE_HEADER_SIZE = 8

    /** `SEQUENCE_KEYFRAME_SIZE` — u32 time_ms then four int16 angles. */
    const val SEQUENCE_KEYFRAME_SIZE = 12

    /** `MAX_SEQUENCE_KEYFRAMES` — the device's fixed keyframe array. */
    const val MAX_SEQUENCE_KEYFRAMES = 128

    /** `MAX_SEQUENCE_SLOTS` — stored sequences, addressed 0..3. */
    const val MAX_SEQUENCE_SLOTS = 4

    /** `SEQUENCE_MAX_BYTES` — the device's upload staging buffer. */
    const val SEQUENCE_MAX_BYTES =
        SEQUENCE_HEADER_SIZE + MAX_SEQUENCE_KEYFRAMES * SEQUENCE_KEYFRAME_SIZE

    // Behavior engine (MOT-6). The two record layouts are documented in
    // TailFirmware `main/ble/ble_protocol.h` next to MCMD_SET_BEHAVIOR_CFG and
    // are `behavior_state_config_t` / `behavior_trigger_config_t` byte-for-byte.

    /** `BEHAVIOR_STATE_RECORD_SIZE` — the fixed-size state record. */
    const val BEHAVIOR_STATE_RECORD_SIZE = 40

    /** `BEHAVIOR_TRIGGER_RECORD_SIZE` — the fixed-size trigger record. */
    const val BEHAVIOR_TRIGGER_RECORD_SIZE = 16

    /** `MAX_BEHAVIOR_STATES` — the device's fixed state array. */
    const val MAX_BEHAVIOR_STATES = 8

    /** `MAX_BEHAVIOR_TRIGGERS` — the device's fixed trigger array. */
    const val MAX_BEHAVIOR_TRIGGERS = 16

    /**
     * FF02 motion-state payload size (`MOTION_STATE_SIZE`) as of protocol v5 —
     * and the minimum a parse needs, since MOT-6 *appends* its behavior block
     * rather than moving anything.
     */
    const val MOTION_STATE_SIZE = 77

    /**
     * FF02 with the 4-byte behavior block: active state, why it last changed,
     * the arbitration flags, and the pattern the engine actually installed.
     */
    const val MOTION_STATE_WITH_BEHAVIOR_SIZE = MOTION_STATE_SIZE + 4

    /**
     * With MOT-0's logical-position block: the behavior payload plus four floats
     * (base X, tip X, base Y, tip Y — the same `[axis*2 + segment]` order the
     * physical positions use). The two spaces differ only when a non-identity
     * axis mix is configured, so plotting one while labelled the other would
     * mislead exactly when the mixer is doing something.
     */
    const val MOTION_STATE_WITH_LOGICAL_SIZE = MOTION_STATE_WITH_BEHAVIOR_SIZE + 16

    /**
     * Bytes per layer entry in the FF04 LED-state payload: 8 header bytes
     * (effect, blend, enabled, 4 transforms, opacity) plus 8 float params.
     */
    const val LED_LAYER_SIZE = 40

    /**
     * The largest ATT MTU worth planning for.
     *
     * MTU is the whole ATT packet, the 3-byte opcode+handle header included — it
     * is not a payload figure with the header still to be added. The firmware
     * asks for a preferred MTU of 512 (`ble_att_set_preferred_mtu(512)` in
     * `ble_service.c`), so a real connection never negotiates above that; 517 is
     * kept here only as a safe ceiling, since Android will not offer more and a
     * bound that is too high can only over-reserve, never truncate a write.
     *
     * Used as the upper bound for [DirectPixelFrame]'s packet-budget sanity
     * check, since a single FF0A write can never carry more LEDs than this allows
     * regardless of what the live connection actually negotiated.
     */
    const val MAX_ATT_MTU = 517

    // OTA firmware update (SYS-2). Constants from TailFirmware
    // `main/ble/ble_protocol.h` and `main/system/ota_manager.h`.

    /** `OTA_STATUS_SIZE` — `[accepted u32 LE][state u8][result u8]` on FF0E. */
    const val OTA_STATUS_SIZE = 6

    /** `OTA_INFO_BLOCK_SIZE` — the FF06 version/rollback block. */
    const val OTA_INFO_BLOCK_SIZE = 8

    /**
     * `OTA_WINDOW_BYTES` — how far ahead of the last echoed `accepted` the app
     * may run.
     *
     * Sized to the device's command queue (8 packets of up to 514 bytes), which
     * is the real buffer between the radio and the task that writes flash.
     * Running further ahead only produces drops and rewinds, and a dropped chunk
     * is *silent* — FF0E is unacknowledged, so there is no `BUSY` to hear and
     * nothing to retry against except the next offset echo.
     */
    const val OTA_WINDOW_BYTES = 4096

    /**
     * `OTA_ECHO_BYTES` — the device echoes at least this often, plus on the last
     * byte of the image, on every rejection, and on each of the three FF06
     * control commands. So the window never empties while the transfer is
     * healthy, and closes at once when it is not.
     */
    const val OTA_ECHO_BYTES = 1024

    /**
     * `OTA_HEADER_BYTES` — bytes of the incoming image the device buffers in RAM
     * before it opens the flash slot. Everything the accept/refuse decision
     * needs is inside them, which is what lets a wrong-project or same-version
     * image be refused before anything is erased. Also the shortest thing that
     * can be an application image at all: below this, BEGIN is answered
     * `OTA_BAD_IMAGE`.
     */
    const val OTA_IMAGE_HEADER_BYTES = 288

    /**
     * A conservative *upper bound* on what an OTA slot can hold — deliberately
     * not a claim about the connected device.
     *
     * Slot size is a property of the partition table the tail was flashed with,
     * and TailFirmware ships two: `partitions.csv` (the 2 MB ESP32-C3 build)
     * gives 0xF0000 = 960 KB per slot, `partitions_4mb.csv` (the ESP32-S3 build)
     * gives 0x1F0000 = 1984 KB. The firmware sizes its own check at runtime from
     * `ota_flash_slot_size()`, and **there is no FF06 field carrying that
     * number**, so the app cannot know which table it is talking to.
     *
     * Hard-coding the smaller figure made the app refuse images an S3 tail would
     * happily take, which is the worse failure: the device is the authority here
     * and it rejects an oversized image at `SCMD_OTA_BEGIN` with a specific
     * result code (`OUT_OF_RANGE`) that the update flow already surfaces. So this
     * is set to the largest slot any shipped table defines and used only to catch
     * a file that cannot fit *any* tail before spending a transfer on it.
     *
     * Limitation to keep in mind: an image between 960 KB and this bound passes
     * the local check and may still be refused by a 2 MB device. That rejection
     * is reported, so it costs one packet — whereas the reverse mistake costs an
     * S3 owner the ability to update at all.
     */
    const val OTA_SLOT_BYTES = 1984 * 1024

    // Motion tuning bounds. Every one of these is *rejected* rather than clamped
    // by the firmware (`RESULT_OUT_OF_RANGE`), deliberately: a device that
    // silently substituted a different number would leave the app tuning against
    // a value the tail is not using. So they are mirrored here to explain the
    // limit where the user is, not to repair the value on the way out.

    /** `MOTOR_SCALE_MIN` — zero would round every velocity command to zero. */
    const val MOTOR_SCALE_MIN = 0.001f

    /** `MOTOR_SCALE_MAX` — command units per deg/s, per motor. */
    const val MOTOR_SCALE_MAX = 100.0f

    /** `GENTLE_SCALE_MIN` — below this the profile no longer overcomes stiction. */
    const val GENTLE_SCALE_MIN = 0.05f

    /** `GENTLE_SCALE_MAX` — 1.0 is full speed; above it "gentle" would raise limits. */
    const val GENTLE_SCALE_MAX = 1.0f

    /** `IMU_TAP_SENSITIVITY_MAX` — 0 is least sensitive. */
    const val TAP_SENSITIVITY_MAX = 7

    /** `IMU_TAP_QUIET_TIME_MIN_MS` — below the mechanism's ring-down, so useless. */
    const val TAP_QUIET_TIME_MIN_MS = 20

    /** `IMU_TAP_QUIET_TIME_MAX_MS` — above it a tail stops answering taps. */
    const val TAP_QUIET_TIME_MAX_MS = 2000

    /** `AXIS_MIX_ROTATION_MAX` — the mix rotation is bounded to ±180°. */
    const val AXIS_MIX_ROTATION_MAX = 180.0f

    /** `AXIS_MIX_GAIN_MIN` — bound away from zero, which would make the mix non-invertible. */
    const val AXIS_MIX_GAIN_MIN = 0.05f

    /** `AXIS_MIX_GAIN_MAX`. */
    const val AXIS_MIX_GAIN_MAX = 20.0f

    /** `ENCODER_RATE_MIN` — first-order correction decay constant, in 1/s. */
    const val ENCODER_RATE_MIN = 0.001f

    /** `ENCODER_RATE_MAX` — a one-second time constant; past it correction fights the profile. */
    const val ENCODER_RATE_MAX = 1.0f

    /** `ENCODER_REHOME_MIN_DEG` — disagreement that triggers a re-home. */
    const val ENCODER_REHOME_MIN_DEG = 1.0f

    /** `ENCODER_REHOME_MAX_DEG`. */
    const val ENCODER_REHOME_MAX_DEG = 180.0f

    // LED render frame rate (LCMD_SET_FRAME_RATE, protocol v6). The device
    // *rejects* an out-of-range rate with OUT_OF_RANGE rather than clamping it,
    // so an app that clamped silently would report a rate the tail is not using.

    /** `LED_FRAME_RATE_MIN` — below this the direct-mode stale-frame timeout is measured in frames. */
    const val LED_FRAME_RATE_MIN = 5

    /** `LED_FRAME_RATE_MAX` — above this the MCU cannot composite a non-trivial stack. */
    const val LED_FRAME_RATE_MAX = 60

    /** `LED_FRAME_RATE_DEFAULT` — what a device runs at until told otherwise. */
    const val LED_FRAME_RATE_DEFAULT = 30

    // Animated images (LED-5). The stored file layout is documented in
    // TailFirmware `main/ble/ble_protocol.h` next to LCMD_BEGIN_ANIMATION; what
    // is uploaded is the whole self-describing file, header included.

    /** `ANIMATION_MAGIC` — "TANM" little-endian, bytes 0-3 of the stored file. */
    const val ANIMATION_MAGIC = 0x4D4E4154

    /** `ANIMATION_FORMAT_VERSION` — byte 4; the device refuses any other. */
    const val ANIMATION_FORMAT_VERSION = 1

    /** `ANIMATION_HEADER_SIZE` — magic, version, geometry, duration, reserved, frame CRC. */
    const val ANIMATION_HEADER_SIZE = 16

    /** `MAX_ANIMATION_SLOTS` — stored animations, addressed 0..3. */
    const val MAX_ANIMATION_SLOTS = 4

    /** `ANIMATION_MAX_DIM` — matches the FF06 `image_max_dim` capability. */
    const val ANIMATION_MAX_DIM = 32

    /** `ANIMATION_MAX_FRAMES` — the device's per-animation frame ceiling. */
    const val ANIMATION_MAX_FRAMES = 64

    /**
     * `ANIMATION_MAX_BYTES` — the device stages the whole transfer in RAM so it
     * can be CRC-checked before anything reaches flash, and 16 KB is what it can
     * allocate without competing with the BLE stack. A BEGIN asking for more is
     * rejected, not truncated.
     */
    const val ANIMATION_MAX_BYTES = 16384

    // Parameter descriptors (FF0D). Record layout from TailFirmware
    // `main/ble/ble_protocol.h`; see [ParamDescriptorParser].

    /** `PARAM_NAME_MAX` — the fixed, NUL-padded name field inside each record. */
    const val PARAM_NAME_MAX = 12

    /** `PARAM_DESC_RECORD_SIZE` — `[id][unit][name 12][min f32][max f32][default f32]`. */
    const val PARAM_DESC_RECORD_SIZE = 1 + 1 + PARAM_NAME_MAX + 4 + 4 + 4

    /** `PARAM_DESC_HEADER_SIZE` — `[kind][id][status][count]` in front of the records. */
    const val PARAM_DESC_HEADER_SIZE = 4

    /** `PARAM_DESC_MAX_PARAMS` — the eight parameter slots FF02/FF04 report per entity. */
    const val PARAM_DESC_MAX_PARAMS = 8

    /** `PARAM_DESC_MAX_PAYLOAD` — the whole FF0D read at its longest; fits one MTU. */
    const val PARAM_DESC_MAX_PAYLOAD =
        PARAM_DESC_HEADER_SIZE + PARAM_DESC_MAX_PARAMS * PARAM_DESC_RECORD_SIZE

    /**
     * `OtaManager::CONFIRM_DWELL_US` — how long the device must hold a BLE
     * connection before it confirms a freshly booted image.
     *
     * The rollback contract in one number, and it asks something of the app.
     * A new image boots in `ESP_OTA_IMG_PENDING_VERIFY`; the firmware confirms
     * it only after every subsystem came up *and* it has held a connection
     * continuously for this long. A dropped connection restarts the clock rather
     * than accumulating, and the device never chooses to roll back — it only
     * ever fails to confirm, silently, so a user who disconnects right after the
     * reboot gets the old firmware back with nothing reporting an error.
     */
    const val OTA_CONFIRM_DWELL_MS = 10_000L
}

/** Result code reported on FF09 after every non-FFT write. */
enum class CommandResultCode(val code: Byte) {
    OK(0x00),
    BAD_LENGTH(0x01),
    UNKNOWN_CMD(0x02),
    UNKNOWN_ID(0x03),
    OUT_OF_RANGE(0x04),
    BAD_STATE(0x05),
    BUSY(0x06),

    // The OTA rejections are distinct codes rather than one BAD_STATE because
    // each has a different cause and a different thing for the user to do about
    // it. They only ever appear for the FF06 OTA commands and in the FF0E status
    // echo.
    OTA_BAD_IMAGE(0x07),
    OTA_WRONG_PROJECT(0x08),
    OTA_SAME_VERSION(0x09),
    OTA_FLASH_ERROR(0x0A),

    /**
     * The running image is itself still on probation, so a new transfer is
     * refused rather than allowed to erase the one slot rollback would fall
     * back on.
     *
     * This is what a second update in a row hits: the device confirms a freshly
     * booted image only after it has held a connection for
     * [Protocol.OTA_CONFIRM_DWELL_MS], and until then `SCMD_OTA_BEGIN` answers
     * this. Retryable, and the wait is short and known — which is exactly why
     * the firmware gave it its own code instead of a bare flash error.
     */
    OTA_VERIFY_PENDING(0x0B),

    UNKNOWN(0xFF.toByte());

    /**
     * True for a rejection the same command could succeed at if resent.
     *
     * [OTA_VERIFY_PENDING] belongs here as much as [BUSY] does: both mean "not
     * yet", not "no". The wait is longer — the confirmation dwell rather than a
     * queue slot — but the command is unchanged and will be accepted once it
     * elapses.
     */
    val isRetryable: Boolean get() = this == BUSY || this == OTA_VERIFY_PENDING

    val isSuccess: Boolean get() = this == OK

    val message: String
        get() = when (this) {
            OK -> "OK"
            BAD_LENGTH -> "Payload too short"
            UNKNOWN_CMD -> "Unknown command"
            UNKNOWN_ID -> "Unknown pattern/effect id"
            OUT_OF_RANGE -> "Index out of range"
            BAD_STATE -> "Rejected: bad state (image checksum or empty profile slot)"
            BUSY -> "Device busy — command queue full, retry"
            OTA_BAD_IMAGE -> "Not an ESP application image — damaged, or the wrong file"
            OTA_WRONG_PROJECT -> "Firmware for a different project — it is not this tail's"
            OTA_SAME_VERSION -> "That version is already running"
            OTA_FLASH_ERROR -> "Erase, write or activation failed on the device"
            OTA_VERIFY_PENDING ->
                "The tail is still confirming the firmware it just booted — stay " +
                    "connected, wait about ${Protocol.OTA_CONFIRM_DWELL_MS / 1000} s and try again"
            UNKNOWN -> "Unrecognised result code"
        }

    companion object {
        fun fromCode(code: Byte): CommandResultCode = entries.find { it.code == code } ?: UNKNOWN
    }
}

/**
 * Acknowledgement on FF09: `[char_uuid_lo][command_id][result][seq]`.
 *
 * [sequence] is a device-side counter (protocol v5), not something the app
 * supplies — this protocol's commands are variable-length, so there is nowhere
 * unambiguous to put an app token. It still distinguishes two identical
 * in-flight commands, since acknowledgements arrive in submission order, and a
 * gap in it means a notify was dropped. Null when talking to older firmware.
 */
data class CommandResult(
    val characteristicId: Byte,
    val commandId: Byte,
    val result: CommandResultCode,
    val sequence: Int? = null
) {
    val isSuccess: Boolean get() = result.isSuccess

    /** Human-readable source, e.g. "FF01". */
    val characteristicName: String
        get() = "FF%02X".format(characteristicId)
}

/** Event notified on FF07, and readable there as a recent-event ring (v5). */
enum class SystemEvent(val code: Byte) {
    TAP_BASE(0x01),
    TAP_TIP(0x02),
    CONFIG_CHANGED(0x03),

    /**
     * StallGuard tripped: the firmware dropped every motor to freewheel and
     * latched them off until explicitly re-enabled. Until the app handled this,
     * a stall looked like a tail that stopped for no stated reason — the only
     * other trace is `motors_enabled` in the FF06 read.
     */
    STALL(0x04),

    // TMC2209 driver health (MOT-3), one paired appear/clear event per fault
    // kind. The byte says *what* went wrong, not which motor — the FF07 ring
    // carries one byte per event — so per-motor detail lives in the FF0C
    // diagnostics read's `DRV_STATUS` masks. Each pair fires on the edge of the
    // OR across all four drivers: a second motor developing the same fault is
    // not a second event, and neither is one of two clearing.
    //
    // They are emitted whether or not an app is connected, which is what the
    // readable event ring exists for: dropping them left a tail that had cooked
    // itself indistinguishable from one that had simply stopped.

    /** Overtemperature *prewarning* (`otpw`): still driving, but the driver is hot. */
    DRV_OVERTEMP_WARN(0x05),

    /** The overtemperature prewarning cleared on every driver that had it. */
    DRV_OVERTEMP_WARN_CLEAR(0x06),

    /** Thermal shutdown (`ot`): that driver has switched itself off. */
    DRV_OVERTEMP(0x07),

    /** Thermal shutdown cleared; the driver is cool enough to drive again. */
    DRV_OVERTEMP_CLEAR(0x08),

    /** A coil is shorted to ground (`s2ga`/`s2gb`) — wiring or a failed driver. */
    DRV_SHORT(0x09),

    /** The short-to-ground condition cleared. */
    DRV_SHORT_CLEAR(0x0A),

    /** A coil reads as not connected (`ola`/`olb`) — an unplugged or broken motor lead. */
    DRV_OPEN_LOAD(0x0B),

    /** The open-load condition cleared; the coil is reading as connected again. */
    DRV_OPEN_LOAD_CLEAR(0x0C),

    /**
     * The low-battery policy engaged, tightened or released (SYS-1). Sent on the
     * crossing only, with a recovery margin either side of the threshold, so a
     * pack resting on one cannot flap them — which is also why the app has to
     * *remember* the last one rather than expecting a repeat.
     *
     * There is no event for an unknown level: a board with no sense line would
     * announce it once at boot and then say nothing forever.
     */
    BATTERY_LOW(0x0D),
    BATTERY_CRITICAL(0x0E),
    BATTERY_NORMAL(0x0F),

    /**
     * The behavior engine entered a new state (MOT-6). Which state, and why, are
     * in the FF02 behavior block — the event carries no payload of its own, so
     * it is a prompt to look rather than a fact in itself.
     */
    BEHAVIOR_STATE(0x10);

    /** True for the three low-battery policy crossings. */
    val isBatteryPolicy: Boolean
        get() = this == BATTERY_LOW || this == BATTERY_CRITICAL || this == BATTERY_NORMAL

    /** True for either half of a TMC2209 driver-health pair (MOT-3). */
    val isDriverHealth: Boolean
        get() = code >= DRV_OVERTEMP_WARN.code && code <= DRV_OPEN_LOAD_CLEAR.code

    /**
     * True for a driver-health event that reports a fault *appearing*. The
     * `_CLEAR` halves are recoveries, so a log that treated the pair alike would
     * count every fault twice and never show one ending.
     */
    val isDriverFault: Boolean
        get() = this == DRV_OVERTEMP_WARN || this == DRV_OVERTEMP ||
            this == DRV_SHORT || this == DRV_OPEN_LOAD

    /** One line of plain English, for an event log the user is expected to read. */
    val description: String
        get() = when (this) {
            TAP_BASE -> "Tap detected at the base"
            TAP_TIP -> "Tap detected at the tip"
            CONFIG_CHANGED -> "Configuration replaced on the device — re-read its state"
            STALL -> "A motor stalled; every motor was released and latched off"
            DRV_OVERTEMP_WARN -> "Motor driver overheating (prewarning) — still driving"
            DRV_OVERTEMP_WARN_CLEAR -> "Motor driver overheating warning cleared"
            DRV_OVERTEMP -> "Motor driver thermal shutdown — that driver is off"
            DRV_OVERTEMP_CLEAR -> "Motor driver cooled down and is driving again"
            DRV_SHORT -> "Motor coil shorted to ground — check the wiring"
            DRV_SHORT_CLEAR -> "Motor coil short cleared"
            DRV_OPEN_LOAD -> "Motor coil not connected — check the motor lead"
            DRV_OPEN_LOAD_CLEAR -> "Motor coil is connected again"
            BATTERY_LOW -> "Battery low: LEDs dimmed and motion limits reduced"
            BATTERY_CRITICAL -> "Battery critical: the tail parked and released the motors"
            BATTERY_NORMAL -> "Battery recovered; the low-power policy released"
            BEHAVIOR_STATE -> "The behavior engine entered a new state"
        }

    companion object {
        fun fromCode(code: Byte): SystemEvent? = entries.find { it.code == code }
    }
}
