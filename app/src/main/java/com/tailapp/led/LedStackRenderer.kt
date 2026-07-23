package com.tailapp.led

import com.tailapp.led.effects.ImageRenderer
import com.tailapp.model.BlendMode
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedState

/**
 * Pixel data for an uploaded image, supplied by the app rather than the
 * device: the firmware never reports uploaded image bytes back over FF04 or
 * FF06, only the fact that layer's effect is Image (`effect_id == 0x02`).
 */
data class ImageData(val rgb: ByteArray, val width: Int, val height: Int)

/**
 * Drives one full render frame from the device's reported [LedState]: builds
 * the coordinate layout ([LedLayout]), keeps one [LedEffectRenderer] per
 * occupied layer slot, and composites them ([LayerCompositor]) every frame.
 * This is what the live effect-stack preview (and, later, the FF0A direct
 * pixel streamer) drives every tick.
 *
 * ### What forces a rebuild
 * Rebuilding a layer's renderer discards its running state - `time_offset_`,
 * `current_brightness_`/`current_level_`, `bar_levels_`. On real hardware
 * that only happens when `layer_compositor.cpp`'s `set_layer` replaces the
 * slot's `unique_ptr<LedEffect>` outright, which is exactly what
 * `LCMD_SET_LAYER_EFFECT` (a new effect id) and `LCMD_REMOVE_LAYER` do. A
 * parameter change (`LCMD_SET_EFFECT_PARAM`) just calls `set_param` on the
 * *existing* object, and transform/blend-mode/enabled changes touch fields
 * the compositor or the base `LedEffect` hold outside the effect subclass
 * entirely - none of those reset anything on real hardware.
 *
 * [setState] mirrors that split: a layer slot is rebuilt only when its
 * **effect id** changes (including transitioning to/from empty). A layer
 * whose effect id is unchanged is updated in place - new params via
 * `setParam`, new flip/mirror flags by direct assignment, new blend
 * mode/enabled by updating the compositor slot - with its running state
 * intact, matching the firmware's behaviour rather than just its wire format.
 */
class LedStackRenderer(
    private val audio: AudioLevelSource,
    private val imageSupplier: () -> ImageData?,
) {
    private var ledsPerRing: List<Int> = emptyList()
    private var coords: List<LedCoord> = emptyList()
    private var frameBuffer = PixelBuffer(0)

    private var layers: List<LayerCompositor.Layer> = emptyList()
    private var layerEffectIds: List<Byte> = emptyList()

    private val compositor = LayerCompositor()

    fun setState(state: LedState) {
        if (state.ledsPerRing != ledsPerRing) {
            ledsPerRing = state.ledsPerRing
            coords = LedLayout.coordsFor(ledsPerRing)
            frameBuffer = PixelBuffer(coords.size)
        }

        val newLayers = ArrayList<LayerCompositor.Layer>(state.layers.size)
        val newEffectIds = ArrayList<Byte>(state.layers.size)

        for ((index, config) in state.layers.withIndex()) {
            val previousRenderer = layers.getOrNull(index)?.renderer
            val previousEffectId = layerEffectIds.getOrNull(index)

            val renderer = if (previousRenderer != null && previousEffectId == config.effectId) {
                updateInPlace(previousRenderer, config)
                previousRenderer
            } else {
                FirmwareEffectFactory.create(config, audio)
            }

            val blendMode = config.blend ?: BlendMode.OVERWRITE
            newLayers.add(LayerCompositor.Layer(renderer, blendMode, config.enabled))
            newEffectIds.add(config.effectId)
        }

        layers = newLayers
        layerEffectIds = newEffectIds
    }

    private fun updateInPlace(renderer: LedEffectRenderer, config: LayerConfig) {
        config.params.forEachIndexed { id, value -> renderer.setParam(id, value) }
        renderer.flipX = config.flipX
        renderer.flipY = config.flipY
        renderer.mirrorX = config.mirrorX
        renderer.mirrorY = config.mirrorY
    }

    fun renderFrame(dtSeconds: Float): PixelBuffer {
        // The device never echoes uploaded image bytes back, so feed whatever
        // is currently staged/uploaded into any live Image layer every frame -
        // it can change without a LedState update, e.g. mid-upload preview.
        val image = imageSupplier()
        if (image != null) {
            for (layer in layers) {
                (layer.renderer as? ImageRenderer)?.setImage(image.rgb, image.width, image.height)
            }
        }

        compositor.render(layers, coords, dtSeconds, frameBuffer)
        return frameBuffer
    }
}
