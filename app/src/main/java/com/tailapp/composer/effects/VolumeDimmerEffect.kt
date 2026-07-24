package com.tailapp.composer.effects

import com.tailapp.composer.AUDIO_SOURCE_OPTIONS
import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.composer.audioSource
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * A modulator: emits flat grey tracking loudness (or one band of it).
 *
 * Multiplied over a folder, it makes that whole group breathe with the music
 * without any of its layers being audio-aware — a rainbow, a plasma and a chase
 * all gain dynamics from one layer above them.
 *
 * Inverted, it does the opposite and is arguably more useful: a folder that
 * *fades in during the quiet parts* and gets out of the way when the mix is
 * dense.
 */
class VolumeDimmerEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val raw = (audioSource(ctx, p.enumIndex("source")) * p.float("gain")).coerceIn(0f, 1f)
        val driven = if (p.bool("invert")) 1f - raw else raw

        val floor = p.float("floor")
        val level = (floor + (1f - floor) * driven).coerceIn(0f, 1f)

        val grey = EffectColors.gray(level)
        for (i in coords.indices) out.setPacked(i, grey)
    }

    companion object {
        val SPEC = EffectSpec(
            id = "volume_dimmer",
            displayName = "Volume Dimmer",
            category = EffectCategory.MODULATOR,
            schema = listOf(
                EffectParam.Choice("source", "Follows", AUDIO_SOURCE_OPTIONS, 0),
                EffectParam.Scalar("gain", "Gain", 1f, 0.1f, 4f),
                EffectParam.Scalar("floor", "Floor", 0.1f, 0f, 1f),
                EffectParam.Toggle("invert", "Invert", false)
            ),
            factory = { VolumeDimmerEffect() }
        )
    }
}
