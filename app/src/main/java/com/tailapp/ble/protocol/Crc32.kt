package com.tailapp.ble.protocol

import java.util.zip.CRC32

/**
 * Standard CRC-32 (IEEE 802.3 / zlib: reflected polynomial `0xEDB88320`, init
 * `0xFFFFFFFF`, final XOR), used to arm the image-upload integrity check on
 * FF03 `0x08` BEGIN.
 *
 * Protocol v3 corrected the firmware to this polynomial. Firmware ≤ v2 used a
 * non-standard `0xEDB88420` variant, so a v3 app talking to a v2 device (or
 * vice versa) has every BEGIN-armed upload rejected with `BAD_STATE` — which is
 * why [Protocol.SUPPORTED_PROTOCOL_VERSION] gates the compatibility banner.
 *
 * Returned as a signed [Int] holding the raw 32 bits, ready to be written
 * little-endian into the BEGIN command.
 */
object Crc32 {

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range $offset..${offset + length} outside buffer of ${data.size}"
        }
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }
}
