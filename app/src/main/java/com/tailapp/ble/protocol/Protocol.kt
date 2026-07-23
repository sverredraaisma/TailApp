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
     */
    const val SUPPORTED_PROTOCOL_VERSION = 3

    /** Profile slots the firmware exposes on FF08 (`MAX_PROFILE_SLOTS`). */
    const val MAX_PROFILE_SLOTS = 4

    /** Longest profile name the firmware stores (`MAX_PROFILE_NAME_LEN`), in UTF-8 bytes. */
    const val MAX_PROFILE_NAME_LEN = 16

    /** Staging buffer size for image uploads (`IMAGE_UPLOAD_MAX`) — 32x32 RGB. */
    const val IMAGE_UPLOAD_MAX = 3072

    /** `effect_id` the firmware writes into a removed layer slot. */
    const val EMPTY_EFFECT_ID: Byte = 0xFF.toByte()

    /** FF02 motion-state payload size (`MOTION_STATE_SIZE`). */
    const val MOTION_STATE_SIZE = 77

    /** Bytes per layer entry in the FF04 LED-state payload. */
    const val LED_LAYER_SIZE = 39

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
}

/** Result code reported on FF09 after every non-FFT write. */
enum class CommandResultCode(val code: Byte) {
    OK(0x00),
    BAD_LENGTH(0x01),
    UNKNOWN_CMD(0x02),
    UNKNOWN_ID(0x03),
    OUT_OF_RANGE(0x04),
    BAD_STATE(0x05),
    UNKNOWN(0xFF.toByte());

    val isSuccess: Boolean get() = this == OK

    val message: String
        get() = when (this) {
            OK -> "OK"
            BAD_LENGTH -> "Payload too short"
            UNKNOWN_CMD -> "Unknown command"
            UNKNOWN_ID -> "Unknown pattern/effect id"
            OUT_OF_RANGE -> "Index out of range"
            BAD_STATE -> "Rejected: bad state (image checksum or empty profile slot)"
            UNKNOWN -> "Unrecognised result code"
        }

    companion object {
        fun fromCode(code: Byte): CommandResultCode = entries.find { it.code == code } ?: UNKNOWN
    }
}

/** Acknowledgement notified on FF09: `[char_uuid_lo][command_id][result]`. */
data class CommandResult(
    val characteristicId: Byte,
    val commandId: Byte,
    val result: CommandResultCode
) {
    val isSuccess: Boolean get() = result.isSuccess

    /** Human-readable source, e.g. "FF01". */
    val characteristicName: String
        get() = "FF%02X".format(characteristicId)
}

/** Event notified on FF07. */
enum class SystemEvent(val code: Byte) {
    TAP_BASE(0x01),
    TAP_TIP(0x02),
    CONFIG_CHANGED(0x03);

    companion object {
        fun fromCode(code: Byte): SystemEvent? = entries.find { it.code == code }
    }
}
