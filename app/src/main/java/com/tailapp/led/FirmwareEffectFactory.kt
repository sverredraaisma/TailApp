package com.tailapp.led

import com.tailapp.led.effects.AudioBarRenderer
import com.tailapp.led.effects.AudioFreqBarsRenderer
import com.tailapp.led.effects.AudioPowerRenderer
import com.tailapp.led.effects.BeatPulseRenderer
import com.tailapp.led.effects.BreathingGlowRenderer
import com.tailapp.led.effects.CandleFlickerRenderer
import com.tailapp.led.effects.CometRenderer
import com.tailapp.led.effects.FireRenderer
import com.tailapp.led.effects.GradientScrollRenderer
import com.tailapp.led.effects.GravityLevelRenderer
import com.tailapp.led.effects.ImageRenderer
import com.tailapp.led.effects.MotionGlowRenderer
import com.tailapp.led.effects.PlasmaRenderer
import com.tailapp.led.effects.RainbowRenderer
import com.tailapp.led.effects.StaticColorRenderer
import com.tailapp.led.effects.TapRippleRenderer
import com.tailapp.led.effects.TwinkleRenderer
import com.tailapp.model.LedEffect
import com.tailapp.model.LayerConfig

/**
 * Builds a [LedEffectRenderer] from a wire-format [LayerConfig] - the
 * app-side equivalent of the firmware dispatching `LCMD_SET_LAYER_EFFECT` to
 * a concrete `LedEffect` subclass, then applying `set_param` for each of the
 * 8 parameter slots and the four transform flags.
 */
object FirmwareEffectFactory {

    /**
     * Returns `null` for an empty (`LayerConfig.isEmpty`) or unrecognised layer.
     *
     * [motion] is to the tail-reactive effects what [audio] is to the
     * audio-reactive ones: the firmware reads `MotionBus::instance()` and
     * `FftBuffer::instance()` as singletons, so both are seams here rather than
     * globals, which is what makes an effect assertable without a device.
     */
    fun create(
        config: LayerConfig,
        audio: AudioLevelSource,
        motion: MotionStateSource
    ): LedEffectRenderer? {
        if (config.isEmpty) return null
        val effect = config.effect ?: return null // unknown effect id

        val renderer: LedEffectRenderer = when (effect) {
            LedEffect.RAINBOW -> RainbowRenderer()
            LedEffect.STATIC_COLOR -> StaticColorRenderer()
            LedEffect.IMAGE -> ImageRenderer()
            LedEffect.AUDIO_POWER -> AudioPowerRenderer(audio)
            LedEffect.AUDIO_BAR -> AudioBarRenderer(audio)
            LedEffect.AUDIO_FREQ_BARS -> AudioFreqBarsRenderer(audio)
            LedEffect.BEAT_PULSE -> BeatPulseRenderer(audio)
            LedEffect.FIRE -> FireRenderer()
            LedEffect.BREATHING_GLOW -> BreathingGlowRenderer()
            LedEffect.COMET -> CometRenderer()
            LedEffect.TWINKLE -> TwinkleRenderer()
            LedEffect.GRADIENT_SCROLL -> GradientScrollRenderer()
            LedEffect.PLASMA -> PlasmaRenderer()
            LedEffect.CANDLE_FLICKER -> CandleFlickerRenderer()
            LedEffect.MOTION_GLOW -> MotionGlowRenderer(motion)
            LedEffect.TAP_RIPPLE -> TapRippleRenderer(motion)
            LedEffect.GRAVITY_LEVEL -> GravityLevelRenderer(motion)
        }

        config.params.forEachIndexed { id, value -> renderer.setParam(id, value) }

        renderer.flipX = config.flipX
        renderer.flipY = config.flipY
        renderer.mirrorX = config.mirrorX
        renderer.mirrorY = config.mirrorY

        return renderer
    }
}
