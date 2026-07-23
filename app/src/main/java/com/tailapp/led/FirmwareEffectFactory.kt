package com.tailapp.led

import com.tailapp.led.effects.AudioBarRenderer
import com.tailapp.led.effects.AudioFreqBarsRenderer
import com.tailapp.led.effects.AudioPowerRenderer
import com.tailapp.led.effects.ImageRenderer
import com.tailapp.led.effects.RainbowRenderer
import com.tailapp.led.effects.StaticColorRenderer
import com.tailapp.model.LedEffect
import com.tailapp.model.LayerConfig

/**
 * Builds a [LedEffectRenderer] from a wire-format [LayerConfig] - the
 * app-side equivalent of the firmware dispatching `LCMD_SET_LAYER_EFFECT` to
 * a concrete `LedEffect` subclass, then applying `set_param` for each of the
 * 8 parameter slots and the four transform flags.
 */
object FirmwareEffectFactory {

    /** Returns `null` for an empty (`LayerConfig.isEmpty`) or unrecognised layer. */
    fun create(config: LayerConfig, audio: AudioLevelSource): LedEffectRenderer? {
        if (config.isEmpty) return null
        val effect = config.effect ?: return null // unknown effect id

        val renderer: LedEffectRenderer = when (effect) {
            LedEffect.RAINBOW -> RainbowRenderer()
            LedEffect.STATIC_COLOR -> StaticColorRenderer()
            LedEffect.IMAGE -> ImageRenderer()
            LedEffect.AUDIO_POWER -> AudioPowerRenderer(audio)
            LedEffect.AUDIO_BAR -> AudioBarRenderer(audio)
            LedEffect.AUDIO_FREQ_BARS -> AudioFreqBarsRenderer(audio)
        }

        config.params.forEachIndexed { id, value -> renderer.setParam(id, value) }

        renderer.flipX = config.flipX
        renderer.flipY = config.flipY
        renderer.mirrorX = config.mirrorX
        renderer.mirrorY = config.mirrorY

        return renderer
    }
}
