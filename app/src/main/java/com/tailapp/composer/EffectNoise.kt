package com.tailapp.composer

import kotlin.math.floor

/**
 * Deterministic pseudo-random helpers for the sparkle/fire/plasma effects.
 *
 * Deterministic on purpose: an effect seeded from a real RNG cannot be asserted
 * on, and the DSP/LED suites in this project assert numbers rather than shapes.
 * Everything here is a pure function of its inputs, so a test that renders frame
 * `n` twice gets the same pixels both times — and a sparkle seeded on the beat
 * index lights the same LEDs for every frame that beat is drawn across, instead
 * of shimmering because it was re-rolled per frame.
 */
internal object EffectNoise {

    /** A stable `0..1` hash of two integers. */
    fun hash(a: Int, b: Int): Float {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        // Drop the sign bit, then scale into 0..1.
        return (h and 0x7FFFFFFF) / 2147483647.0f
    }

    /** A stable `0..1` hash of an integer and a float bucket. */
    fun hash(a: Int, b: Float): Float = hash(a, floor(b.toDouble()).toInt())

    /**
     * Value noise in one dimension: [hash] sampled at integer steps and smoothly
     * interpolated, so a value walks rather than jumps between buckets.
     */
    fun valueNoise(seed: Int, x: Float): Float {
        val i = floor(x.toDouble()).toFloat()
        val f = x - i
        val a = hash(seed, i.toInt())
        val b = hash(seed, i.toInt() + 1)
        // Smoothstep, so the first derivative is continuous across buckets.
        val t = f * f * (3f - 2f * f)
        return a + (b - a) * t
    }
}
