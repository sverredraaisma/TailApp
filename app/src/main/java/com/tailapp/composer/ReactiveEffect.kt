package com.tailapp.composer

import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * One app-side lighting effect that renders a single layer from the live
 * [ReactiveContext] — beats, BPM, volume, the FFT spectrum, drops, sections and
 * genre are all reachable, so *every* effect is reactive, not just a handful.
 *
 * The app-side counterpart to [com.tailapp.led.LedEffectRenderer]: same
 * flip/mirror transform (the order is load-bearing — see [transformCoord]), same
 * "render into a buffer parallel to the coordinate list" contract. The two
 * differences are deliberate:
 *
 * 1. **Input.** [render] receives a [ReactiveContext] instead of a bare `dt`, so
 *    an effect reads the analysis directly rather than through the firmware's
 *    128-bin FFT buffer.
 * 2. **Parameters.** Values live in a schema-typed [ParamBag] ([p]) rather than
 *    a fixed 8-slot float block, so the editor can offer colour pickers,
 *    dropdowns and switches, not only sliders.
 *
 * Running state (decay envelopes, animation phase, per-bar counters) lives in
 * instance fields and is preserved across parameter edits, because
 * [CompositionRenderer] reuses the instance whenever the layer keeps the same
 * effect id — mirroring `LedStackRenderer`'s in-place update.
 *
 * Not thread-safe: one instance belongs to one render loop. It must never store
 * the [ReactiveContext] or its `bands` array beyond the [render] call — both are
 * reused between frames.
 */
abstract class ReactiveEffect(val spec: EffectSpec) {

    /** This instance's live parameter values, pre-filled with the schema defaults. */
    protected val p: ParamBag = ParamBag(spec.schema)

    var flipX: Boolean = false
    var flipY: Boolean = false
    var mirrorX: Boolean = false
    var mirrorY: Boolean = false

    /** Overwrites the known parameters from a persisted or edited map. */
    fun applyParams(values: Map<String, Float>) = p.setAll(values)

    /**
     * Applies this layer's flip/mirror transform to a coordinate, identically to
     * `LedEffectRenderer.transformCoord`: mirror is applied *before* flip, and a
     * mirror folds a coordinate around its axis midpoint (`0.5`) rather than
     * reflecting it end-to-end. The order matters whenever both are set.
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
     * indices, one entry per LED); [ctx] is the analysis snapshot for this frame.
     * The buffer is cleared to black before the call.
     */
    abstract fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext)
}
