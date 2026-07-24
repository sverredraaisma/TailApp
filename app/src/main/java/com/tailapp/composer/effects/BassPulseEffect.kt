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
 * The whole strip pulses with one band of the spectrum — bass by default.
 *
 * Deliberately *not* beat-driven: it follows energy, so it fires on every kick
 * whether or not the tracker called that kick a beat, and it keeps working on
 * material with no stable tempo at all. Pairing this with
 * [BeatFlashEffect] gives a stack that both grooves and thumps.
 *
 * Attack is instant and release is a fixed fall rate, so a sustained bass note
 * holds the strip up rather than flickering with the waveform.
 */
class BassPulseEffect : ReactiveEffect(SPEC) {

    private var current = 0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val raw = audioSource(ctx, p.enumIndex("source"))
        val threshold = p.float("threshold")

        // Re-scale above the threshold rather than merely gating, so a quiet
        // passage goes dark instead of sitting at a constant dim level.
        val driven = if (raw <= threshold) 0f
        else ((raw - threshold) / (1f - threshold).coerceAtLeast(1e-3f)) * p.float("gain")

        val target = driven.coerceIn(0f, 1f)
        current = if (target >= current) target
        else (current - p.float("fall") * ctx.dtSeconds).coerceAtLeast(target)

        val level = current * p.float("brightness")
        if (level <= 0f) return

        val scaled = EffectColors.scale(p.color("color"), level)
        for (i in coords.indices) out.setPacked(i, scaled)
    }

    companion object {
        val SPEC = EffectSpec(
            id = "bass_pulse",
            displayName = "Bass Pulse",
            category = EffectCategory.AUDIO,
            schema = listOf(
                EffectParam.Choice("source", "Follows", AUDIO_SOURCE_OPTIONS, 1),
                EffectParam.Color("color", "Colour", 0xFF6000),
                EffectParam.Scalar("gain", "Gain", 1.2f, 0.1f, 4f),
                EffectParam.Scalar("threshold", "Threshold", 0.15f, 0f, 0.9f),
                EffectParam.Scalar("fall", "Fall", 2.5f, 0.1f, 12f, unit = "/s"),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { BassPulseEffect() }
        )
    }
}
