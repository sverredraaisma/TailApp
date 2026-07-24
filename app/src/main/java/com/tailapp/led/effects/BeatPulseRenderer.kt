package com.tailapp.led.effects

import com.tailapp.led.AudioLevelSource
import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer
import kotlin.math.abs

/**
 * Mirrors `main/led/effects/beat_pulse_effect.cpp`. Params: 0=red, 1=green,
 * 2=blue, 3=decay rate (brightness lost per second), 4=downbeat boost,
 * 5=sweep (>0.5 travels base-to-tip as it decays, else flashes the whole strip).
 *
 * The device has no beat detector; this renders the beat the app streams in the
 * FF05 trailer. With no trailer there is no beat, and the effect stays dark
 * rather than inventing a tempo — the preview has to show that too, or it would
 * flash where the hardware would not.
 */
class BeatPulseRenderer(private val audio: AudioLevelSource) : LedEffectRenderer() {
    private var red = 255.0f
    private var green = 255.0f
    private var blue = 255.0f
    private var decayRate = 4.0f
    private var downbeatBoost = 1.0f
    private var sweep = 0.0f

    // Persists across render() calls, same as the firmware's `brightness_`.
    private var brightness = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        if (audio.hasBeatInfo) {
            val downbeat = audio.takeDownbeat()
            if (audio.takeBeat() || downbeat) {
                brightness = if (downbeat) downbeatBoost else 1.0f
            }
        }

        brightness -= decayRate * dt
        if (brightness < 0.0f) brightness = 0.0f

        if (brightness <= 0.0f) {
            for (i in coords.indices) out.set(i, 0, 0, 0)
            return
        }

        val r = red.coerceIn(0.0f, 255.0f)
        val g = green.coerceIn(0.0f, 255.0f)
        val b = blue.coerceIn(0.0f, 255.0f)

        for (i in coords.indices) {
            var level = brightness

            if (sweep > 0.5f) {
                val c = transformCoord(coords[i])
                val front = 1.0f - brightness
                var shape = 1.0f - abs(c.y - front) * 4.0f
                if (shape < 0.0f) shape = 0.0f
                level *= shape
            }

            // Truncated, matching the firmware's static_cast<uint8_t>.
            out.set(i, (r * level).toInt(), (g * level).toInt(), (b * level).toInt())
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> red = value
            1 -> green = value
            2 -> blue = value
            3 -> decayRate = value
            4 -> downbeatBoost = value
            5 -> sweep = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> red
        1 -> green
        2 -> blue
        3 -> decayRate
        4 -> downbeatBoost
        5 -> sweep
        else -> 0.0f
    }
}
