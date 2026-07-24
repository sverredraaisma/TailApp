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
 * The whole strip flashes on every tracked beat and decays exponentially.
 *
 * The bluntest and most reliable beat layer, and the one to reach for first: it
 * needs no geometry, so it reads identically on any ring configuration. Blend it
 * with [com.tailapp.model.BlendMode.ADD] over a base layer.
 *
 * A beat still in the future contributes nothing — the tracker predicts beats
 * ahead so the BLE round trip can be absorbed, and lighting them early would
 * undo the calibration that exists to hide it.
 */
class BeatFlashEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val level = ctx.beatEnvelope(p.float("decay"), p.float("downbeatBoost"))
        if (level <= 0f) return

        val colour = if (p.enumIndex("colorMode") == 1) {
            EffectColors.fromHue(ctx.beatCount * p.float("hueStep"))
        } else {
            p.color("color")
        }

        // The envelope is allowed past 1 on a downbeat; clipping into white is
        // what makes the first beat of a bar read as an accent.
        val scaled = EffectColors.scale(colour, level * p.float("brightness"))
        for (i in coords.indices) out.setPacked(i, scaled)
    }

    companion object {
        val SPEC = EffectSpec(
            id = "beat_flash",
            displayName = "Beat Flash",
            category = EffectCategory.BEAT,
            schema = listOf(
                EffectParam.Choice("colorMode", "Colour", listOf("Fixed", "Cycle per beat"), 0),
                EffectParam.Color("color", "Colour", 0xFFFFFF),
                EffectParam.Scalar("hueStep", "Hue step", 37f, 0f, 180f, unit = "°"),
                EffectParam.Scalar("decay", "Decay", 0.30f, 0.02f, 2f, unit = "s"),
                EffectParam.Scalar("downbeatBoost", "Downbeat boost", 1.35f, 1f, 3f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { BeatFlashEffect() }
        )
    }
}
