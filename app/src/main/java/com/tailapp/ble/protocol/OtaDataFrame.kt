package com.tailapp.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FF0E image-data packet builder: `[offset u32 LE][image bytes]`.
 *
 * No command id and no FF09 acknowledgement — like FF05 and FF0A this is
 * write-without-response. Flow control is the offset echo instead
 * ([OtaStatusParser]): the device discards any chunk that does not start
 * exactly at the offset it last echoed and answers with a resume point.
 */
object OtaDataFrame {

    /** `OTA_DATA_HEADER_SIZE` — the u32 offset in front of the payload. */
    private const val HEADER_SIZE = 4

    /** Bytes consumed by the ATT opcode + attribute handle on every GATT write. */
    private const val ATT_HEADER_SIZE = 3

    /**
     * Builds one FF0E packet carrying `[from, from + length)` of [image] at
     * [offset] within the image.
     *
     * [offset] and [from] are the same number in every use this app has — the
     * app streams an image it holds whole — but they are kept apart because the
     * offset is what the *device* matches against, and conflating them is how a
     * resume that restarts mid-buffer silently writes the wrong bytes.
     */
    fun build(offset: Int, image: ByteArray, from: Int, length: Int): ByteArray {
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(length > 0) { "a packet with no image bytes advances nothing: $length" }
        require(from >= 0 && from + length <= image.size) {
            "image range [$from, ${from + length}) runs off the end of a ${image.size}-byte image"
        }
        require(length <= maxPayload(Protocol.MAX_ATT_MTU)) {
            "$length bytes exceeds the packet budget at the largest MTU this app " +
                "negotiates (${Protocol.MAX_ATT_MTU}); split the image into more packets"
        }

        val buf = ByteBuffer.allocate(HEADER_SIZE + length).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(offset)
        buf.put(image, from, length)
        return buf.array()
    }

    /**
     * Image bytes a single FF0E write can carry at a negotiated ATT [mtu]:
     * `mtu - 3 - 4`. 240 at the typical Android default of 247, 510 at the 517
     * this device prefers.
     */
    fun maxPayload(mtu: Int): Int = (mtu - ATT_HEADER_SIZE - HEADER_SIZE).coerceAtLeast(1)
}
