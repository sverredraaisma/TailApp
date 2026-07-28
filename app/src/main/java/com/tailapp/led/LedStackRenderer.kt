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
 * This is what the live effect-stack preview drives every tick. It is
 * deliberately *not* what an FF0A direct pixel streamer should drive — see the
 * note on the output stage below: these frames have already been through
 * brightness, the limiter and gamma, and the device applies its own.
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
 *
 * ### The output stage
 * The composited frame is *not* what the strip shows. `LedMatrix::push` runs
 * master brightness, then the current limiter, then gamma over it on the way
 * out, so this renderer runs the same [LedOutputStage] over every frame before
 * returning it. Without that a 500 mA budget or a brightness of 32 would look
 * like full white on screen and brown out on the tail - the exact "preview
 * quietly lies" failure the port exists to prevent.
 *
 * The config comes from the device's own reported [LedState.output] whenever it
 * publishes one (protocol v5+), and can also be set directly via
 * [setOutputConfig] for a preview that has no live device. Firmware older than
 * v5 reports nothing, and the stage then falls back to a pass-through
 * (brightness 255, no limit, no gamma) rather than guessing.
 *
 * **Gamma is applied when the device says it is enabled.** It is arguably the
 * wrong call for an already-sRGB screen - the panel applies its own transfer
 * curve on top, so a gamma-corrected preview reads darker than the tail does -
 * but the alternative (silently dropping one of the three stages) reintroduces
 * a smaller version of the same lie, and this way the preview tracks the
 * device's gamma toggle visibly. Set [applyGamma] to false to opt out.
 *
 * One caution: a frame that comes out of here has already been through the
 * output stage, so it is a *display* frame, not a frame to stream over FF0A -
 * the device runs `push` over whatever it receives, and sending these pixels
 * would apply brightness, the limiter and gamma twice. Today the only caller
 * is the preview ([LedPreviewClock]); a direct-mode streamer would want the
 * composite from before [outputStage] ran.
 */
class LedStackRenderer(
    private val audio: AudioLevelSource,
    private val motion: MotionStateSource = MotionStateSource(),
    private val imageSupplier: () -> ImageData?,
) {
    private var ledsPerRing: List<Int> = emptyList()
    private var coords: List<LedCoord> = emptyList()
    private var frameBuffer = PixelBuffer(0)

    private var layers: List<LayerCompositor.Layer> = emptyList()
    private var layerEffectIds: List<Byte> = emptyList()

    private val compositor = LayerCompositor()

    /**
     * The output stage applied to every frame. Exposed so a caller can read
     * [LedOutputStage.lastPowerScale] - i.e. tell the user their look is being
     * dimmed to fit the current budget - and so tests can drive it directly.
     */
    val outputStage = LedOutputStage(brightness = 255, gammaEnabled = false, currentLimitMa = 0)

    /** The last gamma flag the device reported, kept so [applyGamma] can re-apply it. */
    private var deviceGammaEnabled = false

    /**
     * Whether the device's gamma flag is honoured in the preview. See the class
     * KDoc: on by default because the preview's job is to match the tail.
     */
    var applyGamma: Boolean = true
        set(value) {
            field = value
            outputStage.gammaEnabled = value && deviceGammaEnabled
        }

    /**
     * Sets the output stage config explicitly, for a preview driven without a
     * connected device. [setState] overwrites this whenever the state it is
     * given carries an [LedState.output] block.
     */
    fun setOutputConfig(brightness: Int, gammaEnabled: Boolean, currentLimitMa: Int) {
        outputStage.brightness = brightness.coerceIn(0, 255)
        deviceGammaEnabled = gammaEnabled
        outputStage.gammaEnabled = gammaEnabled && applyGamma
        outputStage.currentLimitMa = currentLimitMa
    }

    fun setState(state: LedState) {
        val output = state.output
        if (output != null) {
            setOutputConfig(output.brightness, output.gammaEnabled, output.currentLimitMa)
        } else {
            // Pre-v5 firmware publishes no output block. Pass the frame through
            // untouched rather than inventing a brightness or a budget.
            setOutputConfig(brightness = 255, gammaEnabled = false, currentLimitMa = 0)
        }

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
                FirmwareEffectFactory.create(config, audio, motion)
            }

            // An id outside 0x00-0x06 can't come off the wire today, but if one
            // ever does the firmware's `switch` falls through to `default:
            // blended = overlay` - plain overlay, then the opacity mix. NORMAL
            // is exactly that (`rgb_normal` returns the overlay at alpha 255),
            // so it, not OVERWRITE, is the matching fallback: OVERWRITE would
            // additionally treat a black overlay as transparent.
            val blendMode = config.blend ?: BlendMode.NORMAL
            newLayers.add(
                LayerCompositor.Layer(renderer, blendMode, config.enabled, config.opacity)
            )
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
        // `LedMatrix::push` runs over the composite before it reaches the
        // strip; so does the preview, or it shows a frame the device never
        // displays. See the class KDoc.
        outputStage.apply(frameBuffer)
        return frameBuffer
    }
}
