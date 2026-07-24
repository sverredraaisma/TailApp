package com.tailapp.composer.effects

import com.tailapp.composer.AXIS_OPTIONS
import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.composer.axisValue
import com.tailapp.composer.frac
import com.tailapp.composer.triangle
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * A two-colour gradient along the tail or around a ring, optionally scrolling.
 *
 * `mirrored` decides what happens at the seam: wrapped, the gradient jumps from
 * the second colour back to the first; mirrored, it folds back through itself so
 * a scrolling gradient has no visible seam at all.
 */
class GradientEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val from = p.color("colorA")
        val to = p.color("colorB")
        val axis = p.enumIndex("axis")
        val repeat = p.float("repeat")
        val speed = p.float("speed")
        val mirrored = p.bool("mirrored")
        val brightness = p.float("brightness")

        val offset = ctx.timeSeconds * speed

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val position = axisValue(c, axis) * repeat + offset
            val t = if (mirrored) triangle(position) else frac(position)
            out.setPacked(i, EffectColors.scale(EffectColors.lerp(from, to, t), brightness))
        }
    }

    companion object {
        val SPEC = EffectSpec(
            id = "gradient",
            displayName = "Gradient",
            category = EffectCategory.BASE,
            schema = listOf(
                EffectParam.Color("colorA", "From", 0x1040FF),
                EffectParam.Color("colorB", "To", 0xFF00C8),
                EffectParam.Choice("axis", "Axis", AXIS_OPTIONS, 0),
                EffectParam.Scalar("repeat", "Repeats", 1f, 0.25f, 8f),
                EffectParam.Scalar("speed", "Scroll", 0.1f, -2f, 2f, unit = "/s"),
                EffectParam.Toggle("mirrored", "Mirrored", true),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { GradientEffect() }
        )
    }
}
