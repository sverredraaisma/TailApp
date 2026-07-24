package com.tailapp.ble.protocol

import com.tailapp.model.FirmwareVersion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FF06 OTA control commands (SYS-2). The image itself rides FF0E; see
 * [OtaDataFrame].
 *
 * The same BEGIN → chunks → FINALIZE shape as an FF03 image upload, and for the
 * same reason — that flow already proved out chunking and an armed length/CRC
 * check. What is different is that nothing between BEGIN and FINALIZE is
 * acknowledged: a firmware image is three orders of magnitude larger than a
 * 32x32 bitmap, and an ACK per chunk would be thousands of round trips.
 */
object OtaCommands {

    /**
     * `0x08` BEGIN — arms a transfer with the image's length, CRC-32 and the
     * version the app claims it is about to send.
     *
     * The version is a *claim*, checked before the slot is erased so that
     * reinstalling the running build costs one packet instead of a whole
     * transfer. The image's own app descriptor is checked again once its header
     * arrives, which is what catches a claim that was wrong — so a wrong
     * declaration buys 288 bytes, not an install.
     *
     * Nothing is erased by this command; a rejected update leaves the previously
     * installed image in the other slot intact.
     */
    fun beginUpdate(totalLength: Int, crc32: Int, version: FirmwareVersion): ByteArray {
        require(totalLength > 0) { "totalLength must be positive: $totalLength" }
        require(version.major in 0..255 && version.minor in 0..255 && version.patch in 0..255) {
            "version fields are u8 on the wire: $version"
        }
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x08)
            .putInt(totalLength)
            .putInt(crc32)
            .put(version.major.toByte())
            .put(version.minor.toByte())
            .put(version.patch.toByte())
            .array()
    }

    /**
     * `0x09` FINALIZE — verifies the length and CRC, runs the bootloader's own
     * image validation and points the boot partition at the new slot.
     *
     * `BAD_STATE` covers two outcomes that behave differently: a short transfer
     * stays armed and resumable (the echo names the offset to carry on from),
     * while a CRC mismatch destroys it. The FF0E status is what tells them
     * apart — `RECEIVING` versus `ERROR`.
     */
    fun finalizeUpdate(): ByteArray = byteArrayOf(0x09)

    /**
     * `0x0A` ABORT — discards an in-flight transfer, and after a finalize points
     * the bootloader back at the running image, so an update can be called off
     * right up to the reboot. Always answered `OK`, including when nothing is
     * armed.
     */
    fun abortUpdate(): ByteArray = byteArrayOf(0x0A)
}
