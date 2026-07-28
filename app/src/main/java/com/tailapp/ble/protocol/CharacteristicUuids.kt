package com.tailapp.ble.protocol

import java.util.UUID

object CharacteristicUuids {
    val SERVICE: UUID         = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
    val MOTION_CMD: UUID      = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
    val MOTION_STATE: UUID    = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
    val LED_CMD: UUID         = UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")
    val LED_STATE: UUID       = UUID.fromString("0000FF04-0000-1000-8000-00805F9B34FB")
    val FFT_STREAM: UUID      = UUID.fromString("0000FF05-0000-1000-8000-00805F9B34FB")
    val SYSTEM_CONFIG: UUID   = UUID.fromString("0000FF06-0000-1000-8000-00805F9B34FB")
    val SYSTEM_EVENTS: UUID   = UUID.fromString("0000FF07-0000-1000-8000-00805F9B34FB")
    val PROFILE_MGMT: UUID    = UUID.fromString("0000FF08-0000-1000-8000-00805F9B34FB")
    val CMD_RESULT: UUID      = UUID.fromString("0000FF09-0000-1000-8000-00805F9B34FB")
    val LED_DIRECT: UUID      = UUID.fromString("0000FF0A-0000-1000-8000-00805F9B34FB")

    /**
     * Live motion targets, write-without-response (protocol v5).
     *
     * Its own characteristic rather than an FF01 command for the same reason
     * [LED_DIRECT] has one: at 20-50 Hz it would otherwise fill the device's
     * command queue and produce an acknowledgement per frame nobody reads.
     */
    val MOTION_TARGET: UUID   = UUID.fromString("0000FF0B-0000-1000-8000-00805F9B34FB")

    /**
     * Diagnostics snapshot, read + notify (SYS-3).
     *
     * Readable with no prior write and no handshake: a support screen exists for a
     * device that is already misbehaving, and a handshake is one more thing that
     * can be the thing that is broken. The notify carries the same payload once a
     * second — uptime moves every second, so the subscription is itself the rate
     * control and there is no unchanged snapshot to suppress.
     */
    val DIAGNOSTICS: UUID     = UUID.fromString("0000FF0C-0000-1000-8000-00805F9B34FB")

    /**
     * Parameter descriptors, read + notify — one pattern or effect at a time.
     *
     * Its own characteristic rather than more bytes on [SYSTEM_CONFIG] because
     * the full set is several kilobytes, past both the FF06 buffer and any single
     * MTU. Which entity it publishes is chosen by writing
     * [SystemCommands.selectDescriptors] to FF06; that keeps every read here a
     * fixed, single-packet payload. Parsed by [ParamDescriptorParser].
     */
    val PARAM_DESC: UUID      = UUID.fromString("0000FF0D-0000-1000-8000-00805F9B34FB")

    /**
     * OTA firmware image in, offset echo out (SYS-2).
     *
     * Both directions on one characteristic: write-without-response for the
     * image bytes, read + notify for the `accepted` echo that is the transfer's
     * only flow control. Control — begin, finalize, abort — is on
     * [SYSTEM_CONFIG]; FF0E carries nothing but bytes and offsets.
     */
    val OTA_DATA: UUID        = UUID.fromString("0000FF0E-0000-1000-8000-00805F9B34FB")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /**
     * Standard SIG services the device publishes alongside FF00.
     *
     * Deliberately the standard UUIDs and not tail-specific ones: a phone's
     * settings screen, a smartwatch or any generic BLE tool already knows how to
     * read a battery level from 0x2A19, and none of them will ever learn a
     * private characteristic.
     */
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val BATTERY_LEVEL: UUID   = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    val DIS_MANUFACTURER: UUID    = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")
    val DIS_MODEL_NUMBER: UUID    = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB")
    val DIS_FIRMWARE_REV: UUID    = UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB")
    val DIS_HARDWARE_REV: UUID    = UUID.fromString("00002A27-0000-1000-8000-00805F9B34FB")

    /** Low byte of a characteristic UUID — how FF09 identifies the written characteristic. */
    fun shortId(uuid: UUID): Byte = ((uuid.mostSignificantBits shr 32) and 0xFF).toByte()
}
