package com.tailapp.led

import com.tailapp.led.effects.BreathingGlowRenderer
import com.tailapp.led.effects.CandleFlickerRenderer
import com.tailapp.led.effects.CometRenderer
import com.tailapp.led.effects.FireRenderer
import com.tailapp.led.effects.GradientScrollRenderer
import com.tailapp.led.effects.GravityLevelRenderer
import com.tailapp.led.effects.MotionGlowRenderer
import com.tailapp.led.effects.PlasmaRenderer
import com.tailapp.led.effects.TapRippleRenderer
import com.tailapp.led.effects.TwinkleRenderer
import com.tailapp.model.BlendMode
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity suite for the LED-3 catalogue, the app-side counterpart of
 * TailFirmware's `test/host/unit/test_led_catalog.cpp`.
 *
 * Every expected pixel below was produced by **linking the firmware's own
 * effect sources** and printing the frame, then transcribed here — not recorded
 * from this port. That is what makes these assertions parity assertions rather
 * than a snapshot of whatever the Kotlin happens to do: if the two arithmetics
 * diverge by a single truncated channel, this suite says so.
 *
 * Several expectations land one below the "obvious" value (a comet head at
 * `159, 254` rather than `160, 255`, a quarter-turn of Gravity Level at `69`
 * rather than `70`). Those are the firmware's float error surviving a
 * `static_cast<uint8_t>`, and they are asserted deliberately: a port that
 * rounded instead of truncating, or that computed in double, would pass a
 * tolerance-based test and still show the wrong pixel.
 */
class LedCatalogParityTest {

    private fun coords(vararg pairs: Pair<Float, Float>): List<LedCoord> =
        pairs.map { LedCoord(it.first, it.second) }

