package com.tailapp.led.effects

import com.tailapp.led.ColorMath
import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/rainbow_effect.cpp`. Params: 0=direction
 * (0=horizontal/x, 1=vertical/y, 2=diagonal), 1=speed (hue degrees/sec),
 * 2=scale (hue cycles across the coordinate range).
 */
class RainbowRenderer : LedEffectRenderer() {
    private var direction = 0.0f
    private var speed = 60.0f
    private var scale = 1.0f

    // Running phase, in hue cycles. Persists across render() calls exactly
    // like the firmware's `time_offset_` member.
    private var timeOffset = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        timeOffset += speed * dt / 360.0f
        // Keep time_offset_ from growing unbounded, same guard as the firmware.
        if (timeOffset > 1000.0f) timeOffset -= 1000.0f

        // `static_cast<int>(direction_ + 0.5f)` truncates toward zero, same as
        // Kotlin's Float.toInt() - this is round-half-up for the non-negative
        // values direction_ is expected to hold, not round-half-to-even.
        val dir = (direction + 0.5f).toInt()

        for (i in coords.indices) {
            val c = transformCoord(coords[i])

            val axis = when (dir) {
                1 -> c.y
                2 -> (c.x + c.y) * 0.5f
                else -> c.x
            }

            var hue = (axis * scale + timeOffset) * 360.0f
            hue %= 360.0f // matches fmodf: result keeps the sign of the dividend
            if (hue < 0.0f) hue += 360.0f

            val h = hue.toInt() % 360
            out.setPacked(i, ColorMath.hsvToRgb(h, 255, 255))
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> direction = value
            1 -> speed = value
            2 -> scale = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> direction
        1 -> speed
        2 -> scale
        else -> 0.0f
    }
}
