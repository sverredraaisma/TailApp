package com.tailapp.led.effects

import com.tailapp.led.AudioLevelSource
import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/audio_freq_bars_effect.cpp`. Params: 0=number of
 * bars, 1=red, 2=green, 3=blue, 4=fade rate, 5=orientation (0=horizontal bars
 * with vertical height, 1=vertical bars with horizontal height).
 *
 * Each bar independently tracks the loudest bin in its slice of the FFT
 * spectrum and decays on its own, so `bar_levels_` is per-bar running state -
 * exactly like `current_brightness_`/`current_level_` in the power/bar
 * effects, just `MAX_BARS`-wide instead of a single float.
 */
class AudioFreqBarsRenderer(private val audio: AudioLevelSource) : LedEffectRenderer() {
    private var numBars = 8.0f
    private var red = 0.0f
    private var green = 255.0f
    private var blue = 0.0f
    private var fadeRate = 5.0f
    private var orientation = 0.0f

    private val barLevels = FloatArray(MAX_BARS)

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        val bars = (numBars.coerceIn(1.0f, MAX_BARS.toFloat()) + 0.5f).toInt()

        if (audio.isFresh) {
            val numBins = audio.numBins
            for (bar in 0 until bars) {
                // Map this bar to a range of FFT bins.
                var binStart = (bar * numBins) / bars
                var binEnd = ((bar + 1) * numBins) / bars
                if (binEnd <= binStart) binEnd = binStart + 1

                var maxVal = 0
                var k = binStart
                while (k < binEnd && k < numBins) {
                    val v = audio.bin(k)
                    if (v > maxVal) maxVal = v
                    k++
                }

                val fftLevel = maxVal / 255.0f
                if (fftLevel > barLevels[bar]) barLevels[bar] = fftLevel
            }
        }

        // Decay all currently-active bars. A bar index beyond the current
        // `bars` count is left untouched until it's active again - the
        // firmware has the same quirk (its decay loop is also `< bars`).
        for (bar in 0 until bars) {
            barLevels[bar] -= fadeRate * dt
            if (barLevels[bar] < 0.0f) barLevels[bar] = 0.0f
        }

        val orient = (orientation + 0.5f).toInt()

        val r = red.coerceIn(0.0f, 255.0f).toInt()
        val g = green.coerceIn(0.0f, 255.0f).toInt()
        val b = blue.coerceIn(0.0f, 255.0f).toInt()
        val litColor = (r shl 16) or (g shl 8) or b

        for (i in coords.indices) {
            val c = transformCoord(coords[i])

            val barAxis: Float
            val heightAxis: Float
            if (orient == 0) {
                barAxis = c.x; heightAxis = c.y
            } else {
                barAxis = c.y; heightAxis = c.x
            }

            var barIdx = (barAxis * bars).toInt()
            if (barIdx >= bars) barIdx = bars - 1
            if (barIdx < 0) barIdx = 0

            val level = barLevels[barIdx]
            if (heightAxis <= level) out.setPacked(i, litColor) else out.set(i, 0, 0, 0)
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> numBars = value
            1 -> red = value
            2 -> green = value
            3 -> blue = value
            4 -> fadeRate = value
            5 -> orientation = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> numBars
        1 -> red
        2 -> green
        3 -> blue
        4 -> fadeRate
        5 -> orientation
        else -> 0.0f
    }

    companion object {
        const val MAX_BARS = 32
    }
}
