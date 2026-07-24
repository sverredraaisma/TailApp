package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer
import kotlin.math.cos
import kotlin.math.floor

/**
 * Mirrors `main/led/effects/breathing_glow_effect.cpp`. Params: 0=red,
 * 1=green, 2=blue, 3=period (seconds per breath), 4=min brightness (0-1, the
 * level at the trough).
 */
class BreathingGlowRenderer : LedEffectRenderer() {
    private var red = 255.0f
    private var green = 220.0f
    private var blue = 180.0f
    private var period = 4.0f
    private var minBrightness = 0.08f

    // 0-1 through the current breath; starts at the trough, like the firmware.
    private var phase = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        // A period of 0 would divide by zero; treated as "as fast as
        // representable" rather than freezing on the trough.
        val p = if (period < 0.01f) 0.01f else period
        phase += dt / p
        phase -= floor(phase)

        val raised = 0.5f - 0.5f * cos(phase * TWO_PI)

        val minB = minBrightness.coerceIn(0.0f, 1.0f)
        val level = minB + (1.0f - minB) * raised

        // Clamped before the multiply, not after: the firmware clamps the
        // parameter and lets the level scale the clamped value.
        val r = (red.coerceIn(0.0f, 255.0f) * level).toInt()
        val g = (green.coerceIn(0.0f, 255.0f) * level).toInt()
        val b = (blue.coerceIn(0.0f, 255.0f) * level).toInt()

        for (i in coords.indices) out.set(i, r, g, b)
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> red = value
            1 -> green = value
            2 -> blue = value
            3 -> period = value
            4 -> minBrightness = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> red
        1 -> green
        2 -> blue
        3 -> period
        4 -> minBrightness
        else -> 0.0f
    }

    private companion object {
        const val TWO_PI = 6.28318530718f
    }
}
