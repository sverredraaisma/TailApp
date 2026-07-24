package com.tailapp.composer.effects

import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectColors
import com.tailapp.composer.EffectParam
import com.tailapp.composer.EffectSpec
import com.tailapp.composer.ReactiveContext
import com.tailapp.composer.ReactiveEffect
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * A slow, organic two-colour field: three sine waves at different rates and
 * angles summed per LED, the classic plasma.
 *
 * A good base under sharp beat layers precisely because it never resolves into a
 * repeating pattern — the three periods are mutually irrational enough that the
 * field keeps drifting. `levelBoost` ties its brightness to loudness so it
 * recedes in quiet passages instead of competing with them.
 */
class PlasmaEffect : ReactiveEffect(SPEC) {

    override fun render(out: PixelBuffer, coords: List<LedCoord>, ctx: ReactiveContext) {
        val from = p.color("colorA")
        val to = p.color("colorB")
        val scale = p.float("scale")
        val speed = p.float("speed")
        val brightness = p.float("brightness")
        val boost = p.float("levelBoost")

        val t = ctx.timeSeconds * speed * TWO_PI
        val gain = brightness * ((1f - boost) + boost * ctx.level)

        for (i in coords.indices) {
            val c = transformCoord(coords[i])
            val x = c.x * scale * TWO_PI
            val y = c.y * scale * TWO_PI

            val v = sin(x + t) +
                sin(y * 1.3f + t * 0.8f) +
                sin((x + y) * 0.7f + t * 1.4f)

            // v spans -3..3; fold to 0..1.
            val mix = (v / 3f + 1f) * 0.5f
            out.setPacked(i, EffectColors.scale(EffectColors.lerp(from, to, mix), gain))
        }
    }

    companion object {
        private const val TWO_PI = (2 * PI).toFloat()

        val SPEC = EffectSpec(
            id = "plasma",
            displayName = "Plasma",
            category = EffectCategory.BASE,
            schema = listOf(
                EffectParam.Color("colorA", "Colour A", 0x2000FF),
                EffectParam.Color("colorB", "Colour B", 0x00FFC0),
                EffectParam.Scalar("scale", "Scale", 1.5f, 0.25f, 6f),
                EffectParam.Scalar("speed", "Speed", 0.2f, 0f, 2f, unit = "/s"),
                EffectParam.Scalar("brightness", "Brightness", 1f, 0f, 1f),
                EffectParam.Scalar("levelBoost", "Volume boost", 0f, 0f, 1f)
            ),
            factory = { PlasmaEffect() }
        )
    }
}
