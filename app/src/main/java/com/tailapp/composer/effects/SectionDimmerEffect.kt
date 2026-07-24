package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.composer.frac
import com.tailapp.drop.SectionState
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * A modulator driven by the coarse song section: strobes through a build-up,
 * dims through a breakdown, holds out of the way everywhere else.
 *
 * This is the old `ReactiveRenderer.sectionGain` recovered as a *composable*
 * layer. In the profile system that behaviour was welded into the one renderer
 * and applied to the entire frame, take it or leave it. Here it is a layer like
 * any other, so it can modulate one folder and not another — the build-up can
 * strobe the beat layers while an ambient base underneath stays steady.
 *
 * The build-up strobe ramps its rate from `startHz` to `endHz` across
 * [ReactiveContext.sectionRamp], and is deliberately square rather than
 * sinusoidal: a build-up should read as flashes getting faster, not as a wobble
 * getting quicker. It dips to `strobeFloor` rather than to black, so the tail
 * never looks switched off mid-build.
 */
class SectionDimmerEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val level = when (ctx.section) {
            SectionState.BUILDUP -> {
                val startHz = p.float("buildupStartHz")
                val hz = startHz + (p.float("buildupEndHz") - startHz) * ctx.sectionRamp
                if (frac(ctx.timeSeconds * hz) < p.float("strobeDuty")) 1f else p.float("strobeFloor")
            }

            SectionState.BREAKDOWN -> p.float("breakdown")
            SectionState.OUTRO -> p.float("breakdown") * OUTRO_SCALE
            SectionState.INTRO -> p.float("intro")
            else -> 1f
        }

        val grey = EffectColors.gray(level.coerceIn(0f, 1f))
        for (i in coords.indices) out.setPacked(i, grey)
    }

    companion object {
        /** An outro is a breakdown that is not coming back; slightly darker. */
        private const val OUTRO_SCALE = 0.8f

        val SPEC = EffectSpec(
            id = "section_dimmer",
            displayName = "Section Dimmer",
            category = EffectCategory.MODULATOR,
            schema = listOf(
                EffectParam.Scalar("buildupStartHz", "Build-up from", 2f, 0.2f, 20f, unit = "Hz"),
                EffectParam.Scalar("buildupEndHz", "Build-up to", 12f, 0.2f, 25f, unit = "Hz"),
                EffectParam.Scalar("strobeDuty", "Strobe duty", 0.45f, 0.05f, 0.95f),
                EffectParam.Scalar("strobeFloor", "Strobe floor", 0.12f, 0f, 1f),
                EffectParam.Scalar("breakdown", "Breakdown", 0.4f, 0f, 1f),
                EffectParam.Scalar("intro", "Intro", 0.6f, 0f, 1f)
            ),
            factory = { SectionDimmerEffect() }
        )
    }
}
