package com.tailapp.composer.effects

import com.tailapp.composer.AUDIO_SOURCE_OPTIONS
import com.tailapp.composer.AXIS_OPTIONS
import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.composer.audioSource
import com.tailapp.composer.axisValue
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import kotlin.math.abs

/**
 * A level meter filling along the tail, with the classic falling peak dot.
 *
 * The fill is instantaneous — loudness is already smoothed upstream — while the
 * peak marker falls at a fixed rate, which is what makes transients legible: the
 * fill collapses after a hit but the dot hangs where the hit reached.
 *
 * The peak is per-instance running state, preserved across parameter edits
 * because [com.tailapp.composer.CompositionRenderer] reuses the instance.
 */
class VuMeterEffect : ReactiveEffect(SPEC) {

    private var peak = 0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val value = (audioSource(ctx, p.enumIndex("source")) * p.float("gain")).coerceIn(0f, 1f)

        peak = if (value >= peak) value
        else (peak - p.float("peakFall") * ctx.dtSeconds).coerceAtLeast(value)

        val axis = p.enumIndex("axis")
        val inverted = p.bool("inverted")
        val low = p.color("colorLow")
        val high = p.color("colorHigh")
        val brightness = p.float("brightness")
        val showPeak = p.bool("peakDot")
        val peakColour = p.color("peakColor")
        val dotWidth = p.float("dotWidth")

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val raw = axisValue(c, axis)
            val position = if (inverted) 1f - raw else raw

            if (showPeak && peak > 0f && abs(position - peak) <= dotWidth) {
                out.setPacked(i, EffectColors.scale(peakColour, brightness))
                continue
            }

            if (position <= value) {
                // Gradient by position, not by level: the top of the meter is
                // always the "hot" colour, so the scale reads consistently.
                out.setPacked(i, EffectColors.scale(EffectColors.lerp(low, high, position), brightness))
            }
        }
    }

    companion object {
        val SPEC = EffectSpec(
            id = "vu_meter",
            displayName = "VU Meter",
            category = EffectCategory.AUDIO,
            schema = listOf(
                EffectParam.Choice("source", "Follows", AUDIO_SOURCE_OPTIONS, 0),
                EffectParam.Color("colorLow", "Low colour", 0x00FF40),
                EffectParam.Color("colorHigh", "High colour", 0xFF2000),
                EffectParam.Choice("axis", "Axis", AXIS_OPTIONS, 0),
                EffectParam.Toggle("inverted", "From the tip", false),
                EffectParam.Scalar("gain", "Gain", 1f, 0.1f, 4f),
                EffectParam.Toggle("peakDot", "Peak dot", true),
                EffectParam.Color("peakColor", "Peak colour", 0xFFFFFF),
                EffectParam.Scalar("peakFall", "Peak fall", 0.5f, 0.05f, 4f, unit = "/s"),
                EffectParam.Scalar("dotWidth", "Peak width", 0.04f, 0.01f, 0.2f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { VuMeterEffect() }
        )
    }
}
