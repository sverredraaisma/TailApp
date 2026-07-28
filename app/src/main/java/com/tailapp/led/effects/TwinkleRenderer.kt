package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.Noise
import com.tailapp.led.Palettes
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/twinkle_effect.cpp`. Params: 0=density (fraction
 * of pixels sparkling at any moment), 1=fade speed (sparkle events per
 * second), 2=palette id.
 *
 * Every "does this pixel sparkle" and "how bright" decision comes from a hash
 * of (led index, time slot) rather than an RNG, so a sparkle holds still for
 * its whole fade instead of re-rolling per frame — see [Noise].
 */
class TwinkleRenderer : LedEffectRenderer() {
    private var density = 0.12f
    private var fadeSpeed = 1.0f
    private var paletteId = 2.0f

    private var time = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        time += dt
        if (time > 100000.0f) time -= 100000.0f

        // One slot is one candidate sparkle's whole fade-in/fade-out lifetime.
        val period = 1.0f / (if (fadeSpeed < 0.05f) 0.05f else fadeSpeed)
        val slotTime = time / period
        val tick = slotTime.toInt()
        val local = slotTime - tick.toFloat()

        val d = density.coerceIn(0.0f, 1.0f)
        // `static_cast<uint8_t>` on the device: an id above 255 wraps rather
        // than being rejected, so 260 selects palette 4 there. Masking here too
        // keeps the preview from blanking where the tail shows a palette.
        val palette = (if (paletteId < 0.0f) 0.0f else paletteId).toInt() and 0xFF

        for (i in coords.indices) {
            // The same (index, tick) hash decides both whether this pixel
            // sparkles and where its brightness peaks - a pixel that loses the
            // roll contributes nothing rather than borrowing a neighbour's.
            val roll = Noise.hash01(Noise.hash(i, tick), TWINKLE_SEED)
            if (roll >= d) {
                out.set(i, 0, 0, 0)
                continue
            }

            val peakRaw = Noise.hash01(Noise.hash(i, tick), TWINKLE_SEED + 1)
            // Kept off both edges so every sparkle has a real rise and fall.
            val peak = 0.05f + peakRaw * 0.9f

            var level = if (local < peak) local / peak else (1.0f - local) / (1.0f - peak)
            if (level < 0.0f) level = 0.0f
            if (level > 1.0f) level = 1.0f

            // Fixed per pixel, independent of tick: "that one's the red star"
            // reads better than a fresh hue on every event.
            val hueRoll = Noise.hash01(i, TWINKLE_SEED + 2)
            val base = Palettes.sample(palette, (hueRoll * 255.0f).toInt())

            out.set(
                i,
                (((base shr 16) and 0xFF) * level).toInt(),
                (((base shr 8) and 0xFF) * level).toInt(),
                ((base and 0xFF) * level).toInt()
            )
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> density = value
            1 -> fadeSpeed = value
            2 -> paletteId = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> density
        1 -> fadeSpeed
        2 -> paletteId
        else -> 0.0f
    }

    private companion object {
        const val TWINKLE_SEED = 211
    }
}
