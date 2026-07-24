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
 * A modulator: emits flat grey that brightens on every beat.
 *
 * Meant to sit at the **top of a folder** with
 * [com.tailapp.model.BlendMode.MULTIPLY], which makes everything inside that
 * folder pulse on the beat while keeping its own colours. That is the move this
 * whole folder mechanism exists for: one layer beats a dozen effects at once,
 * and none of them needs to know what a beat is.
 *
 * At `floor = 0` the folder is black between beats; at `1` the mask does
 * nothing. Turn `invert` on to *duck* the folder on the beat instead — useful
 * for making one layer punch a hole through everything beneath it.
 */
class BeatMaskEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val pulse = ctx.beatEnvelope(p.float("decay"), p.float("downbeatBoost")).coerceIn(0f, 1f)

        // Invert the pulse, not the finished level: the floor must stay the
        // *dimmest* the folder ever gets in both directions, or inverting would
        // silently turn the floor into a ceiling.
        val driven = if (p.bool("invert")) 1f - pulse else pulse

        val floor = p.float("floor")
        val level = (floor + (1f - floor) * driven).coerceIn(0f, 1f)

        val grey = EffectColors.gray(level)
        for (i in coords.indices) out.setPacked(i, grey)
    }

    companion object {
        val SPEC = EffectSpec(
            id = "beat_mask",
            displayName = "Beat Mask",
            category = EffectCategory.MODULATOR,
            schema = listOf(
                EffectParam.Scalar("decay", "Decay", 0.25f, 0.02f, 2f, unit = "s"),
                EffectParam.Scalar("downbeatBoost", "Downbeat boost", 1f, 1f, 3f),
                EffectParam.Scalar("floor", "Floor", 0.15f, 0f, 1f),
                EffectParam.Toggle("invert", "Invert (duck on beat)", false)
            ),
            factory = { BeatMaskEffect() }
        )
    }
}
