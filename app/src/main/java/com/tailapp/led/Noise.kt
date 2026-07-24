package com.tailapp.led

/**
 * Mirror of the firmware's `noise::` helpers (`main/led/noise.h`).
 *
 * The value has to come from a *hash of the coordinate and a tick*, never from
 * a stateful RNG: the same pixel on the same tick always gets the same answer,
 * so a sparkle stays put for as long as it should instead of turning into
 * television static — and so a test can assert a pixel rather than a shape.
 *
 * The firmware works in `uint32_t`; Kotlin has no unsigned `Int` arithmetic
 * built in, but it doesn't need one here. Two's-complement multiply and xor
 * produce identical bit patterns on `Int`, and the only place signedness would
 * show is the right shift — hence `ushr` throughout, matching C's `>>` on an
 * unsigned operand. `hash01`'s `ushr 8` also guarantees a non-negative
 * result below 2^24, which converts to `Float` exactly, so the division lands
 * on the same value the firmware computes.
 *
 * `noise::hash8` and `noise::Lcg` are deliberately not mirrored: no ported
 * effect calls them, and an unexercised copy of firmware arithmetic is exactly
 * the kind of thing that silently drifts.
 */
object Noise {

    /** Deterministic 32-bit hash. Mirrors `noise::hash`. */
    fun hash(a: Int, b: Int): Int {
        // `*` binds tighter than `^` in C++, so the firmware's unparenthesised
        // `a * K1 ^ b * K2` multiplies both operands before the xor.
        var h = (a * MIX_A) xor (b * MIX_B)
        h = h xor (h ushr 15)
        h *= MIX_C
        h = h xor (h ushr 13)
        return h
    }

    /** Hash mapped to `0.0..1.0`. Mirrors `noise::hash01`. */
    fun hash01(a: Int, b: Int): Float = (hash(a, b) ushr 8).toFloat() / 16777216.0f

    /**
     * Smooth 1-D value noise, mirroring `noise::value1d`.
     *
     * The lattice index is `(int32_t)(x < 0 ? x - 1.0f : x)`, which is *not*
     * `floor` at exact negative integers (`-1.0` lands on lattice cell `-2`,
     * not `-1`). Reproduced as written rather than "fixed": the field an effect
     * samples has to be the same field the device samples.
     */
    fun value1d(x: Float, seed: Int): Float {
        val i = (if (x < 0f) x - 1.0f else x).toInt()
        val f = x - i.toFloat()
        val s = f * f * (3.0f - 2.0f * f)
        val a = hash01(i, seed)
        val b = hash01(i + 1, seed)
        return a + (b - a) * s
    }

    /** Smooth 2-D value noise, bilinear over the same lattice. Mirrors `noise::value2d`. */
    fun value2d(x: Float, y: Float, seed: Int): Float {
        val xi = (if (x < 0f) x - 1.0f else x).toInt()
        val yi = (if (y < 0f) y - 1.0f else y).toInt()
        val fx = x - xi.toFloat()
        val fy = y - yi.toFloat()
        val sx = fx * fx * (3.0f - 2.0f * fx)
        val sy = fy * fy * (3.0f - 2.0f * fy)

        val a = hash01(hash(xi, yi), seed)
        val b = hash01(hash(xi + 1, yi), seed)
        val c = hash01(hash(xi, yi + 1), seed)
        val d = hash01(hash(xi + 1, yi + 1), seed)

        val top = a + (b - a) * sx
        val bottom = c + (d - c) * sx
        return top + (bottom - top) * sy
    }

    // Written as Long literals narrowed to Int so the firmware's unsigned
    // constants stay readable: spelling out the negative Int each becomes
    // would be three chances to typo a bit pattern nothing else checks.
    private val MIX_A = 0x9E3779B1L.toInt()
    private val MIX_B = 0x85EBCA77L.toInt()
    private val MIX_C = 0x2545F491L.toInt()
}
