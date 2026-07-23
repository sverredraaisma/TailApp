package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/static_color_effect.cpp`. Params: 0=red, 1=green,
 * 2=blue, each clamped to `0..255` before the `(uint8_t)` truncation - matching
 * `std::max(0.0f, std::min(255.0f, red_))` in the firmware.
 */
class StaticColorRenderer : LedEffectRenderer() {
    private var red = 255.0f
    private var green = 255.0f
    private var blue = 255.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        val r = red.coerceIn(0.0f, 255.0f).toInt()
        val g = green.coerceIn(0.0f, 255.0f).toInt()
        val b = blue.coerceIn(0.0f, 255.0f).toInt()

        for (i in coords.indices) out.set(i, r, g, b)
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> red = value
            1 -> green = value
            2 -> blue = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> red
        1 -> green
        2 -> blue
        else -> 0.0f
    }
}
