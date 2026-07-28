package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.Palettes
import com.tailapp.led.PixelBuffer
import kotlin.math.sin

/**
 * Mirrors `main/led/effects/plasma_effect.cpp`. Params: 0=palette id, 1=scale
 * (cycles across the strip), 2=speed (field evolution rate, cycles/s).
 *
 * Plain sine rather than [com.tailapp.led.Noise]: this wants unbroken
 * interference bands, which a lattice-interpolated field cannot give.
 */
class PlasmaRenderer : LedEffectRenderer() {
    private var paletteId = 3.0f
    private var scale = 2.0f
    private var speed = 0.3f

    private var time = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        time += speed * dt
        if (time > 100000.0f) time -= 100000.0f

        // `static_cast<uint8_t>` on the device: an id above 255 wraps rather
        // than being rejected, so 260 selects palette 4 there. Masking here too
        // keeps the preview from blanking where the tail shows a palette.
        val palette = (if (paletteId < 0.0f) 0.0f else paletteId).toInt() and 0xFF

        for (i in coords.indices) {
            val c = transformCoord(coords[i])

            // Three fields at different spatial and temporal rates, so the
            // interference doesn't repeat on an obviously short cycle.
            val v = sin((c.x * scale + time) * TWO_PI) +
                sin((c.y * scale - time * 0.8f) * TWO_PI) +
                sin(((c.x + c.y) * scale * 0.5f + time * 0.6f) * TWO_PI)

            var n = (v + 3.0f) / 6.0f
            if (n < 0.0f) n = 0.0f
            if (n > 1.0f) n = 1.0f

            out.setPacked(i, Palettes.sample(palette, (n * 255.0f).toInt()))
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> paletteId = value
            1 -> scale = value
            2 -> speed = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> paletteId
        1 -> scale
        2 -> speed
        else -> 0.0f
    }

    private companion object {
        const val TWO_PI = 6.28318530718f
    }
}
