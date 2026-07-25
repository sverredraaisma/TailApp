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
     * The largest ATT MTU worth planning for. The firmware requests a preferred
     * MTU of 512 (`ble_att_set_preferred_mtu`), which negotiates up to 517 once
     * the 3-byte ATT opcode+handle overhead is added back on — the same ceiling
     * [com.tailapp.repository.DeviceRepository] requests on connect. Used as the
     * upper bound for [DirectPixelFrame]'s packet-budget sanity check, since a
     * single FF0A write can never carry more LEDs than this allows regardless of
     * what the live connection actually negotiated.
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
     * Bytes an OTA slot holds (`ota_0`/`ota_1` in the device's partition table).
     * An image larger than this is refused at BEGIN with `OUT_OF_RANGE`, and
     * enlarging a slot is a cable-only change, so this is a hard ceiling rather
     * than a current figure.
     */
    const val OTA_SLOT_BYTES = 960 * 1024

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

    // The four OTA rejections are distinct codes rather than one BAD_STATE
    // because each has a different cause and a different thing for the user to
    // do about it. They only ever appear for the FF06 OTA commands and in the
    // FF0E status echo.
    OTA_BAD_IMAGE(0x07),
    OTA_WRONG_PROJECT(0x08),
    OTA_SAME_VERSION(0x09),
    OTA_FLASH_ERROR(0x0A),

    UNKNOWN(0xFF.toByte());

    /** True for a rejection the same command could succeed at if resent. */
    val isRetryable: Boolean get() = this == BUSY

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

    companion object {
        fun fromCode(code: Byte): SystemEvent? = entries.find { it.code == code }
    }
}
