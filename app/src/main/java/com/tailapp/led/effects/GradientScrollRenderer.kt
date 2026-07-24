package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.Palettes
import com.tailapp.led.PixelBuffer
import kotlin.math.floor

/**
 * Mirrors `main/led/effects/gradient_scroll_effect.cpp`. Params: 0=palette id,
 * 1=speed (ramp-cycles/s), 2=scale (ramp-cycles across the coordinate range),
 * 3=axis (0=x, 1=y).
 */
class GradientScrollRenderer : LedEffectRenderer() {
    private var paletteId = 2.0f
    private var speed = 0.15f
    private var scale = 1.0f
    private var axis = 0.0f

    private var timeOffset = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        timeOffset += speed * dt
        if (timeOffset > 1000.0f) timeOffset -= 1000.0f

        // `static_cast<int>(axis_ + 0.5f)`, the same truncating round-half-up
        // RainbowRenderer's direction uses.
        val ax = (axis + 0.5f).toInt()
        val palette = (if (paletteId < 0.0f) 0.0f else paletteId).toInt()

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val pos = if (ax == 1) c.y else c.x

            var t = pos * scale + timeOffset
            // A ramp is a fixed 0..255 span, not a wheel, but scrolling it
            // still wants a position that wraps rather than clamping at the
            // last stop.
            t -= floor(t)

            out.setPacked(i, Palettes.sample(palette, (t * 255.0f).toInt()))
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> paletteId = value
            1 -> speed = value
            2 -> scale = value
            3 -> axis = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> paletteId
        1 -> speed
        2 -> scale
        3 -> axis
        else -> 0.0f
    }
}
