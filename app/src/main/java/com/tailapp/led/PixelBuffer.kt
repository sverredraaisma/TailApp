package com.tailapp.led

/**
 * An RGB frame for the whole strip, stored exactly as FF0A wants it: three bytes
 * per LED, red first, in strip index order. Rendering into this layout means a
 * frame can be streamed to the device without a copy or a conversion pass.
 *
 * Mirrors the firmware's `std::vector<RGB>` render buffer (`led_matrix.cpp`).
 */
class PixelBuffer(val ledCount: Int) {
    init {
        require(ledCount >= 0) { "ledCount must not be negative" }
    }

    /** Packed `r,g,b` triplets, `ledCount * 3` bytes. */
    val bytes: ByteArray = ByteArray(ledCount * 3)

    fun red(index: Int): Int = bytes[index * 3].toInt() and 0xFF
    fun green(index: Int): Int = bytes[index * 3 + 1].toInt() and 0xFF
    fun blue(index: Int): Int = bytes[index * 3 + 2].toInt() and 0xFF

    /** Channels are clamped to `0..255`, matching the firmware's `uint8_t` casts. */
    fun set(index: Int, r: Int, g: Int, b: Int) {
        if (index < 0 || index >= ledCount) return
        val o = index * 3
        bytes[o] = r.coerceIn(0, 255).toByte()
        bytes[o + 1] = g.coerceIn(0, 255).toByte()
        bytes[o + 2] = b.coerceIn(0, 255).toByte()
    }

    /** Sets a pixel from a packed `0xRRGGBB` value. */
    fun setPacked(index: Int, rgb: Int) =
        set(index, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    /** Returns the pixel as packed `0xRRGGBB`. */
    fun packed(index: Int): Int =
        (red(index) shl 16) or (green(index) shl 8) or blue(index)

    fun clear() = bytes.fill(0)

    fun fill(r: Int, g: Int, b: Int) {
        for (i in 0 until ledCount) set(i, r, g, b)
    }

    /** Copies [other]'s pixels into this buffer. Sizes must match. */
    fun copyFrom(other: PixelBuffer) {
        require(other.ledCount == ledCount) {
            "size mismatch: $ledCount vs ${other.ledCount}"
        }
        other.bytes.copyInto(bytes)
    }

    fun copy(): PixelBuffer = PixelBuffer(ledCount).also { it.copyFrom(this) }
}
