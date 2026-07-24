package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.Noise
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/candle_flicker_effect.cpp`. Params:
 * 0=colour temperature (0=deep amber, 1=pale warm white), 1=intensity
 * (brightness ceiling), 2=wind (how much fast jitter mixes into the slow drift).
 *
 * The level never reaches 0: real candles gutter, they don't blink off.
 */
class CandleFlickerRenderer : LedEffectRenderer() {
    private var colorTemp = 0.5f
    private var intensity = 0.85f
    private var wind = 0.2f

    private var time = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        time += dt
        if (time > 100000.0f) time -= 100000.0f

        val w = wind.coerceIn(0.0f, 1.0f)

        // Two octaves: a slow drift for the flame's breathing, and a faster one
        // wind mixes in for gusts - a candle in a draft flickers noticeably
        // faster, not just brighter.
        val slow = Noise.value1d(time * 1.3f, CANDLE_SEED_SLOW)
        val fast = Noise.value1d(time * 6.0f, CANDLE_SEED_FAST)
        val flicker = slow * (1.0f - w * 0.6f) + fast * w * 0.6f

        val ceiling = intensity.coerceIn(0.0f, 1.0f)
        val level = ceiling * (0.35f + 0.65f * flicker)

        val temp = colorTemp.coerceIn(0.0f, 1.0f)
        val r = LOW_R + (HIGH_R - LOW_R) * temp
        val g = LOW_G + (HIGH_G - LOW_G) * temp
        val b = LOW_B + (HIGH_B - LOW_B) * temp

        val cr = (r * level).coerceIn(0.0f, 255.0f).toInt()
        val cg = (g * level).coerceIn(0.0f, 255.0f).toInt()
        val cb = (b * level).coerceIn(0.0f, 255.0f).toInt()

        for (i in coords.indices) out.set(i, cr, cg, cb)
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> colorTemp = value
            1 -> intensity = value
            2 -> wind = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> colorTemp
        1 -> intensity
        2 -> wind
        else -> 0.0f
    }

    private companion object {
        const val CANDLE_SEED_SLOW = 307
        const val CANDLE_SEED_FAST = 308

        // `LOW_TEMP_COLOR` / `HIGH_TEMP_COLOR`: a guttering deep amber and a
        // fuller pale warm white. Both read as candle, never as cool.
        const val LOW_R = 255.0f
        const val LOW_G = 90.0f
        const val LOW_B = 20.0f
        const val HIGH_R = 255.0f
        const val HIGH_G = 200.0f
        const val HIGH_B = 130.0f
    }
}
