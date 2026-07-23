package com.tailapp.led.effects

import com.tailapp.led.AudioLevelSource
import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/audio_power_effect.cpp`. Params: 0=red, 1=green,
 * 2=blue, 3=fade rate (brightness decay/sec while stale). The whole strip is
 * one solid colour scaled by the current brightness envelope.
 */
class AudioPowerRenderer(private val audio: AudioLevelSource) : LedEffectRenderer() {
    private var red = 0.0f
    private var green = 255.0f
    private var blue = 0.0f
    private var fadeRate = 3.0f

    // Persists across render() calls, same as the firmware's `current_brightness_`.
    private var currentBrightness = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        if (audio.isFresh) {
            // Snap up instantly on a loud frame, never snap down - decay handles that.
            val loudness = audio.loudnessNormalized
            if (loudness > currentBrightness) currentBrightness = loudness
        } else {
            currentBrightness -= fadeRate * dt
            if (currentBrightness < 0.0f) currentBrightness = 0.0f
        }

        // Clamp before multiplying, then truncate - matches the firmware's
        // `static_cast<uint8_t>(clamp(...) * current_brightness_)` ordering.
        val r = (red.coerceIn(0.0f, 255.0f) * currentBrightness).toInt()
        val g = (green.coerceIn(0.0f, 255.0f) * currentBrightness).toInt()
        val b = (blue.coerceIn(0.0f, 255.0f) * currentBrightness).toInt()

        for (i in coords.indices) out.set(i, r, g, b)
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> red = value
            1 -> green = value
            2 -> blue = value
            3 -> fadeRate = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> red
        1 -> green
        2 -> blue
        3 -> fadeRate
        else -> 0.0f
    }
}
