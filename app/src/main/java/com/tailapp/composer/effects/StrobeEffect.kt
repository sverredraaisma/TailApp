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

/**
 * A hard on/off strobe locked to a subdivision of the tracked tempo.
 *
 * The phase is `beatCount + beatPhase` — a continuous count of beats — scaled by
 * the chosen division, so a `1/2 beat` strobe fires exactly twice per beat and
 * stays in phase indefinitely rather than accumulating error the way a free
 * timer at "roughly the right Hz" would.
 *
 * With no tempo yet (silence, or the first second of a session) it falls back to
 * the fixed `freeHz` rate, so the layer never simply vanishes.
 */
class StrobeEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val duty = p.float("duty")
        val locked = ctx.bpm > 0f && ctx.lastBeat != null

        val phase = if (locked) {
            // Belt and braces: the bag already clamps a Choice to its options,
            // but this subscripts a raw array from a persisted value, and an
            // out-of-range index here would throw out of the render loop.
            val division = p.enumIndex("division").coerceIn(FLASHES_PER_BEAT.indices)
            (ctx.beatCount + ctx.beatPhase) * FLASHES_PER_BEAT[division]
        } else {
            ctx.timeSeconds * p.float("freeHz")
        }

        val lit = frac(phase) < duty
        val level = if (lit) p.float("brightness") else p.float("floor") * p.float("brightness")
        if (level <= 0f) return

        val scaled = EffectColors.scale(p.color("color"), level)
        for (i in coords.indices) out.setPacked(i, scaled)
    }

    companion object {
        private val DIVISIONS = listOf("1/4 beat", "1/2 beat", "1 beat", "2 beats", "4 beats")

        /** Flashes per beat, parallel to [DIVISIONS]. */
        private val FLASHES_PER_BEAT = floatArrayOf(4f, 2f, 1f, 0.5f, 0.25f)

        val SPEC = EffectSpec(
            id = "strobe",
            displayName = "Strobe",
            category = EffectCategory.BEAT,
            schema = listOf(
                EffectParam.Color("color", "Colour", 0xFFFFFF),
                EffectParam.Choice("division", "Every", DIVISIONS, 2),
                EffectParam.Scalar("duty", "Duty", 0.35f, 0.02f, 0.98f),
                EffectParam.Scalar("floor", "Off level", 0f, 0f, 1f),
                EffectParam.Scalar("freeHz", "Free rate", 8f, 0.5f, 25f, unit = "Hz"),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { StrobeEffect() }
        )
    }
}
