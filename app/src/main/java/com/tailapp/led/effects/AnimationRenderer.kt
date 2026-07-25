package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.LedEffectRenderer
import com.tailapp.led.PixelBuffer

/**
 * Stands in for the firmware's `EFFECT_ANIMATION` (LED-5) in the app.
 *
 * The animation's frames live in a device flash slot, uploaded over the FF03
 * animation flow; they never reach the phone, so the composer's live preview
 * has no pixels to show. This renders **black** rather than inventing a
 * placeholder animation — a preview that made something up would be the exact
 * "quietly lies" failure the parity port exists to prevent, and black is the
 * honest "nothing to preview here" that matches how an empty slot renders on
 * the device anyway.
 *
 * The parameters are still stored and reported, so the effect can be selected
 * and configured (slot, speed, loop, beat lock) and installed onto the tail,
 * where it plays for real. Ranges mirror `AnimationEffect::describe_params()`.
 */
class AnimationRenderer : LedEffectRenderer() {
    private val params = floatArrayOf(0f, 1f, 1f, 0f, 4f, 0f)

    override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {
        for (i in coords.indices) out.set(i, 0, 0, 0)
    }

    override fun setParam(id: Int, value: Float) {
        if (id in params.indices) params[id] = value
    }

    override fun getParam(id: Int): Float = params.getOrElse(id) { 0f }
}
