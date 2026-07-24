package com.tailapp.composer

import com.tailapp.led.ColorMath

/**
 * Small packed-`0xRRGGBB` helpers shared by the effect library.
 *
 * Deliberately separate from [com.tailapp.led.ColorMath]: that class is a
 * transcription of the firmware's `color.h` and must stay one, integer
 * truncation included, because the live preview's fidelity depends on it. These
 * are app-side float conveniences with no firmware counterpart, so mixing them
 * into that file would blur the line the LED port depends on.
 */
internal object EffectColors {

    fun channel(colour: Int, shift: Int): Int = (colour shr shift) and 0xFF

    fun red(colour: Int): Int = channel(colour, 16)
    fun green(colour: Int): Int = channel(colour, 8)
    fun blue(colour: Int): Int = channel(colour, 0)

    fun pack(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Multiplies every channel by [factor], clamping. */
    fun scale(colour: Int, factor: Float): Int {
        if (factor <= 0f) return 0
        return pack(
            (red(colour) * factor).toInt(),
            (green(colour) * factor).toInt(),
            (blue(colour) * factor).toInt()
        )
    }

    /** Linear per-channel interpolation, `t` clamped to `0..1`. */
    fun lerp(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return pack(
            (red(from) + (red(to) - red(from)) * f).toInt(),
            (green(from) + (green(to) - green(from)) * f).toInt(),
            (blue(from) + (blue(to) - blue(from)) * f).toInt()
        )
    }

    /**
     * A colour at [hueDegrees] (wrapped into `0..359`), via the firmware's own
     * integer HSV — so a hue-cycling effect and a device-side rainbow land on the
     * same RGB for the same hue.
     */
    fun fromHue(hueDegrees: Float, saturation: Float = 1f, value: Float = 1f): Int {
        val h = ((hueDegrees % 360f) + 360f) % 360f
        return ColorMath.hsvToRgb(
            h.toInt().coerceIn(0, 359),
            (saturation * 255f).toInt().coerceIn(0, 255),
            (value * 255f).toInt().coerceIn(0, 255)
        )
    }

    /** Neutral grey at [level] (`0..1`) — what a modulator layer emits. */
    fun gray(level: Float): Int {
        val v = (level.coerceIn(0f, 1f) * 255f).toInt()
        return pack(v, v, v)
    }
}
