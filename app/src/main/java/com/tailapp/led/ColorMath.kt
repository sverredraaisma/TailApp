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
    fun blend(base: Int, overlay: Int, mode: BlendMode): Int = blend(base, overlay, mode, 255)

    /**
     * Blends with a per-layer opacity, mirroring `LayerCompositor::blend`.
     *
     * Opacity mixes the blended *result* back toward the base rather than
     * scaling the overlay first, so a half-opacity Add is a weaker glow rather
     * than a different computation — matching the firmware exactly.
     */
    fun blend(base: Int, overlay: Int, mode: BlendMode, opacity: Int): Int {
        if (mode == BlendMode.NORMAL) return normal(base, overlay, opacity)

        val blended = when (mode) {
            BlendMode.MULTIPLY -> multiply(base, overlay)
            BlendMode.ADD -> add(base, overlay)
            BlendMode.SUBTRACT -> subtract(base, overlay)
            BlendMode.MIN -> min(base, overlay)
            BlendMode.MAX -> max(base, overlay)
            BlendMode.OVERWRITE -> overwrite(base, overlay)
            BlendMode.NORMAL -> overlay // unreachable; handled above
        }
        if (opacity >= 255) return blended
        return normal(base, blended, opacity)
    }

    /**
     * Alpha blend, mirroring `rgb_normal`. Unlike [overwrite] this treats black
     * as a colour, which is what lets a layer darken the stack below it.
     */
    fun normal(base: Int, overlay: Int, alpha: Int): Int {
        if (alpha >= 255) return overlay
        if (alpha <= 0) return base
        val inv = 255 - alpha
        return combine(base, overlay) { b, o -> (o * alpha + b * inv) / 255 }
    }

    /** `c * factor / 255` per channel. Mirrors `rgb_scale`. */
    fun scale(colour: Int, factor: Int): Int {
        if (factor >= 255) return colour
        return combine(colour, colour) { c, _ -> c * factor / 255 }
    }

    /**
     * Perceptual gamma correction, mirroring `rgb_gamma` and the firmware's
     * `GAMMA8` table.
     *
     * The firmware applies this on the way to the strip, so the preview has to
     * as well — a preview that skips it shows a picture the device will never
     * display, which is exactly the kind of quiet lie this whole port exists to
     * prevent.
     */
    fun gamma(colour: Int): Int = combine(colour, colour) { c, _ -> GAMMA8[c] }

    /**
     * `round((i / 255)^2.6 * 255)`, the same table the firmware carries.
     *
     * Computed once here rather than transcribed: the closed form is exact and
     * a 256-entry literal would be 256 chances to typo a value that nothing
     * would notice until the colours were subtly wrong on hardware.
     */
    val GAMMA8: IntArray = IntArray(256) { i ->
        Math.round(Math.pow(i / 255.0, 2.6) * 255.0).toInt()
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
