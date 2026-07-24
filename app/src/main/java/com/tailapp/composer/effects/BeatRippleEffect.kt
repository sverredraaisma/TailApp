package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import kotlin.math.abs
import kotlin.math.exp

/**
 * Every beat launches a wavefront that travels along the tail and fades.
 *
 * Movement rather than a flash, which is what makes a tail read as a *tail*
 * instead of a lamp. The front's position is derived from the time since the
 * beat rather than integrated frame to frame, so a dropped or late frame skips
 * the wave ahead to where it should be instead of stalling it.
 *
 * Only the most recent beat's wave is drawn: at any sane tempo the previous one
 * has left the strip, and tracking a queue of them would cost allocation on the
 * render path for something invisible.
 */
class BeatRippleEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val age = ctx.secondsSinceBeat
        if (ctx.lastBeat == null || age < 0f) return

        val decay = p.float("decay")
        val fade = exp(-age / decay.coerceAtLeast(1e-3f))
        if (fade <= MIN_VISIBLE) return

        val front = age * p.float("speed")
        val width = p.float("width").coerceAtLeast(1e-3f)
        val origin = p.enumIndex("origin")
        val colour = p.color("color")
        val brightness = p.float("brightness") *
            (if (ctx.onDownbeat) p.float("downbeatBoost") else 1f)

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val distance = when (origin) {
                1 -> 1f - c.y          // from the tip
                2 -> abs(c.y - 0.5f) * 2f  // outward from the middle
                else -> c.y            // from the base
            }

            // Gaussian ring around the wavefront: soft edges read as a wave,
            // a hard threshold reads as a bar sliding past.
            val offset = (distance - front) / width
            val shape = exp(-offset * offset)
            if (shape <= MIN_VISIBLE) continue

            out.setPacked(i, EffectColors.scale(colour, shape * fade * brightness))
        }
    }

    companion object {
        /** Below this a pixel would round to black anyway. */
        private const val MIN_VISIBLE = 1f / 512f

        val SPEC = EffectSpec(
            id = "beat_ripple",
            displayName = "Beat Ripple",
            category = EffectCategory.BEAT,
            schema = listOf(
                EffectParam.Color("color", "Colour", 0x00D0FF),
                EffectParam.Choice("origin", "From", listOf("Base", "Tip", "Middle"), 0),
                EffectParam.Scalar("speed", "Speed", 1.6f, 0.1f, 8f, unit = "/s"),
                EffectParam.Scalar("width", "Width", 0.18f, 0.02f, 1f),
                EffectParam.Scalar("decay", "Fade", 0.7f, 0.05f, 3f, unit = "s"),
                EffectParam.Scalar("downbeatBoost", "Downbeat boost", 1.3f, 1f, 3f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { BeatRippleEffect() }
        )
    }
}
