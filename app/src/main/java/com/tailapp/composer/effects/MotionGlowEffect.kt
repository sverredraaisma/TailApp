package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * The tail glows when it moves: brightness follows wag speed, and the hue
 * shifts with which way it is deflected.
 *
 * The single clearest demonstration that the lighting knows about the body it
 * is attached to — hold the tail still and it is dim, swing it and it lights up,
 * and the colour tells you which way it went. Nothing about it involves sound.
 *
 * A floor keeps it from going fully black at rest, because an effect that
 * disappears entirely reads as broken rather than as idle.
 */
class MotionGlowEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val speed = (ctx.tail.wagSpeed * p.float("gain")).coerceIn(0f, 1f)
        val floor = p.float("floor")
        val level = (floor + (1f - floor) * speed) * p.float("brightness")

        // Deflection drives hue: the two extremes of travel land a configurable
        // spread apart, so a wag sweeps through colour as well as brightness.
        val spread = p.float("hueSpread")
        val hue = p.float("baseHue") + ctx.tail.deflectionX * spread
        val colour = EffectColors.fromHue(hue)

        // The tip moves further than the base, so let it glow proportionally
        // more: it is the part actually travelling.
        val tipBias = p.float("tipBias")
        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val alongTail = 1f - tipBias + tipBias * c.y
            out.setPacked(i, EffectColors.scale(colour, level * alongTail))
        }
    }

    companion object {
        val SPEC = EffectSpec(
            id = "motion_glow",
            displayName = "Motion Glow",
            category = EffectCategory.TAIL,
            schema = listOf(
                EffectParam.Scalar("baseHue", "Base hue", 190f, 0f, 360f, unit = "°"),
                EffectParam.Scalar("hueSpread", "Hue spread", 60f, 0f, 180f, unit = "°"),
                EffectParam.Scalar("gain", "Speed gain", 1.5f, 0.1f, 6f),
                EffectParam.Scalar("floor", "Resting brightness", 0.12f, 0f, 1f),
                EffectParam.Scalar("tipBias", "Tip bias", 0.4f, 0f, 1f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { MotionGlowEffect() }
        )
    }
}
