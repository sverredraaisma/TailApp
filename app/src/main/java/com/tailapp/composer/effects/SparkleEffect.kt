package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectNoise
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import kotlin.math.exp
import kotlin.math.floor

/**
 * Scattered LEDs light and fade — either a fresh set on every beat, or a
 * continuous twinkle at a fixed rate.
 *
 * Which LEDs sparkle is a *hash of the LED index and the current tick*, not a
 * live RNG. That matters for more than testability: a beat is drawn across many
 * frames, and re-rolling per frame would make the set shimmer into mush instead
 * of lighting a stable constellation that fades.
 */
class SparkleEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val density = p.float("density")
        val decay = p.float("decay").coerceAtLeast(1e-3f)
        val onBeat = p.enumIndex("trigger") == 0

        val tick: Int
        val level: Float
        if (onBeat) {
            if (ctx.lastBeat == null || ctx.secondsSinceBeat < 0f) return
            tick = ctx.beatCount
            level = ctx.beatEnvelope(decay)
        } else {
            val rate = p.float("rate").coerceAtLeast(1e-3f)
            val ticks = ctx.timeSeconds * rate
            tick = floor(ticks.toDouble()).toInt()
            // Seconds elapsed inside the current tick, so each generation fades
            // on the same curve regardless of the rate.
            val elapsed = (ticks - tick) / rate
            level = exp(-elapsed / decay)
        }
        if (level <= 0f) return

        val cycleHue = p.enumIndex("colorMode") == 1
        val fixed = p.color("color")
        val hueStep = p.float("hueStep")
        val spread = p.float("hueSpread")
        val brightness = p.float("brightness") * level

        for (i in coords.indices) {
            if (EffectNoise.hash(i, tick) >= density) continue

            val colour = if (cycleHue) {
                // A second hash spreads the constellation across nearby hues so
                // it reads as glitter rather than as one flat colour.
                EffectColors.fromHue(tick * hueStep + EffectNoise.hash(i, tick + HUE_SALT) * spread)
            } else {
                fixed
            }
            out.setPacked(i, EffectColors.scale(colour, brightness))
        }
    }

    companion object {
        /** Decorrelates the hue hash from the "does it sparkle" hash. */
        private const val HUE_SALT = 7919

        val SPEC = EffectSpec(
            id = "sparkle",
            displayName = "Sparkle",
            category = EffectCategory.BEAT,
            schema = listOf(
                EffectParam.Choice("trigger", "Trigger", listOf("On beat", "Continuous"), 0),
                EffectParam.Choice("colorMode", "Colour", listOf("Fixed", "Cycle"), 0),
                EffectParam.Color("color", "Colour", 0xFFFFFF),
                EffectParam.Scalar("hueStep", "Hue step", 37f, 0f, 180f, unit = "°"),
                EffectParam.Scalar("hueSpread", "Hue spread", 60f, 0f, 180f, unit = "°"),
                EffectParam.Scalar("density", "Density", 0.25f, 0.01f, 1f),
                EffectParam.Scalar("decay", "Decay", 0.25f, 0.02f, 2f, unit = "s"),
                EffectParam.Scalar("rate", "Rate", 8f, 0.5f, 30f, unit = "Hz"),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { SparkleEffect() }
        )
    }
}
