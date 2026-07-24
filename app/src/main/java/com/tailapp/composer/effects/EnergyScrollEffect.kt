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
import com.tailapp.composer.frac
import com.tailapp.composer.triangle
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer

/**
 * A gradient that scrolls faster the louder the music gets.
 *
 * The one effect here whose phase is **integrated** rather than derived from
 * the session clock: because the speed itself varies, position is the integral
 * of speed, and there is no closed form to evaluate at an arbitrary `t`. That
 * makes the phase genuine running state — it accumulates from [ctx.dtSeconds],
 * survives parameter edits along with the instance, and resets only when the
 * session does.
 *
 * The trade-off is deliberate and worth naming: a dropped frame loses that
 * frame's motion instead of skipping ahead, unlike every time-derived effect
 * here. At 30 fps against a dt-scaled speed the error is imperceptible, and the
 * alternative — a speed that jumps discontinuously with the volume — is not.
 */
class EnergyScrollEffect : ReactiveEffect(SPEC) {

    private var phase = 0f

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val level = audioSource(ctx, p.enumIndex("source"))
        val speed = p.float("baseSpeed") + p.float("speedPerLevel") * level

        phase += speed * ctx.dtSeconds
        // Keep it bounded: the pattern repeats every unit, so the integer part
        // is dead weight that would eventually cost Float precision.
        phase = frac(phase)

        val from = p.color("colorA")
        val to = p.color("colorB")
        val axis = p.enumIndex("axis")
        val repeat = p.float("repeat")
        val mirrored = p.bool("mirrored")
        val boost = p.float("levelBoost")
        val brightness = p.float("brightness") * ((1f - boost) + boost * level)

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val position = axisValue(c, axis) * repeat + phase
            val t = if (mirrored) triangle(position) else frac(position)
            out.setPacked(i, EffectColors.scale(EffectColors.lerp(from, to, t), brightness))
        }
    }

    companion object {
        val SPEC = EffectSpec(
            id = "energy_scroll",
            displayName = "Energy Scroll",
            category = EffectCategory.AUDIO,
            schema = listOf(
                EffectParam.Choice("source", "Follows", AUDIO_SOURCE_OPTIONS, 0),
                EffectParam.Color("colorA", "Colour A", 0x00FFA0),
                EffectParam.Color("colorB", "Colour B", 0x8020FF),
                EffectParam.Choice("axis", "Axis", AXIS_OPTIONS, 0),
                EffectParam.Scalar("repeat", "Repeats", 2f, 0.25f, 8f),
                EffectParam.Scalar("baseSpeed", "Base speed", 0.1f, -2f, 2f, unit = "/s"),
                EffectParam.Scalar("speedPerLevel", "Speed from volume", 1.2f, -4f, 4f, unit = "/s"),
                EffectParam.Toggle("mirrored", "Mirrored", true),
                EffectParam.Scalar("levelBoost", "Volume boost", 0.3f, 0f, 1f),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f)
            ),
            factory = { EnergyScrollEffect() }
        )
    }
}
