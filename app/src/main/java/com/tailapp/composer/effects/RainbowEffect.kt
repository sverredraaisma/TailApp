package com.tailapp.composer.effects

import com.tailapp.composer.AXIS_OPTIONS
import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.composer.axisValue
import com.tailapp.composer.frac
import com.tailapp.led.ColorMath
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * A hue sweep along the tail (or around a ring).
 *
 * The phase advances with wall-clock time, and — with `beatSync` on — jumps a
 * further `beatStep` of a full cycle on every tracked beat, which locks the
 * colour movement to the music instead of merely running near it.
 *
 * Colours come from [ColorMath.hsvToRgb], the firmware's own integer HSV, so a
 * rainbow composed here and a rainbow rendered by the device agree.
 */
class RainbowEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val axis = p.enumIndex("axis")
        val spread = p.float("spread")
        val speed = p.float("speed")
        val saturation = (p.float("saturation") * 255f).toInt().coerceIn(0, 255)
        val value = (p.float("brightness") * 255f).toInt().coerceIn(0, 255)
        val beatStep = if (p.bool("beatSync")) p.float("beatStep") else 0f

        val phase = ctx.timeSeconds * speed + ctx.beatCount * beatStep

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val hue = frac(axisValue(c, axis) * spread + phase) * 360f
            out.setPacked(i, ColorMath.hsvToRgb(hue.toInt().coerceIn(0, 359), saturation, value))
        }
    }

    companion object {
        val SPEC = EffectSpec(
            id = "rainbow",
            displayName = "Rainbow",
            category = EffectCategory.BASE,
            schema = listOf(
                EffectParam.Choice("axis", "Axis", AXIS_OPTIONS, 0),
                EffectParam.Scalar("spread", "Cycles", 1f, 0.25f, 6f),
                EffectParam.Scalar("speed", "Speed", 0.15f, -2f, 2f, unit = "/s"),
                EffectParam.Toggle("beatSync", "Step on beat", false),
                EffectParam.Scalar("beatStep", "Beat step", 0.08f, 0f, 0.5f),
                EffectParam.Scalar("saturation", "Saturation", 1f, 0f, 1f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { RainbowEffect() }
        )
    }
}
