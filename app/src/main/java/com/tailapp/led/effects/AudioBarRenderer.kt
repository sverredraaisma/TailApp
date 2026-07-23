package com.tailapp.led.effects

import com.tailapp.led.AudioLevelSource
import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/audio_bar_effect.cpp`. Params: 0=red, 1=green,
 * 2=blue, 3=direction (0=left-to-right/x, 1=bottom-to-top/y), 4=fade rate.
 * Lights every LED whose position along [direction]'s axis is at or below the
 * current audio level, like a VU meter bar.
 */
class AudioBarRenderer(private val audio: AudioLevelSource) : LedEffectRenderer() {
    private var red = 0.0f
    private var green = 0.0f
    private var blue = 255.0f
    private var direction = 0.0f
    private var fadeRate = 3.0f

    // Persists across render() calls, same as the firmware's `current_level_`.
    private var currentLevel = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        if (audio.isFresh) {
            val loudness = audio.loudnessNormalized
            if (loudness > currentLevel) currentLevel = loudness
        } else {
            currentLevel -= fadeRate * dt
            if (currentLevel < 0.0f) currentLevel = 0.0f
        }

        val dir = (direction + 0.5f).toInt()

        val r = red.coerceIn(0.0f, 255.0f).toInt()
        val g = green.coerceIn(0.0f, 255.0f).toInt()
        val b = blue.coerceIn(0.0f, 255.0f).toInt()
        val litColor = (r shl 16) or (g shl 8) or b

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val axis = if (dir == 1) c.y else c.x

            if (axis <= currentLevel) out.setPacked(i, litColor) else out.set(i, 0, 0, 0)
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> red = value
            1 -> green = value
            2 -> blue = value
            3 -> direction = value
            4 -> fadeRate = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> red
        1 -> green
        2 -> blue
        3 -> direction
        4 -> fadeRate
        else -> 0.0f
    }
}
