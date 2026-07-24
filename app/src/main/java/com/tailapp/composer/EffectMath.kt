package com.tailapp.composer

import com.tailapp.led.LedCoord

/** Fractional part, always in `0..1` even for negative input (scroll offsets go both ways). */
internal fun frac(x: Float): Float = ((x % 1f) + 1f) % 1f

/** Folds `0..1` into a `0→1→0` triangle, for gradients that mirror instead of wrapping. */
internal fun triangle(x: Float): Float {
    val f = frac(x)
    return if (f <= 0.5f) f * 2f else (1f - f) * 2f
}

/**
 * The axis convention shared by every effect that offers one: index `0` runs
 * **along the tail** ([LedCoord.y], base to tip) and index `1` runs **around a
 * ring** ([LedCoord.x]). Effects declare it as
 * `EffectParam.Choice(..., options = AXIS_OPTIONS)`.
 */
internal val AXIS_OPTIONS = listOf("Along tail", "Around ring")

internal fun axisValue(c: LedCoord, axis: Int): Float = if (axis == 0) c.y else c.x

/**
 * The scalar-loudness convention shared by every effect that offers a choice of
 * what to follow. All four are already adaptively normalised to `0..1` by
 * [CompositionScene], so a threshold set against one is meaningful against any.
 */
internal val AUDIO_SOURCE_OPTIONS = listOf("Volume", "Bass", "Mid", "High")

internal fun audioSource(ctx: ReactiveContext, index: Int): Float = when (index) {
    1 -> ctx.bass
    2 -> ctx.mid
    3 -> ctx.high
    else -> ctx.level
}
