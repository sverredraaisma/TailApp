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
 * A flat colour across the whole strip — the usual bottom layer of a stack.
 *
 * Static by default, but `levelBoost` lets even the base breathe with the music
 * without adding a second layer: at `0` it is a constant fill, at `1` the
 * loudness drives most of its brightness.
 */
class SolidColorEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val colour = p.color("color")
        val brightness = p.float("brightness")
        val boost = p.float("levelBoost")

        // At boost = 0 the level is ignored entirely; at 1 it scales from silence
        // to full, so the parameter reads as "how much of the brightness is the music".
        val gain = brightness * ((1f - boost) + boost * ctx.level)
        val scaled = EffectColors.scale(colour, gain)
        for (i in coords.indices) out.setPacked(i, scaled)
    }

    companion object {
        val SPEC = EffectSpec(
            id = "solid",
            displayName = "Solid Colour",
            category = EffectCategory.BASE,
            schema = listOf(
                EffectParam.Color("color", "Colour", 0x00A0FF),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f),
                EffectParam.Scalar("levelBoost", "Volume boost", 0f, 0f, 1f)
            ),
            factory = { SolidColorEffect() }
        )
    }
}
