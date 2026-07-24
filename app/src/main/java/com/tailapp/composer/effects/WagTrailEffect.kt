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

/**
 * A bright band whose position around the rings tracks the tail's live
 * deflection, leaving a phosphor-style trail behind it.
 *
 * Where [MotionGlowEffect] says *how much* the tail is moving, this says *where
 * it is*: swing left and the lit band swings left with it. The decay is what
 * turns that into motion you can see — a bare marker would just be a dot, while
 * a fading trail draws the path the tail took.
 *
 * The trail is the one thing here that genuinely has to integrate, since its
 * shape is a history rather than a function of the present. It decays per second
 * rather than per frame so the look is the same at 30 fps and 60.
 */
class WagTrailEffect : ReactiveEffect(SPEC) {

    /**
     * Per-LED phosphor level, sized to the current strip.
     *
     * Instance state, which survives a parameter edit on purpose — the renderer
     * reuses an instance whose effect id has not changed, so dragging the width
     * slider does not wipe the trail. A new session drops the instances
     * entirely, which is what clears it.
     */
    private var trail = FloatArray(0)

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        if (trail.size != coords.size) trail = FloatArray(coords.size)

        // Deflection is -1..1; the head runs around the ring axis.
        val head = (ctx.tail.deflectionX + 1f) * 0.5f
        val width = p.float("width").coerceAtLeast(1e-3f)
        val persistence = p.float("persistence").coerceAtLeast(1e-3f)

        // Exponential decay expressed per second: dt-independent by construction.
        val keep = if (ctx.dtSeconds <= 0f) 1f else {
            val steps = ctx.dtSeconds / persistence
            if (steps > 20f) 0f else Math.exp(-steps.toDouble()).toFloat()
        }

        val colour = p.color("color")
        val brightness = p.float("brightness")
        val useHead = p.color("headColor")

        for (i in coords.indices) {
            val c = transformCoord(coords[i])

            // Wrap around the ring: a head at 0.95 must still light 0.02.
            var distance = abs(c.x - head)
            if (distance > 0.5f) distance = 1f - distance

            val deposit = if (distance < width) 1f - distance / width else 0f
            trail[i] = maxOf(trail[i] * keep, deposit)
            if (trail[i] <= MIN_VISIBLE) continue

            // The head itself takes its own colour so the leading edge reads
            // distinctly from the tail it leaves behind.
            val tint = EffectColors.lerp(colour, useHead, deposit)
            out.setPacked(i, EffectColors.scale(tint, trail[i] * brightness))
        }
    }

    companion object {
        private const val MIN_VISIBLE = 1f / 512f

        val SPEC = EffectSpec(
            id = "wag_trail",
            displayName = "Wag Trail",
            category = EffectCategory.TAIL,
            schema = listOf(
                EffectParam.Color("color", "Trail colour", 0x2050FF),
                EffectParam.Color("headColor", "Head colour", 0xFFFFFF),
                EffectParam.Scalar("width", "Head width", 0.18f, 0.02f, 0.5f),
                EffectParam.Scalar("persistence", "Trail length", 0.45f, 0.02f, 3f, unit = "s"),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { WagTrailEffect() }
        )
    }
}
