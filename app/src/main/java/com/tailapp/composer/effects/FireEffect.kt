package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectNoise
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import kotlin.math.pow

/**
 * Flames licking up the tail from the base.
 *
 * Heat is [EffectNoise.valueNoise] scrolling down the tail, multiplied by a
 * falloff so the base stays hot and the tip only flickers. The ramp is
 * black → `colorLow` → `colorHigh`, which keeps the usual red/orange fire but
 * also gives blue or green flame for free.
 *
 * `levelBoost` feeds loudness into the heat, so the fire flares with the music
 * rather than burning at a constant height.
 */
class FireEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val low = p.color("colorLow")
        val high = p.color("colorHigh")
        val scale = p.float("scale")
        val speed = p.float("speed")
        val falloff = p.float("falloff")
        val intensity = p.float("intensity")
        val boost = p.float("levelBoost")

        val scroll = ctx.timeSeconds * speed
        val drive = intensity * ((1f - boost) + boost * ctx.level)

        for (i in coords.indices) {
            val c = transformCoord(coords[i])

            // Two octaves: a slow body plus a faster flicker, and a per-column
            // seed from x so neighbouring ring positions burn independently.
            val column = (c.x * SEED_COLUMNS).toInt()
            val body = EffectNoise.valueNoise(column, c.y * scale - scroll)
            val flicker = EffectNoise.valueNoise(column + 977, c.y * scale * 2.7f - scroll * 1.9f)
            var heat = (body * 0.65f + flicker * 0.35f) * drive

            // Hot at the base, cooling toward the tip.
            heat *= (1f - c.y).coerceIn(0f, 1f).pow(falloff)

            out.setPacked(i, ramp(heat.coerceIn(0f, 1f), low, high))
        }
    }

    /** black → [low] → [high], so a cold LED is off rather than merely dim. */
    private fun ramp(heat: Float, low: Int, high: Int): Int =
        if (heat < 0.5f) EffectColors.lerp(0x000000, low, heat * 2f)
        else EffectColors.lerp(low, high, (heat - 0.5f) * 2f)

    companion object {
        /** Distinct noise columns around a ring; more than any real ring has. */
        private const val SEED_COLUMNS = 64f

        val SPEC = EffectSpec(
            id = "fire",
            displayName = "Fire",
            category = EffectCategory.BASE,
            schema = listOf(
                EffectParam.Color("colorLow", "Ember", 0xFF2000),
                EffectParam.Color("colorHigh", "Flame", 0xFFD040),
                EffectParam.Scalar("scale", "Detail", 3f, 0.5f, 10f),
                EffectParam.Scalar("speed", "Speed", 1.2f, 0f, 5f, unit = "/s"),
                EffectParam.Scalar("falloff", "Falloff", 1.2f, 0.2f, 4f),
                EffectParam.Scalar("intensity", "Intensity", 1.4f, 0f, 3f),
                EffectParam.Scalar("levelBoost", "Volume boost", 0.5f, 0f, 1f)
            ),
            factory = { FireEffect() }
        )
    }
}
