package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer
import kotlin.math.exp

/**
 * Mirrors `main/led/effects/comet_effect.cpp`. Params: 0=red, 1=green, 2=blue,
 * 3=speed (strip-lengths/s; sign sets direction), 4=tail length (exponential
 * decay length, in strip-lengths), 5=bounce (>0.5 reflects at the ends
 * instead of wrapping).
 */
class CometRenderer : LedEffectRenderer() {
    private var red = 0.0f
    private var green = 160.0f
    private var blue = 255.0f
    private var speed = 0.4f
    private var tailLength = 0.25f
    private var bounce = 0.0f

    private var pos = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        pos += speed * dt
        // Reduced every frame rather than past some large threshold: bounce
        // mode's triangle wave needs pos in a small, well-defined range to read
        // off which leg - and so which direction - the head is on.
        pos %= 2.0f

        val bouncing = bounce > 0.5f
        val baseDir = if (speed >= 0.0f) 1.0f else -1.0f

        val head: Float
        val travelDir: Float
        if (bouncing) {
            var m = pos
            if (m < 0.0f) m += 2.0f
            if (m <= 1.0f) {
                head = m
                travelDir = baseDir
            } else {
                head = 2.0f - m
                travelDir = -baseDir
            }
        } else {
            var m = pos % 1.0f
            if (m < 0.0f) m += 1.0f
            head = m
            travelDir = baseDir
        }

        val tail = if (tailLength < 0.001f) 0.001f else tailLength

        val r = red.coerceIn(0.0f, 255.0f).toInt()
        val g = green.coerceIn(0.0f, 255.0f).toInt()
        val b = blue.coerceIn(0.0f, 255.0f).toInt()

        for (i in coords.indices) {
            val c = transformCoord(coords[i])

            // In wrap mode a pixel "ahead" of the head is just behind it from
            // the other side; in bounce mode the head turned around before
            // running off the strip, so there is nothing to wrap.
            var distance = if (travelDir > 0.0f) head - c.y else c.y - head
            if (!bouncing && distance < 0.0f) distance += 1.0f

            var level = if (distance >= 0.0f) exp(-distance / tail) else 0.0f
            if (level > 1.0f) level = 1.0f

            out.set(i, (r * level).toInt(), (g * level).toInt(), (b * level).toInt())
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> red = value
            1 -> green = value
            2 -> blue = value
            3 -> speed = value
            4 -> tailLength = value
            5 -> bounce = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> red
        1 -> green
        2 -> blue
        3 -> speed
        4 -> tailLength
        5 -> bounce
        else -> 0.0f
    }
}
