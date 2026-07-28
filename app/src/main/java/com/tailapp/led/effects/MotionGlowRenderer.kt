package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.MotionStateSource
import com.tailapp.led.Palettes
import com.tailapp.led.PixelBuffer
import kotlin.math.floor

/**
 * Mirrors `main/led/effects/motion_glow_effect.cpp`. Params: 0=palette id,
 * 1=speed gain (multiplies motion energy before it drives brightness),
 * 2=floor (minimum brightness at rest).
 *
 * Brightness tracks how fast the tail is physically moving and hue drifts with
 * how far axis 0 is deflected — the one category of effect that needs the
 * tail's own sensors rather than audio or a clock.
 */
class MotionGlowRenderer(private val motion: MotionStateSource) : LedEffectRenderer() {
    private var paletteId = 2.0f
    private var speedGain = 1.0f
    private var restFloor = 0.15f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        val floorLevel = restFloor.coerceIn(0.0f, 1.0f)
        var gain = motion.motionEnergy * speedGain
        if (gain < 0.0f) gain = 0.0f
        if (gain > 1.0f) gain = 1.0f
        val level = floorLevel + (1.0f - floorLevel) * gain

        // Axis 0's two halves average to the axis's overall lean; a single half
        // only reports that one motor's own travel.
        val deflect = 0.5f * (motion.position(0) + motion.position(1))

        // Degrees across a fixed +-90 window - the default axis-0 travel - so a
        // full swing cycles the palette once instead of clipping at one end.
        var t = (deflect + SPAN_DEG * 0.5f) / SPAN_DEG
        t -= floor(t)

        // `static_cast<uint8_t>` on the device: an id above 255 wraps rather
        // than being rejected, so 260 selects palette 4 there. Masking here too
        // keeps the preview from blanking where the tail shows a palette.
        val palette = (if (paletteId < 0.0f) 0.0f else paletteId).toInt() and 0xFF
        val base = Palettes.sample(palette, (t * 255.0f).toInt())

        val r = (((base shr 16) and 0xFF) * level).toInt()
        val g = (((base shr 8) and 0xFF) * level).toInt()
        val b = ((base and 0xFF) * level).toInt()

        for (i in coords.indices) out.set(i, r, g, b)
    }

    override fun setParam(id: Int, value: Float) {
        when (id) {
            0 -> paletteId = value
            1 -> speedGain = value
            2 -> restFloor = value
        }
    }

    override fun getParam(id: Int): Float = when (id) {
        0 -> paletteId
        1 -> speedGain
        2 -> restFloor
        else -> 0.0f
    }

    private companion object {
        const val SPAN_DEG = 180.0f
    }
}
