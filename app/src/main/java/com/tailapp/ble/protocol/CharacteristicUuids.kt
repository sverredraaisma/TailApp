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
