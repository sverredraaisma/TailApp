package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.composer.TailEnd
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * A modulator that opens on a tap and closes again as it decays.
 *
 * Multiplied over a folder it turns a tap into a switch for a whole group: an
 * otherwise-invisible stack that flares into life when the tail is touched, or
 * — inverted — a look that ducks out of the way for a moment.
 *
 * Like every modulator it emits neutral grey, so it changes how bright the group
 * below it is without recolouring it.
 */
class TapGateEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val end = when (p.enumIndex("respondsTo")) {
            1 -> TailEnd.BASE
            2 -> TailEnd.TIP
            else -> null
        }

        val open = ctx.tapEnvelope(p.float("decay"), end)
        val driven = if (p.bool("invert")) 1f - open else open

        val floor = p.float("floor")
        val level = (floor + (1f - floor) * driven).coerceIn(0f, 1f)

        val grey = EffectColors.gray(level)
        for (i in coords.indices) out.setPacked(i, grey)
    }

    companion object {
        val SPEC = EffectSpec(
            id = "tap_gate",
            displayName = "Tap Gate",
            category = EffectCategory.MODULATOR,
            schema = listOf(
                EffectParam.Choice("respondsTo", "Responds to", listOf("Any tap", "Base", "Tip"), 0),
                EffectParam.Scalar("decay", "Hold", 1.2f, 0.05f, 8f, unit = "s"),
                EffectParam.Scalar("floor", "Closed level", 0f, 0f, 1f),
                EffectParam.Toggle("invert", "Invert", false)
            ),
            factory = { TapGateEffect() }
        )
    }
}
