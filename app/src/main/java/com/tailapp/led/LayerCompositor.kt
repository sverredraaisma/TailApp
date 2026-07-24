package com.tailapp.led

import com.tailapp.model.BlendMode

/**
 * Mirrors `LayerCompositor` (`main/led/layer_compositor.cpp` / `.h`): renders
 * each enabled layer bottom-to-top (list index 0 first) into a scratch
 * buffer, then blends that layer's output into the accumulated result.
 */
class LayerCompositor {

    /** One compositor slot. `renderer == null` mirrors a layer whose `effect` pointer is null. */
    data class Layer(
        val renderer: LedEffectRenderer?,
        val blendMode: BlendMode,
        val enabled: Boolean = true,
        /** Per-layer mix, 0-255, mirroring the firmware's `Layer::opacity`. */
        val opacity: Int = 255,
    )

    // Reused across frames instead of allocated fresh each render(), mirroring
    // `temp_buffer_`'s `resize(count)` reuse in the firmware.
    private var scratch: PixelBuffer? = null

    /**
     * Renders [layers] over [coords] into [out]. [out] must already be sized
     * to `coords.size`. A layer that is disabled or has no renderer
     * contributes nothing - not even black - exactly like the firmware
     * skipping `!layer.enabled || !layer.effect`.
     */
    fun render(layers: List<Layer>, coords: List<LedCoord>, dt: Float, out: PixelBuffer) {
        require(out.ledCount == coords.size) {
            "out buffer size ${out.ledCount} does not match coords size ${coords.size}"
        }

        // Output starts black, matching `std::vector<RGB> output(count, RGB::black())`.
        out.clear()
        if (coords.isEmpty()) return

        val temp = scratchBuffer(coords.size)

        for (layer in layers) {
            val renderer = layer.renderer
            if (!layer.enabled || renderer == null) continue

            temp.clear()
            renderer.render(temp, coords, dt)

            for (i in coords.indices) {
                out.setPacked(
                    i,
                    ColorMath.blend(out.packed(i), temp.packed(i), layer.blendMode, layer.opacity)
                )
            }
        }
    }

    private fun scratchBuffer(size: Int): PixelBuffer {
        val current = scratch
        if (current != null && current.ledCount == size) return current
        return PixelBuffer(size).also { scratch = it }
    }
}
