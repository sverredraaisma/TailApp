package com.tailapp.led

/**
 * Base class for a single rendered effect layer.
 *
 * Mirrors the `LedEffect` interface (`main/led/led_effect.h`): the four
 * transform flags, plus the `render`/`set_param`/`get_param` trio every
 * concrete effect implements. Parameter ids are `0..7`, matching the FF03/FF04
 * wire format's fixed 8-slot parameter block.
 */
abstract class LedEffectRenderer {
    var flipX: Boolean = false
    var flipY: Boolean = false
    var mirrorX: Boolean = false
    var mirrorY: Boolean = false

    /**
     * Applies this layer's flip/mirror transform to a coordinate. Mirrors
     * `LedEffect::transform_coord`: mirror is applied *before* flip, and
     * mirroring folds a coordinate around its axis midpoint (`0.5`) rather
     * than reflecting it end-to-end - e.g. mirrorX turns `x=0.2` into `x=0.4`,
     * not `x=0.8`. Applying flip first would change the result whenever both
     * are set, so the order here is load-bearing, not cosmetic.
     */
    protected fun transformCoord(c: LedCoord): LedCoord {
        var x = c.x
        var y = c.y
        if (mirrorX) x = if (x <= 0.5f) x * 2.0f else (1.0f - x) * 2.0f
        if (mirrorY) y = if (y <= 0.5f) y * 2.0f else (1.0f - y) * 2.0f
        if (flipX) x = 1.0f - x
        if (flipY) y = 1.0f - y
        return LedCoord(x, y)
    }

    /**
     * Renders this effect into [out]. [coords] is parallel to [out] (same
     * indices, one entry per LED); [dt] is seconds since the previous frame,
     * for effects that carry running state (rainbow phase, audio decay, ...).
     */
    abstract fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float)

    /** Sets effect-specific parameter [id] (`0..7`). Unknown ids are ignored. */
    abstract fun setParam(id: Int, value: Float)

    /** Reads effect-specific parameter [id] (`0..7`). Unknown ids return `0f`. */
    abstract fun getParam(id: Int): Float
}
