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
 * A bar that fills along the tail as a musical bar progresses, then resets on
 * the downbeat.
 *
 * The one effect here that makes the *metre* visible rather than just the pulse:
 * with `smooth` off it steps a quarter of the tail per beat, with it on the fill
 * slides continuously and the downbeat is a hard reset. Reads as a loading bar
 * for the next drop, which is exactly what a build-up is.
 */
class BarSweepEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val beat = ctx.lastBeat ?: return
        val beatsPerBar = p.float("beatsPerBar").coerceAtLeast(1f)

        val beats = if (p.bool("smooth")) {
            beat.beatInBar + ctx.beatPhase
        } else {
            (beat.beatInBar + 1).toFloat()
        }
        val height = (beats / beatsPerBar).coerceIn(0f, 1f)

        val fromTip = p.enumIndex("direction") == 1
        val softness = p.float("softness").coerceAtLeast(1e-3f)
        val colour = p.color("color")
        val tipColour = p.color("tipColor")
        val brightness = p.float("brightness")

        // Tint toward tipColour as the bar approaches full, so the last beat of
        // the bar is visibly the last one. `height` is the same for every LED, so
        // this is computed once rather than per pixel.
        val colourAt = EffectColors.lerp(colour, tipColour, height)

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val position = if (fromTip) 1f - c.y else c.y

            // Soft leading edge: a hard cut looks like a glitch on a strip whose
            // rings are only a few LEDs apart.
            //
            // The `+ 1` puts the fade band just *above* `height` rather than just
            // below it, so an LED sitting exactly at the fill line is lit rather
            // than dark. Without it a full bar leaves the tip unlit, and the last
            // beat of every bar visibly stops one LED short.
            val edge = ((height - position) / softness + 1f).coerceIn(0f, 1f)
            if (edge <= 0f) continue

            out.setPacked(i, EffectColors.scale(colourAt, edge * brightness))
        }
    }

    companion object {
        val SPEC = EffectSpec(
            id = "bar_sweep",
            displayName = "Bar Sweep",
            category = EffectCategory.BEAT,
            schema = listOf(
                EffectParam.Color("color", "Colour", 0x40FF00),
                EffectParam.Color("tipColor", "Colour at full", 0xFFFFFF),
                EffectParam.Choice("direction", "Fills", listOf("Base to tip", "Tip to base"), 0),
                EffectParam.Scalar("beatsPerBar", "Beats per bar", 4f, 2f, 8f),
                EffectParam.Toggle("smooth", "Slide between beats", true),
                EffectParam.Scalar("softness", "Edge softness", 0.08f, 0.01f, 0.5f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { BarSweepEffect() }
        )
    }
}