    /** A diagonal spread, matching `make_coords` in the firmware suite. */
    private fun diagonal(count: Int): List<LedCoord> = List(count) { i ->
        val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1).toFloat()
        LedCoord(t, t)
    }

    private fun assertPixels(expected: String, out: PixelBuffer) {
        val actual = (0 until out.ledCount)
            .joinToString(" ") { "${out.red(it)},${out.green(it)},${out.blue(it)}" }
        assertEquals(expected, actual)
    }

    /**
     * Builds a renderer through the factory with **no** parameter overrides, so
     * it keeps the constructor defaults the firmware ships.
     */
    private fun rendererFor(effect: LedEffect): LedEffectRenderer? =
        FirmwareEffectFactory.create(
            LayerConfig(
                effectId = effect.id,
                blendMode = BlendMode.OVERWRITE.id,
                enabled = true,
                flipX = false, flipY = false, mirrorX = false, mirrorY = false,
                params = emptyList()
            ),
            AudioLevelSource(),
            MotionStateSource()
        )

    private val newEffects = listOf(
        LedEffect.FIRE, LedEffect.BREATHING_GLOW, LedEffect.COMET, LedEffect.TWINKLE,
        LedEffect.GRADIENT_SCROLL, LedEffect.PLASMA, LedEffect.CANDLE_FLICKER,
        LedEffect.MOTION_GLOW, LedEffect.TAP_RIPPLE, LedEffect.GRAVITY_LEVEL
    )

    // ── noise.h ──────────────────────────────────────────────────────────

    @Test
    fun `hash reproduces the firmware's unsigned 32-bit mixing`() {
        fun u(a: Int, b: Int) = Noise.hash(a, b).toLong() and 0xFFFFFFFFL
        assertEquals(0L, u(0, 0))
        assertEquals(3679995205L, u(1, 101))
        assertEquals(2257756053L, u(-1, 101))
        assertEquals(344790872L, u(7, 211))
    }

    @Test
    fun `hash01 lands on the firmware's 24-bit fraction`() {
        assertEquals(0.0f, Noise.hash01(0, 0), 0f)
        assertEquals(0.080277860f, Noise.hash01(7, 211), 1e-9f)
    }

    @Test
    fun `value1d matches the firmware including its negative-integer lattice quirk`() {
        assertEquals(0.957757592f, Noise.value1d(0.0f, 307), 1e-7f)
        assertEquals(0.315082490f, Noise.value1d(1.3f, 307), 1e-7f)
        assertEquals(0.584052920f, Noise.value1d(-0.5f, 307), 1e-7f)
        // x = -1.0 truncates to lattice cell -2, not -1: `(int32_t)(x - 1.0f)`
        // is not floor at exact negative integers.
        assertEquals(0.210348189f, Noise.value1d(-1.0f, 307), 1e-7f)
    }

    @Test
    fun `value2d matches the firmware's bilinear field`() {
        assertEquals(0.648431540f, Noise.value2d(0.0f, 0.0f, 101), 1e-7f)
        assertEquals(0.617868185f, Noise.value2d(1.5f, 2.25f, 101), 1e-7f)
        assertEquals(0.287433654f, Noise.value2d(0.9f, -0.6f, 101), 1e-7f)
    }

    // ── FireRenderer ─────────────────────────────────────────────────────

    @Test
    fun `fire renders the firmware's heat field`() {
        val c = coords(0f to 0f, 0.25f to 0.25f, 0.5f to 0.5f, 1f to 1f)
        val out = PixelBuffer(4)
        val fx = FireRenderer()
        fx.render(out, c, dt = 0.5f)
        assertPixels("205,67,0 220,83,0 91,11,0 0,0,0", out)

        // The field scrolls, so a second identical frame is a different one -
        // and proves `time_` carried over rather than resetting.
        fx.render(out, c, dt = 0.5f)
        assertPixels("117,14,0 207,69,0 87,10,0 0,0,0", out)
    }

    @Test
    fun `fire cooling shortens the flame`() {
        val c = coords(0f to 0f, 0.25f to 0.25f, 0.5f to 0.5f, 1f to 1f)
        val out = PixelBuffer(4)
        FireRenderer().apply { setParam(2, 4.0f) }.render(out, c, dt = 0.5f)
        // 1 - y*4 is already <= 0 by y = 0.25, so everything above the base dies.
        assertPixels("205,67,0 0,0,0 0,0,0 0,0,0", out)
    }

    @Test
    fun `fire at zero intensity is black`() {
        val out = PixelBuffer(8)
        FireRenderer().apply { setParam(0, 0f) }.render(out, diagonal(8), dt = 0.5f)
        assertPixels(List(8) { "0,0,0" }.joinToString(" "), out)
    }

    // ── BreathingGlowRenderer ────────────────────────────────────────────

    @Test
    fun `breathing glow follows the raised cosine from trough to peak`() {
        val c = coords(0.1f to 0.2f, 0.9f to 0.8f)
        val out = PixelBuffer(2)
        val fx = BreathingGlowRenderer().apply { setParam(3, 2.0f) }

        // phase 0 is the trough, so the level is min_brightness (0.08).
        fx.render(out, c, dt = 0f)
        assertPixels("20,17,14 20,17,14", out)

        // Quarter period: raised = 0.5, level = 0.08 + 0.92*0.5 = 0.54.
        fx.render(out, c, dt = 0.5f)
        assertPixels("137,118,97 137,118,97", out)

        // Three eighths round: raised = 0.85355, level = 0.86527.
        fx.render(out, c, dt = 0.25f)
        assertPixels("220,190,155 220,190,155", out)
    }

    @Test
    fun `breathing glow with a zero floor is black at the trough`() {
        val out = PixelBuffer(4)
        BreathingGlowRenderer().apply { setParam(4, 0f) }
            .render(out, diagonal(4), dt = 0f)
        assertPixels(List(4) { "0,0,0" }.joinToString(" "), out)
    }

    // ── CometRenderer ────────────────────────────────────────────────────

    @Test
    fun `comet wraps at the ends when bounce is off`() {
        val c = coords(0.5f to 0f, 0.5f to 0.2f, 0.5f to 0.8f)
        val out = PixelBuffer(3)
        CometRenderer().apply { setParam(3, 1.0f); setParam(5, 0f) }
            .render(out, c, dt = 1.2f)
        // 1.2 strip-lengths of travel wraps to a head at y = 0.2.
        assertPixels("0,71,114 0,159,254 0,32,51", out)
    }

    @Test
    fun `comet bounce reflects instead of wrapping`() {
        val c = coords(0.5f to 0f, 0.5f to 0.2f, 0.5f to 0.8f)
        val out = PixelBuffer(3)
        CometRenderer().apply { setParam(3, 1.0f); setParam(5, 1.0f) }
            .render(out, c, dt = 1.2f)
        // The same travel reflected at y = 1 puts the head at y = 0.8, and the
        // tail now trails toward the tip - the opposite pixel is the bright one.
        assertPixels("0,0,0 0,0,0 0,159,254", out)
    }

    @Test
    fun `comet defaults decay exponentially behind the head`() {
        val c = coords(0.5f to 0f, 0.5f to 0.2f, 0.5f to 0.8f)
        val out = PixelBuffer(3)
        CometRenderer().render(out, c, dt = 0.25f)
        // head 0.1, tail 0.25: exp(-0.4), exp(-3.6) (wrapped), exp(-1.2).
        assertPixels("0,107,170 0,4,6 0,48,76", out)
    }

    // ── TwinkleRenderer ──────────────────────────────────────────────────

    @Test
    fun `twinkle picks the firmware's stars and hues`() {
        val c = List(8) { LedCoord(0.5f, 0.5f) }
        val out = PixelBuffer(8)
        // Density 1: every pixel wins its roll, so this pins the whole hash
        // chain (roll, peak, hue) rather than only the pixels that survive.
        TwinkleRenderer().apply { setParam(0, 1.0f) }.render(out, c, dt = 0.4f)
        assertPixels(
            "129,0,0 0,137,44 75,0,250 0,210,63 25,216,0 49,173,0 0,108,4 38,108,0",
            out
        )
    }

    @Test
    fun `twinkle density selects which pixels sparkle at all`() {
        val c = List(8) { LedCoord(0.5f, 0.5f) }
        val out = PixelBuffer(8)
        TwinkleRenderer().render(out, c, dt = 0.4f) // default density 0.12
        assertPixels(
            "0,0,0 0,137,44 0,0,0 0,0,0 25,216,0 0,0,0 0,0,0 0,0,0",
            out
        )
    }

    @Test
    fun `twinkle at zero density never lights anything`() {
        val c = List(64) { LedCoord(0.5f, 0.5f) }
        val out = PixelBuffer(64)
        val fx = TwinkleRenderer().apply { setParam(0, 0f) }
        // Several slot windows, so a pixel that could only go dark at density>0
        // gets its chance to.
        for (dt in listOf(0.3f, 0.4f, 0.5f, 1.2f, 0.7f)) {
            fx.render(out, c, dt)
            assertPixels(List(64) { "0,0,0" }.joinToString(" "), out)
        }
    }

    // ── GradientScrollRenderer ───────────────────────────────────────────

    @Test
    fun `gradient scroll maps the x coordinate onto the palette`() {
        val c = coords(0f to 0.5f, 0.5f to 0.5f, 0.9f to 0.5f)
        val out = PixelBuffer(3)
        GradientScrollRenderer().apply {
            setParam(0, 5.0f) // PALETTE_MONO: a straight grayscale ramp
            setParam(1, 0f)   // no scroll this frame
            setParam(2, 1.0f)
            setParam(3, 0f)   // axis x
        }.render(out, c, dt = 0f)
        assertPixels("0,0,0 127,127,127 229,229,229", out)
    }

    @Test
    fun `gradient scroll axis y ignores x`() {
        val out = PixelBuffer(2)
        GradientScrollRenderer().apply {
            setParam(0, 5.0f); setParam(1, 0f); setParam(2, 1.0f); setParam(3, 1.0f)
        }.render(out, coords(0.9f to 0f, 0.1f to 0.5f), dt = 0f)
        assertPixels("0,0,0 127,127,127", out)
    }

    @Test
    fun `gradient scroll wraps the ramp position rather than clamping it`() {
        val c = coords(0f to 0.5f, 0.5f to 0.5f, 0.9f to 0.5f)
        val out = PixelBuffer(3)
        GradientScrollRenderer().render(out, c, dt = 1.0f)
        // x=0.9 plus a 0.15 scroll is 1.05, which wraps to 0.05 - a clamp would
        // have left it stuck on the palette's last stop instead.
        assertPixels("255,230,0 0,31,255 255,72,0", out)
    }

    // ── PlasmaRenderer ───────────────────────────────────────────────────

    @Test
    fun `plasma sums three sine fields through the palette`() {
        val c = coords(0f to 0f, 0.25f to 0.5f, 1f to 1f)
        val out = PixelBuffer(3)
        val fx = PlasmaRenderer()
        // At t=0 the corners sit at v=0 (palette midpoint) and (0.25,0.5) at
        // v=-1, which is 1/3 of the way up the ramp.
        fx.render(out, c, dt = 0f)
        assertPixels("208,43,84 255,64,56 208,43,84", out)

        fx.render(out, c, dt = 1.0f)
        assertPixels("162,27,108 255,117,17 162,27,108", out)
    }

    // ── CandleFlickerRenderer ────────────────────────────────────────────

    @Test
    fun `candle flicker mixes two noise octaves into a warm level`() {
        val c = coords(0.1f to 0.2f, 0.9f to 0.8f)
        val out = PixelBuffer(2)
        val fx = CandleFlickerRenderer()
        fx.render(out, c, dt = 0f)
        assertPixels("201,114,59 201,114,59", out)
        fx.render(out, c, dt = 1.0f)
        assertPixels("122,69,36 122,69,36", out)
    }

    @Test
    fun `candle flicker colour temperature and wind reach the firmware's value`() {
        val out = PixelBuffer(2)
        CandleFlickerRenderer().apply {
            setParam(0, 1.0f) // pale warm white
            setParam(2, 1.0f) // full wind: the fast octave dominates
        }.render(out, coords(0.1f to 0.2f, 0.9f to 0.8f), dt = 2.0f)
        assertPixels("156,122,79 156,122,79", out)
    }

    @Test
    fun `candle flicker at zero intensity is black`() {
        val out = PixelBuffer(4)
        CandleFlickerRenderer().apply { setParam(1, 0f) }
            .render(out, diagonal(4), dt = 0.5f)
        assertPixels(List(4) { "0,0,0" }.joinToString(" "), out)
    }

    // ── MotionGlowRenderer ───────────────────────────────────────────────

    @Test
    fun `motion glow brightness tracks motion energy and hue tracks deflection`() {
        val motion = MotionStateSource()
        motion.publish(floatArrayOf(30f, 60f, 0f, 0f), 0f, 0f, 0f, motionEnergy = 0.5f)

        val out = PixelBuffer(2)
        MotionGlowRenderer(motion).render(out, coords(0.1f to 0.2f, 0.9f to 0.8f), dt = 0.016f)
        // Axis 0 averages to 45 deg, which is 3/4 across the +-90 window.
        assertPixels("71,0,146 71,0,146", out)
    }

    @Test
    fun `motion glow at rest sits on its floor and brightens with energy`() {
        val motion = MotionStateSource()
        val out = PixelBuffer(2)
        val c = coords(0.1f to 0.2f, 0.9f to 0.8f)

        MotionGlowRenderer(motion).render(out, c, dt = 0.016f)
        assertPixels("0,38,37 0,38,37", out) // floor 0.15 of the centred hue

        motion.publish(FloatArray(4), 0f, 0f, 0f, motionEnergy = 1.0f)
        MotionGlowRenderer(motion).render(out, c, dt = 0.016f)
        assertPixels("0,255,249 0,255,249", out)
    }

    @Test
    fun `motion glow with a zero floor is black at rest`() {
        val out = PixelBuffer(4)
        MotionGlowRenderer(MotionStateSource()).apply { setParam(2, 0f) }
            .render(out, diagonal(4), dt = 0.016f)
        assertPixels(List(4) { "0,0,0" }.joinToString(" "), out)
    }

    // ── TapRippleRenderer ────────────────────────────────────────────────

    @Test
    fun `tap ripple stays black until something taps`() {
        val out = PixelBuffer(8)
        val fx = TapRippleRenderer(MotionStateSource())
        for (dt in listOf(0f, 0.1f, 0.2f)) {
            fx.render(out, diagonal(8), dt)
            assertPixels(List(8) { "0,0,0" }.joinToString(" "), out)
        }
    }

    @Test
    fun `tap ripple travels away from the tapped end`() {
        val motion = MotionStateSource()
        motion.tapBase()

        val c = coords(0.5f to 0f, 0.5f to 0.6f, 0.5f to 1.0f)
        val out = PixelBuffer(3)
        val fx = TapRippleRenderer(motion)

        fx.render(out, c, dt = 0f)
        assertPixels("0,180,255 0,0,0 0,0,0", out)

        fx.render(out, c, dt = 0.5f) // progress 0.6: the edge is now at y=0.6
        assertPixels("0,0,0 0,180,255 0,0,0", out)

        fx.render(out, c, dt = 0.1f) // 0.12 behind the edge, 40% into the ring
        assertPixels("0,0,0 0,108,153 0,0,0", out)
    }

    @Test
    fun `tap ripple from the tip starts at the tip`() {
        val motion = MotionStateSource()
        motion.tapTip()
        val out = PixelBuffer(3)
        TapRippleRenderer(motion).render(out, coords(0.5f to 0f, 0.5f to 0.6f, 0.5f to 1.0f), 0f)
        assertPixels("0,0,0 0,0,0 0,180,255", out)
    }

    @Test
    fun `tap ripple fires once per tap and goes dark once the ring leaves`() {
        val motion = MotionStateSource()
        motion.tapBase()
        val c = coords(0.5f to 0f, 0.5f to 0.5f, 0.5f to 1.0f)
        val out = PixelBuffer(3)
        val fx = TapRippleRenderer(motion)

        fx.render(out, c, dt = 0f)
        // Past 1 + width of travel the ring is gone, and the tap does not
        // retrigger: take_tap_* cleared the flag on the spawning frame.
        fx.render(out, c, dt = 2.0f)
        assertPixels("0,0,0 0,0,0 0,0,0", out)
        fx.render(out, c, dt = 0f)
        assertPixels("0,0,0 0,0,0 0,0,0", out)
    }

    // ── GravityLevelRenderer ─────────────────────────────────────────────

    @Test
    fun `gravity level marks the downhill side of the ring`() {
        val motion = MotionStateSource()
        val c = coords(0f to 0.5f, 0.25f to 0.5f, 0.5f to 0.5f, 0.75f to 0.5f)
        val out = PixelBuffer(4)
        val fx = GravityLevelRenderer(motion)

        motion.publish(FloatArray(4), gravityX = 1.0f, gravityY = 0f, gravityZ = 0f, motionEnergy = 0f)
        fx.render(out, c, dt = 0.016f)
        // Downhill at theta 0: full at x=0, dark half a turn away. The
        // quarter-turns differ by one count because the firmware's float
        // cos(theta) lands either side of zero there.
        assertPixels("0,140,255 0,69,127 0,0,0 0,70,127", out)

        motion.publish(FloatArray(4), gravityX = -1.0f, gravityY = 0f, gravityZ = 0f, motionEnergy = 0f)
        fx.render(out, c, dt = 0.016f)
        assertPixels("0,0,0 0,69,127 0,140,255 0,70,127", out)

        motion.publish(FloatArray(4), gravityX = 0.3f, gravityY = 0.7f, gravityZ = 0.1f, motionEnergy = 0f)
        fx.render(out, c, dt = 0.016f)
        assertPixels("0,103,187 0,140,255 0,36,67 0,0,0", out)
    }

    @Test
    fun `gravity level at zero contrast is flat, and a vertical tail does not go NaN`() {
        val out = PixelBuffer(4)
        // Gravity (0,0,0) - nothing in the ring's plane - relies on
        // atan2(0,0) == 0 rather than producing NaN.
        GravityLevelRenderer(MotionStateSource()).apply { setParam(3, 0f) }
            .render(out, coords(0f to 0.5f, 0.25f to 0.5f, 0.5f to 0.5f, 0.75f to 0.5f), 0.016f)
        assertPixels("0,70,127 0,70,127 0,70,127 0,70,127", out)
    }

    // ── Catalogue-wide ───────────────────────────────────────────────────

    @Test
    fun `every catalogue default is the renderer's constructor default`() {
        for (effect in LedEffect.entries) {
            val renderer = rendererFor(effect)
            assertNotNull("${effect.name} has no renderer", renderer)
            for (p in effect.params) {
                // The user is shown this default before the device reports
                // anything back, so a mismatch is a slider pointing at a value
                // the tail is not actually at.
                assertEquals(
                    "${effect.name} param ${p.id} (${p.name})",
                    p.default, renderer!!.getParam(p.id), 0f
                )
            }
        }
    }

    @Test
    fun `every catalogue default sits inside its declared range`() {
        for (effect in LedEffect.entries) {
            for (p in effect.params) {
                assertTrue(
                    "${effect.name} param ${p.id} (${p.name}) default outside min..max",
                    p.default >= p.min && p.default <= p.max
                )
            }
        }
    }

    @Test
    fun `catalogue ids run unbroken from rainbow to gravity level`() {
        assertEquals(
            (0x00..0x10).map { it.toByte() },
            LedEffect.entries.map { it.id }
        )
    }

    @Test
    fun `every new effect writes every pixel at every strip size`() {
        for (effect in newEffects) {
            for (count in listOf(1, 8, 64)) {
                val renderer = rendererFor(effect)!!
                val out = PixelBuffer(count)
                val c = diagonal(count)
                for (frame in 0 until 5) {
                    // A sentinel no effect can legitimately produce: an
                    // untouched pixel would otherwise look like a deliberate
                    // colour and go unnoticed.
                    out.fill(1, 2, 3)
                    renderer.render(out, c, dt = 0.033f)
                    for (i in 0 until count) {
                        assertNotEquals(
                            "${effect.name} left LED $i unwritten at count=$count",
                            0x010203, out.packed(i)
                        )
                        assertTrue(out.red(i) in 0..255)
                        assertTrue(out.green(i) in 0..255)
                        assertTrue(out.blue(i) in 0..255)
                    }
                }
            }
        }
    }

    @Test
    fun `every new effect is deterministic across an identical frame sequence`() {
        val dts = listOf(0.1f, 0.2f, 0.05f, 0.3f, 0.13f)
        for (effect in newEffects) {
            val motionA = MotionStateSource().also { it.tapBase() }
            val motionB = MotionStateSource().also { it.tapBase() }
            val a = build(effect, motionA)
            val b = build(effect, motionB)

            val outA = PixelBuffer(16)
            val outB = PixelBuffer(16)
            val c = diagonal(16)
            for (dt in dts) a.render(outA, c, dt)
            for (dt in dts) b.render(outB, c, dt)

            for (i in 0 until 16) {
                assertEquals("${effect.name} LED $i", outA.packed(i), outB.packed(i))
            }
        }
    }

    /** Like [rendererFor], but with a caller-supplied motion source. */
    private fun build(effect: LedEffect, motion: MotionStateSource): LedEffectRenderer =
        FirmwareEffectFactory.create(
            LayerConfig(
                effectId = effect.id,
                blendMode = BlendMode.OVERWRITE.id,
                enabled = true,
                flipX = false, flipY = false, mirrorX = false, mirrorY = false,
                params = emptyList()
            ),
            AudioLevelSource(),
            motion
        )!!
}
