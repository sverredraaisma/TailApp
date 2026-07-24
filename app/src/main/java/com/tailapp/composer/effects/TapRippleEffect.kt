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
import kotlin.math.exp

/**
 * A tap on the tail launches a wavefront from the end that was touched.
 *
 * The physical counterpart of [BeatRippleEffect]: same wave, but the trigger is
 * someone touching the device rather than the music. Tapping the base sends the
 * ripple outward toward the tip and tapping the tip sends it back, so the light
 * appears to come from where the hand was — which is what makes it read as the
 * tail responding rather than as an animation that happened to play.
 *
 * The front's position comes from the time since the tap rather than being
 * integrated per frame, so a late frame skips the wave ahead instead of stalling
 * it. Only the latest tap is drawn; a queue would cost allocation on the render
 * path for waves that have already left the strip.
 */
class TapRippleEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val tap = ctx.lastTap ?: return
        val age = ctx.secondsSinceTap
        if (age < 0f) return

        // "Which end" is a filter, not just a direction: a stack can carry one
        // of these per end with different colours.
        when (p.enumIndex("respondsTo")) {
            1 -> if (tap.end != TailEnd.BASE) return
            2 -> if (tap.end != TailEnd.TIP) return
        }

        val decay = p.float("decay")
        val fade = exp(-age / decay.coerceAtLeast(1e-3f))
        if (fade <= MIN_VISIBLE) return

        val front = age * p.float("speed")
        val width = p.float("width").coerceAtLeast(1e-3f)
        val colour = p.color("color")
        val brightness = p.float("brightness")

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            // Distance travelled from the tapped end, so the wave always starts
            // where the hand was.
            val distance = if (tap.end == TailEnd.TIP) 1f - c.y else c.y

            val offset = (distance - front) / width
            val shape = exp(-offset * offset)
            if (shape <= MIN_VISIBLE) continue

            out.setPacked(i, EffectColors.scale(colour, shape * fade * brightness))
        }
    }

    companion object {
        private const val MIN_VISIBLE = 1f / 512f

        val SPEC = EffectSpec(
            id = "tap_ripple",
            displayName = "Tap Ripple",
            category = EffectCategory.TAIL,
            schema = listOf(
                EffectParam.Color("color", "Colour", 0xFFFFFF),
                EffectParam.Choice("respondsTo", "Responds to", listOf("Any tap", "Base", "Tip"), 0),
                EffectParam.Scalar("speed", "Speed", 2.0f, 0.1f, 8f, unit = "/s"),
                EffectParam.Scalar("width", "Width", 0.16f, 0.02f, 1f),
                EffectParam.Scalar("decay", "Fade", 0.9f, 0.05f, 4f, unit = "s"),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { TapRippleEffect() }
        )
    }
}
