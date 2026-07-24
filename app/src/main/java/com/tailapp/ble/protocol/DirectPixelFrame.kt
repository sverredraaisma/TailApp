package com.tailapp.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FF0A LED direct pixel stream packet builder.
 *
 * Wire format (TailFirmware `docs/ble-protocol.md`, "FF0A: LED Direct Stream"):
 * `[start_index: u16 LE] [r,g,b ...]`. No command_id, no FF09 ACK — like FF05
 * this is fire-and-forget for rate reasons, so a malformed packet built here
 * would fail silently on the wire rather than surface as a rejected command.
 * All validation therefore happens locally, before the write ever goes out.
 *
 * The firmware writes straight into its strip buffer at `start_index` and
 * stops the moment an index reaches the configured LED count
 * (`LedMatrix::write_pixels` `break`s rather than wrapping or clamping into
 * range) — so a packet that runs past the end of the strip has its tail
 * silently dropped, not wrapped around to LED 0.
 */
object DirectPixelFrame {

    /** Bytes consumed by the packet header (`start_index` u16 LE). */
    private const val HEADER_SIZE = 2

    /** Bytes consumed by the ATT opcode + attribute handle on every GATT write. */
    private const val ATT_HEADER_SIZE = 3

    /**
     * Builds one FF0A packet: `[start_index u16 LE][rgb bytes]`.
     *
     * @param startIndex index of the first LED this packet writes. Must be in
     *   `0..65535` — the wire field is `u16 LE`.
     * @param rgb the source pixel buffer, `r,g,b` per LED — e.g.
     *   [com.tailapp.led.PixelBuffer.bytes], which is already in this exact layout.
     * @param offset byte offset into [rgb] to start copying from.
     * @param ledCount number of LEDs (not bytes) to copy from [rgb] starting at [offset].
     * @throws IllegalArgumentException if [startIndex] is outside `0..65535`, if
     *   `[offset, offset + ledCount * 3)` runs off the end of [rgb], or if
     *   [ledCount] exceeds what a single packet could ever carry — even at the
     *   largest MTU this app will ever negotiate (see [maxLedsPerPacket]).
     *   Callers that need to send more than that must split across several
     *   packets with increasing [startIndex] values (see
     *   `DeviceRepository.streamDirectFrame`).
     */
    fun build(startIndex: Int, rgb: ByteArray, offset: Int, ledCount: Int): ByteArray {
        // The wire field is u16 LE; an out-of-range startIndex would silently
        // wrap through toShort() and land the pixels on the wrong LED. FF0A is
        // unacknowledged, so that failure is invisible — reject it here instead.
        require(startIndex in 0..0xFFFF) { "startIndex must be in 0..65535: $startIndex" }
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(ledCount >= 0) { "ledCount must not be negative: $ledCount" }

        val absoluteMaxLeds = maxLedsPerPacket(Protocol.MAX_ATT_MTU)
        require(ledCount <= absoluteMaxLeds) {
            "ledCount $ledCount exceeds the packet budget of $absoluteMaxLeds LEDs " +
                "even at the largest MTU this app negotiates (${Protocol.MAX_ATT_MTU}); " +
                "split the frame into multiple packets instead"
        }

        val byteCount = ledCount * 3
        require(offset + byteCount <= rgb.size) {
            "rgb range [$offset, ${offset + byteCount}) runs off the end of a ${rgb.size}-byte buffer"
        }

        val buf = ByteBuffer.allocate(HEADER_SIZE + byteCount).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(startIndex.toShort())
        buf.put(rgb, offset, byteCount)
        return buf.array()
    }

    /**
     * Max LEDs a single FF0A write can carry at a negotiated ATT [mtu], floored,
     * never less than 1.
     *
     * The exact figure is `((mtu - 3) - 2) / 3`: the ATT write payload is
     * `mtu - 3` bytes (3-byte opcode + attribute-handle header), and 2 of those
     * bytes are this packet's own `start_index` before any RGB data starts.
     *
     * The protocol doc's rule of thumb, `(MTU-3)/3`, skips that second
     * subtraction. It happens to floor to the same value at MTU 23 and 185
     * (the header is absorbed by the remainder), but at MTU 247 and 517 it
     * overstates capacity by exactly one LED — building a packet 3 bytes
     * larger than the ATT payload the negotiated MTU actually has room for.
     * This function computes the exact value instead of the approximation.
     */
    fun maxLedsPerPacket(mtu: Int): Int {
        val payloadForRgb = (mtu - ATT_HEADER_SIZE - HEADER_SIZE) / 3
        return payloadForRgb.coerceAtLeast(1)
    }
}
