package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.Noise
import com.tailapp.led.Palettes
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/fire_effect.cpp`. Params: 0=intensity, 1=speed
 * (noise-field scroll rate, field-units/s), 2=cooling (heat lost per unit of
 * height; higher = shorter flames), 3=palette id.
 */
class FireRenderer : LedEffectRenderer() {
    private var intensity = 1.0f
    private var speed = 0.6f
    private var cooling = 1.2f
    private var paletteId = 0.0f

    // Persists across render() calls, same as the firmware's `time_`.
    private var time = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        time += speed * dt
        // Keep the accumulator from growing unbounded, same guard as Rainbow.
        if (time > 100000.0f) time -= 100000.0f

        val level = intensity.coerceIn(0.0f, 1.0f)
        val cool = if (cooling < 0.0f) 0.0f else cooling
        // `static_cast<uint8_t>` on the device: an id above 255 wraps rather
        // than being rejected, so 260 selects palette 4 there. Masking here too
        // keeps the preview from blanking where the tail shows a palette.
        val palette = (if (paletteId < 0.0f) 0.0f else paletteId).toInt() and 0xFF

        for (i in coords.indices) {
            val c = transformCoord(coords[i])

            // Sampled moving backward through y as time advances, so the
            // turbulence climbs the strip rather than the strip scrolling past.
            val n = Noise.value2d(c.x * 3.0f, c.y * 4.0f - time, FIRE_SEED)

            var falloff = 1.0f - c.y * cool
            if (falloff < 0.0f) falloff = 0.0f

            var heat = n * falloff * level
            if (heat < 0.0f) heat = 0.0f
            if (heat > 1.0f) heat = 1.0f

            out.setPacked(i, Palettes.sample(palette, (heat * 255.0f).toInt()))
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> intensity = value
            1 -> speed = value
            2 -> cooling = value
            3 -> paletteId = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> intensity
        1 -> speed
        2 -> cooling
        3 -> paletteId
        else -> 0.0f
    }

    private companion object {
        /** `FIRE_SEED` — distinct from every other noise-driven effect's field. */
        const val FIRE_SEED = 101
    }
}
