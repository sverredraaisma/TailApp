package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.MotionStateSource
import com.tailapp.led.PixelBuffer

/**
 * Mirrors `main/led/effects/tap_ripple_effect.cpp`. Params: 0=red, 1=green,
 * 2=blue, 3=speed (strip-lengths/s), 4=width (ring thickness, in
 * strip-lengths).
 *
 * A tap at either end sends one ring of light travelling away from that end.
 * The tap flags are take-and-clear, so a tap fires the ripple exactly once
 * however many frames run before the next one.
 */
class TapRippleRenderer(private val motion: MotionStateSource) : LedEffectRenderer() {
    private var red = 0.0f
    private var green = 180.0f
    private var blue = 255.0f
    private var speed = 1.2f
    private var width = 0.3f

    private var active = false
    private var origin = 0.0f
    private var progress = 0.0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        val tappedBase = motion.takeTapBase()
        val tappedTip = motion.takeTapTip()

        if (tappedBase || tappedTip) {
            // Both firing on the same frame is a coincidence, not a sequence to
            // honour; the tip tap wins arbitrarily rather than spawning two
            // ripples this effect has no slot for.
            origin = if (tappedTip) 1.0f else 0.0f
            progress = 0.0f
            active = true
        }

        if (!active) {
            for (i in coords.indices) out.set(i, 0, 0, 0)
            return
        }

        val w = if (width < 0.01f) 0.01f else width
        progress += speed * dt

        // Gone once even the trailing edge has passed the far end - one full
        // strip length plus the ring's own width.
        if (progress > 1.0f + w) {
            active = false
            for (i in coords.indices) out.set(i, 0, 0, 0)
            return
        }

        val r = red.coerceIn(0.0f, 255.0f).toInt()
        val g = green.coerceIn(0.0f, 255.0f).toInt()
        val b = blue.coerceIn(0.0f, 255.0f).toInt()

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val dist = if (c.y > origin) c.y - origin else origin - c.y

            // How far behind the ring's leading edge this pixel sits; inside
            // the ring it is brightest at the edge.
            val ringPos = progress - dist
            val level = if (ringPos >= 0.0f && ringPos <= w) 1.0f - ringPos / w else 0.0f

            out.set(i, (r * level).toInt(), (g * level).toInt(), (b * level).toInt())
        }
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> red = value
            1 -> green = value
            2 -> blue = value
            3 -> speed = value
            4 -> width = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> red
        1 -> green
        2 -> blue
        3 -> speed
        4 -> width
        else -> 0.0f
    }
}
