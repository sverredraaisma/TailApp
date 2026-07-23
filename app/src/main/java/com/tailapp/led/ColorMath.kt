package com.tailapp.led

import com.tailapp.model.BlendMode

/**
 * Colour conversion and layer-blend math, transcribed from `main/led/color.h`.
 *
 * The firmware's `hsv_to_rgb` and blend helpers operate on `uint8_t`/`uint16_t`
 * fields and rely on C++'s truncating integer division. None of the
 * intermediate products in either function overflow 16 bits (the largest is
 * `255 * 255 = 65025`), so plain Kotlin `Int` arithmetic reproduces the same
 * truncation without needing to mask/cast anywhere - there is nowhere the two
 * implementations can silently diverge on rounding.
 *
 * Colours are packed as `0xRRGGBB`, matching [PixelBuffer.packed]/`setPacked`.
 */
object ColorMath {

    /**
     * Mirrors `hsv_to_rgb` (`color.h`). Callers are expected to pass `h` in
     * `0..359` and `s`/`v` in `0..255` - the same ranges the firmware's `HSV`
     * struct fields are typed to (`uint16_t h`, `uint8_t s`/`v`). Out-of-range
     * input isn't clamped here, exactly as the firmware doesn't clamp inside
     * `hsv_to_rgb` either (an `h >= 360` just falls through arithmetically,
     * same as it would in C++).
     */
    fun hsvToRgb(h: Int, s: Int, v: Int): Int {
        if (s == 0) return packRgb(v, v, v)

        val region = h / 60
        val remainder = (h - region * 60) * 255 / 60

        val p = v * (255 - s) / 255
        val q = v * (255 - (s * remainder / 255)) / 255
        val t = v * (255 - (s * (255 - remainder) / 255)) / 255

        return when (region) {
            0 -> packRgb(v, t, p)
            1 -> packRgb(q, v, p)
            2 -> packRgb(p, v, t)
            3 -> packRgb(p, q, v)
            4 -> packRgb(t, p, v)
            else -> packRgb(v, p, q) // region 5 (and any out-of-range input), same as the firmware's `default:`
        }
    }

    /** Dispatches to the blend function named by [mode]. Mirrors `LayerCompositor::blend`. */
    fun blend(base: Int, overlay: Int, mode: BlendMode): Int = when (mode) {
        BlendMode.MULTIPLY -> multiply(base, overlay)
        BlendMode.ADD -> add(base, overlay)
        BlendMode.SUBTRACT -> subtract(base, overlay)
        BlendMode.MIN -> min(base, overlay)
        BlendMode.MAX -> max(base, overlay)
        BlendMode.OVERWRITE -> overwrite(base, overlay)
    }

    /** `base * overlay / 255` per channel. Mirrors `rgb_multiply`. */
    fun multiply(base: Int, overlay: Int): Int = combine(base, overlay) { b, o -> b * o / 255 }

    /** `min(base + overlay, 255)` per channel. Mirrors `rgb_add`. */
    fun add(base: Int, overlay: Int): Int = combine(base, overlay) { b, o -> if (b + o > 255) 255 else b + o }

    /** `max(base - overlay, 0)` per channel. Mirrors `rgb_subtract`. */
    fun subtract(base: Int, overlay: Int): Int = combine(base, overlay) { b, o -> if (b - o < 0) 0 else b - o }

    /** `min(base, overlay)` per channel. Mirrors `rgb_min`. */
    fun min(base: Int, overlay: Int): Int = combine(base, overlay) { b, o -> if (b < o) b else o }

    /** `max(base, overlay)` per channel. Mirrors `rgb_max`. */
    fun max(base: Int, overlay: Int): Int = combine(base, overlay) { b, o -> if (b > o) b else o }

    /**
     * `overlay == black ? base : overlay`. Mirrors `rgb_overwrite` - note this is
     * a whole-pixel comparison, not per-channel: an overlay of `(0, 0, 1)` is
     * *not* black and replaces all three base channels, it doesn't merely
     * overwrite the blue channel.
     */
    fun overwrite(base: Int, overlay: Int): Int = if (overlay == 0x000000) base else overlay

    private inline fun combine(base: Int, overlay: Int, op: (Int, Int) -> Int): Int {
        val r = op((base shr 16) and 0xFF, (overlay shr 16) and 0xFF)
        val g = op((base shr 8) and 0xFF, (overlay shr 8) and 0xFF)
        val b = op(base and 0xFF, overlay and 0xFF)
        return packRgb(r, g, b)
    }

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
