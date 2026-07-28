package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import kotlin.math.abs
import kotlin.math.exp

/**
 * The payoff layer: fires once when the drop detector says a drop landed, then
 * decays over its window.
 *
 * Silent almost all the time, which is the point — everything else in a stack is
 * continuous, so the one layer that only ever speaks at the drop is what makes
 * the drop land. Its brightness carries the detector's own `intensity`, so a
 * marginal detection is a glow and an unmistakable one is a wall of light.
 *
 * `Burst` sends a front up the tail as the envelope falls, arriving at the tip
 * as it fades out; `Full strip` is a plain flash for stacks where the geometry
 * is already busy.
 */
class DropFlashEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val window = p.float("window").coerceAtLeast(1e-3f)
        val level = ctx.dropEnvelope(window)
        if (level <= 0f) return

        val colour = p.color("color")
        val brightness = p.float("brightness")

        if (p.enumIndex("shape") == 0) {
            val scaled = EffectColors.scale(colour, level * brightness)
            for (i in coords.indices) out.setPacked(i, scaled)
            return
        }

        // The front leaves the base at the moment of the drop and reaches the tip
        // as the window expires — so it is elapsed *time*, not the envelope.
        // `level` carries the detector's intensity (see `dropEnvelope`), so
        // deriving the position from it would start a marginal drop's burst
        // halfway up the tail and let it travel only part of the way.
        val front = (ctx.secondsSinceDrop / window).coerceIn(0f, 1f)
        val width = p.float("width").coerceAtLeast(1e-3f)

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val offset = (abs(c.y - front)) / width
            val shape = exp(-offset * offset)
            if (shape <= MIN_VISIBLE) continue
            out.setPacked(i, EffectColors.scale(colour, shape * level * brightness))
        }
    }

    companion object {
        private const val MIN_VISIBLE = 1f / 512f

        val SPEC = EffectSpec(
            id = "drop_flash",
            displayName = "Drop Flash",
            category = EffectCategory.BEAT,
            schema = listOf(
                EffectParam.Color("color", "Colour", 0xFFFFFF),
                EffectParam.Choice("shape", "Shape", listOf("Full strip", "Burst up the tail"), 0),
                EffectParam.Scalar("window", "Window", 2.0f, 0.2f, 6f, unit = "s"),
                EffectParam.Scalar("width", "Burst width", 0.25f, 0.05f, 1f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { DropFlashEffect() }
        )
    }
}
