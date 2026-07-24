package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.MotionStateSource
import com.tailapp.led.PixelBuffer
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Mirrors `main/led/effects/gravity_level_effect.cpp`. Params: 0=red, 1=green,
 * 2=blue, 3=contrast (>1 sharpens the downhill side, <1 flattens it).
 *
 * A ring lies in the plane perpendicular to the tail's long axis, so only
 * gravity's x/y components decide which way is down — the z component runs
 * along the tail and doesn't project onto a ring at all.
 */
class GravityLevelRenderer(private val motion: MotionStateSource) : LedEffectRenderer() {
    private var red = 0.0f
    private var green = 140.0f
    private var blue = 255.0f
    private var contrast = 1.2f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        // atan2(0,0) is defined as 0, so a tail held perfectly vertical - no
        // gravity in the ring's plane - still renders instead of going NaN.
        val downhill = atan2(motion.gravityY, motion.gravityX)

        val r = red.coerceIn(0.0f, 255.0f).toInt()
        val g = green.coerceIn(0.0f, 255.0f).toInt()
        val b = blue.coerceIn(0.0f, 255.0f).toInt()

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val theta = c.x * TWO_PI

            val align = cos(theta - downhill)
            var level = 0.5f + 0.5f * align * contrast
            if (level < 0.0f) level = 0.0f
            if (level > 1.0f) level = 1.0f

            out.set(i, (r * level).toInt(), (g * level).toInt(), (b * level).toInt())
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> red = value
            1 -> green = value
            2 -> blue = value
            3 -> contrast = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> red
        1 -> green
        2 -> blue
        3 -> contrast
        else -> 0.0f
    }

    private companion object {
        const val TWO_PI = 6.28318530718f
    }
}
