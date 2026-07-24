package com.tailapp.led

/**
 * Mirror of the firmware's `PaletteTable` (`main/led/palette.h` / `.cpp`).
 *
 * The firmware's ambient effects sample a palette rather than carrying their
 * own colours, so the preview cannot reproduce any of them without the same
 * ramps and the same interpolation — including the integer arithmetic, since a
 * float lerp would land a shade or two off and the preview is supposed to show
 * what the device shows.
 *
 * Ids are persisted inside saved layer parameters, so the built-in order is
 * fixed. A user palette uploaded to the device is not mirrored here: the app
 * would have to be told about it separately, and until it is, sampling an
 * unknown id yields black — visibly wrong, rather than quietly substituting
 * another ramp and making a typo'd id look like a design choice.
 */
object Palettes {

    /** Stops are `position` (0-255) to packed `0xRRGGBB`. Non-decreasing. */
    private class Palette(val positions: IntArray, val colors: IntArray)

    const val FIRE = 0
    const val ICE = 1
    const val RAINBOW = 2
    const val SUNSET = 3
    const val FOREST = 4
    const val MONO = 5

    /** How many built-ins exist; ids at or above this are user slots. */
    const val BUILTIN_COUNT = 6

    private val builtins = arrayOf(
        // Fire: black through red and orange to a white-hot tip.
        Palette(intArrayOf(0, 96, 176, 255), intArrayOf(0x000000, 0xA01400, 0xFF7800, 0xFFF0B4)),
        // Ice: deep blue through cyan to white.
        Palette(intArrayOf(0, 96, 190, 255), intArrayOf(0x000028, 0x003CB4, 0x00C8E6, 0xE6FFFF)),
        // Rainbow: the full hue circle as stops, so it interpolates in RGB the
        // way every other palette does rather than being a special case.
        Palette(
            intArrayOf(0, 42, 85, 128, 170, 213, 255),
            intArrayOf(0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000)
        ),
        // Sunset: warm orange into deep violet.
        Palette(intArrayOf(0, 90, 180, 255), intArrayOf(0xFF8C00, 0xFF3C3C, 0x8C1478, 0x1E003C)),
        // Forest: dark green through leaf to a pale highlight.
        Palette(intArrayOf(0, 100, 190, 255), intArrayOf(0x001400, 0x146414, 0x50BE3C, 0xC8FFB4)),
        // Mono: black to white, for effects used as masks.
        Palette(intArrayOf(0, 255), intArrayOf(0x000000, 0xFFFFFF))
    )

    /**
     * Samples palette [id] at [t] (0-255), matching `PaletteTable::sample`
     * including its integer interpolation and its clamp-rather-than-wrap edges.
     *
     * A ramp is not a cycle: an effect that wants one says so by making its
     * first and last stop the same colour, as Rainbow does.
     */
    fun sample(id: Int, t: Int): Int {
        val p = builtins.getOrNull(id) ?: return 0x000000
        val pos = t.coerceIn(0, 255)
        val n = p.positions.size
        if (n == 1) return p.colors[0]
        if (pos <= p.positions[0]) return p.colors[0]
        if (pos >= p.positions[n - 1]) return p.colors[n - 1]

        for (i in 1 until n) {
            val hi = p.positions[i]
            if (pos > hi) continue
            val lo = p.positions[i - 1]
            val span = hi - lo
            if (span == 0) return p.colors[i]
            // Integer local position, exactly as the firmware computes it: a
            // float here would round differently and drift the preview.
            val local = ((pos - lo) * 255) / span
            return lerp(p.colors[i - 1], p.colors[i], local)
        }
        return p.colors[n - 1]
    }

    /** `(a * (255 - t) + b * t) / 255` per channel — the firmware's `lerp`. */
    private fun lerp(a: Int, b: Int, t: Int): Int {
        val inv = 255 - t
        val r = (((a shr 16) and 0xFF) * inv + ((b shr 16) and 0xFF) * t) / 255
        val g = (((a shr 8) and 0xFF) * inv + ((b shr 8) and 0xFF) * t) / 255
        val bl = ((a and 0xFF) * inv + (b and 0xFF) * t) / 255
        return (r shl 16) or (g shl 8) or bl
    }
}
