package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.composer.frac
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import kotlin.math.abs
import kotlin.math.exp

/**
 * A lit band that jumps a fixed step along the tail on every beat, wrapping
 * around at the tip.
 *
 * Position is a function of the beat *count*, not of time, so the band lands on
 * the same rings every bar and the movement reads as deliberate rather than as
 * drift. Because every LED in a ring shares its `y`, stepping by `1/rings` lights
 * exactly one ring at a time; any other step gives a smoother slide.
 */
class RingChaseEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val hold = p.float("hold")
        val pulse = ctx.beatEnvelope(p.float("decay"))
        // `hold` keeps the band visible between beats; at 0 it only exists during
        // the flash, at 1 it is always lit and only the position moves.
        val level = (hold + (1f - hold) * pulse).coerceIn(0f, 1f)
        if (level <= 0f) return

        val position = frac(ctx.beatCount * p.float("step"))
        val width = p.float("width").coerceAtLeast(1e-3f)
        val colour = if (p.enumIndex("colorMode") == 1) {
            EffectColors.fromHue(ctx.beatCount * p.float("hueStep"))
        } else {
            p.color("color")
        }
        val brightness = p.float("brightness")

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            // Wrapped distance, so the band crosses the tip cleanly.
            val raw = abs(c.y - position)
            val distance = if (raw > 0.5f) 1f - raw else raw

            val offset = distance / width
            val shape = exp(-offset * offset)
            if (shape <= MIN_VISIBLE) continue

            out.setPacked(i, EffectColors.scale(colour, shape * level * brightness))
        }
    }

    companion object {
        private const val MIN_VISIBLE = 1f / 512f

        val SPEC = EffectSpec(
            id = "ring_chase",
            displayName = "Ring Chase",
            category = EffectCategory.BEAT,
            schema = listOf(
                EffectParam.Choice("colorMode", "Colour", listOf("Fixed", "Cycle per beat"), 0),
                EffectParam.Color("color", "Colour", 0xFF00C8),
                EffectParam.Scalar("hueStep", "Hue step", 37f, 0f, 180f, unit = "°"),
                EffectParam.Scalar("step", "Step per beat", 0.2f, 0.02f, 1f),
                EffectParam.Scalar("width", "Width", 0.12f, 0.02f, 0.5f),
                EffectParam.Scalar("decay", "Decay", 0.35f, 0.02f, 2f, unit = "s"),
                EffectParam.Scalar("hold", "Hold between beats", 0.2f, 0f, 1f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { RingChaseEffect() }
        )
    }
}
