package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * A bank of spectrum-analyser bars driven by the FFT.
 *
 * The app-side counterpart to the firmware's `audio_freq_bars` effect, and a
 * strictly better one: the firmware reads a 128-bin buffer shipped to it over
 * BLE, while this reads [ReactiveContext.bands] — the analysis front-end's own
 * log-spaced filterbank, at full resolution and with no round trip. Bands are
 * log-spaced by construction, so a linear slice of them is already a musically
 * sensible split rather than one that crams every octave above 5 kHz into the
 * last bar.
 *
 * Each bar keeps its own level and falls at `fadeRate`, so bars decay
 * independently — per-bar running state, exactly like the firmware's
 * `bar_levels_`.
 */
class SpectrumBarsEffect : ReactiveEffect(SPEC) {

    private val barLevels = FloatArray(MAX_BARS)

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val bars = p.float("bars").toInt().coerceIn(1, MAX_BARS)
        val gain = p.float("gain")
        val fade = p.float("fadeRate") * ctx.dtSeconds

        if (ctx.bandCount > 0) {
            for (bar in 0 until bars) {
                // Widest band in this bar's slice — a peak reads better than a
                // mean, which washes a single strong partial out.
                var peak = 0f
                val from = bar * ctx.bandCount / bars
                val to = ((bar + 1) * ctx.bandCount / bars).coerceAtLeast(from + 1)
                var k = from
                while (k < to && k < ctx.bandCount) {
                    val v = ctx.band(k)
                    if (v > peak) peak = v
                    k++
                }
                val level = (peak * gain).coerceIn(0f, 1f)
                if (level > barLevels[bar]) barLevels[bar] = level
            }
        }

        for (bar in 0 until bars) {
            barLevels[bar] = (barLevels[bar] - fade).coerceAtLeast(0f)
        }

        val barAxisIsRing = p.enumIndex("barAxis") == 0
        val low = p.color("colorLow")
        val high = p.color("colorHigh")
        val brightness = p.float("brightness")
        val denominator = (bars - 1).coerceAtLeast(1).toFloat()

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            // One axis selects the bar, the other is its height.
            val barAxis = if (barAxisIsRing) c.x else c.y
            val heightAxis = if (barAxisIsRing) c.y else c.x

            val barIdx = (barAxis * bars).toInt().coerceIn(0, bars - 1)
            if (heightAxis > barLevels[barIdx]) continue

            // Colour by frequency, so the palette maps to the spectrum itself.
            val colour = EffectColors.lerp(low, high, barIdx / denominator)
            out.setPacked(i, EffectColors.scale(colour, brightness))
        }
    }

    companion object {
        /** Matches the firmware effect's own cap. */
        const val MAX_BARS = 32

        val SPEC = EffectSpec(
            id = "spectrum_bars",
            displayName = "Spectrum Bars",
            category = EffectCategory.AUDIO,
            schema = listOf(
                EffectParam.Scalar("bars", "Bars", 8f, 1f, MAX_BARS.toFloat(), step = 1f),
                EffectParam.Choice("barAxis", "Bars run", listOf("Around ring", "Along tail"), 0),
                EffectParam.Color("colorLow", "Low freq", 0xFF2000),
                EffectParam.Color("colorHigh", "High freq", 0x00A0FF),
                EffectParam.Scalar("gain", "Gain", 1.2f, 0.1f, 5f),
                EffectParam.Scalar("fadeRate", "Fall", 1.5f, 0.1f, 10f, unit = "/s"),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { SpectrumBarsEffect() }
        )
    }
}
