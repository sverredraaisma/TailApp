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
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The downhill side of every ring lights up, like a bubble level wrapped around
 * the tail.
 *
 * Reads the base IMU's gravity vector, so it responds to how the *wearer* is
 * oriented rather than to how the tail is posed: lean over and the light rolls
 * around the rings to stay pointing at the floor. Striking in motion, and unlike
 * every other effect here it needs no music and no movement of the tail itself.
 *
 * Falls back to an even glow when gravity is degenerate — a disconnected device
 * reports a resting vector, and an IMU in freefall reports nothing usable.
 */
class GravityLevelEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val gx = ctx.tail.gravityX
        val gy = ctx.tail.gravityY

        // Only the component across the rings matters; the axial one just says
        // whether the tail is pointing up or down.
        val lateral = sqrt(gx * gx + gy * gy)
        val colour = p.color("color")
        val brightness = p.float("brightness")

        if (lateral < MIN_TILT) {
            // Level (or freefall): no downhill side exists, so an even ring is
            // the honest answer rather than an arbitrary direction.
            val even = EffectColors.scale(colour, p.float("floor") * brightness)
            for (i in coords.indices) out.setPacked(i, even)
            return
        }

        // Direction of "down" as a fraction around the ring.
        val downhill = ((atan2(gy, gx) / TWO_PI) + 1f) % 1f
        val contrast = p.float("contrast").coerceIn(0f, 1f)
        val floor = p.float("floor")

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            var distance = abs(c.x - downhill)
            if (distance > 0.5f) distance = 1f - distance

            // 1 at the downhill point, falling to 0 at the opposite side.
            val alignment = 1f - distance * 2f
            val shaped = floor + (1f - floor) * alignment.coerceIn(0f, 1f)
            // Contrast blends between a flat ring and a hard band.
            val level = (1f - contrast) + contrast * shaped

            out.setPacked(i, EffectColors.scale(colour, level * brightness))
        }
    }

    companion object {
        /** Below this the tail is level enough that "downhill" is meaningless. */
        private const val MIN_TILT = 0.05f
        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        val SPEC = EffectSpec(
            id = "gravity_level",
            displayName = "Gravity Level",
            category = EffectCategory.TAIL,
            schema = listOf(
                EffectParam.Color("color", "Colour", 0x30FF80),
                EffectParam.Scalar("contrast", "Contrast", 0.85f, 0f, 1f),
                EffectParam.Scalar("floor", "Uphill brightness", 0.05f, 0f, 1f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { GravityLevelEffect() }
        )
    }
}
