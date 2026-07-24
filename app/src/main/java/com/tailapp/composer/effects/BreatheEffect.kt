package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * A slow sinusoidal swell of one colour across the whole strip.
 *
 * With `syncToBar` on it breathes once per bar at the tracked tempo instead of
 * at a fixed rate, which turns it from ambient wallpaper into something that
 * moves with the music while staying far gentler than a beat flash.
 */
class BreatheEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val colour = p.color("color")
        val floor = p.float("floor")
        val brightness = p.float("brightness")

        val phase = if (p.bool("syncToBar") && ctx.bpm > 0f) {
            ctx.barPhase
        } else {
            ctx.timeSeconds * p.float("rate")
        }

        val swell = 0.5f + 0.5f * sin(phase * TWO_PI)
        val level = (floor + (1f - floor) * swell) * brightness
        val scaled = EffectColors.scale(colour, level)
        for (i in coords.indices) out.setPacked(i, scaled)
    }

    companion object {
        private const val TWO_PI = (2 * PI).toFloat()

        val SPEC = EffectSpec(
            id = "breathe",
            displayName = "Breathe",
            category = EffectCategory.BASE,
            schema = listOf(
                EffectParam.Color("color", "Colour", 0x2060FF),
                EffectParam.Scalar("rate", "Rate", 0.18f, 0.02f, 2f, unit = "Hz"),
                EffectParam.Toggle("syncToBar", "One breath per bar", false),
                EffectParam.Scalar("floor", "Floor", 0.25f, 0f, 1f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { BreatheEffect() }
        )
    }
}
