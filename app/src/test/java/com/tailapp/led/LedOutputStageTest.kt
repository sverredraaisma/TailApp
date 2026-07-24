package com.tailapp.led

import com.tailapp.model.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output stage and the new blend arithmetic, asserted against the
 * firmware's integer maths rather than against whatever this code happens to
 * produce.
 *
 * This port exists so the preview shows what the device will show. Every
 * expected value here is worked out from `led_matrix.cpp` / `color.h` by hand;
 * a test that recorded the port's own output could not catch the port drifting.
 */
class LedOutputStageTest {

    private fun buffer(vararg colours: Int): PixelBuffer {
        val b = PixelBuffer(colours.size)
        colours.forEachIndexed { i, c -> b.setPacked(i, c) }
        return b
    }

    // ── Gamma ───────────────────────────────────────────────────────────

    @Test
    fun `the gamma table matches the firmware's generator`() {
        // GAMMA8[i] = round((i / 255)^2.6 * 255) — the same expression the
        // firmware's table was generated from.
        assertEquals(256, ColorMath.GAMMA8.size)
        assertEquals(0, ColorMath.GAMMA8[0])
        assertEquals(255, ColorMath.GAMMA8[255])
        for (i in 0..255) {
            val expected = Math.round(Math.pow(i / 255.0, 2.6) * 255.0).toInt()
            assertEquals("GAMMA8[$i]", expected, ColorMath.GAMMA8[i])
        }
    }

    @Test
    fun `gamma is monotonic and darkens the middle`() {
        assertTrue(ColorMath.GAMMA8[128] < 128)
        for (i in 1..255) assertTrue(ColorMath.GAMMA8[i] >= ColorMath.GAMMA8[i - 1])
    }

    // ── Brightness and ordering ─────────────────────────────────────────

    @Test
    fun `brightness scales each channel with the firmware's truncation`() {
        val stage = LedOutputStage(brightness = 128, gammaEnabled = false)
        val b = buffer(0xC86432) // 200, 100, 50
        stage.apply(b)

        // Integer divide, truncating, exactly as rgb_scale does.
        assertEquals(200 * 128 / 255, b.red(0))
        assertEquals(100 * 128 / 255, b.green(0))
        assertEquals(50 * 128 / 255, b.blue(0))
    }

    @Test
    fun `gamma is applied after brightness, not before`() {
        val stage = LedOutputStage(brightness = 128, gammaEnabled = true)
        val b = buffer(0xFF0000)
        stage.apply(b)

        // Half of full red, then gamma — not gamma of full red, then halved.
        assertEquals(ColorMath.GAMMA8[255 * 128 / 255], b.red(0))
    }

    // ── Power limiter ───────────────────────────────────────────────────

    @Test
    fun `a frame inside the budget is untouched`() {
        val stage = LedOutputStage(gammaEnabled = false, currentLimitMa = 2000)
        val b = PixelBuffer(4)
        for (i in 0 until 4) b.setPacked(i, 0xFFFFFF)
        stage.apply(b)

        assertEquals(255, stage.lastPowerScale)
        assertEquals(255, b.red(0))
    }

    @Test
    fun `a white frame over budget is scaled rather than clipped`() {
        val stage = LedOutputStage(gammaEnabled = false, currentLimitMa = 500)
        val b = PixelBuffer(100)
        for (i in 0 until 100) b.setPacked(i, 0xFFFFFF)
        stage.apply(b)

        assertTrue(stage.lastPowerScale < 255)
        assertTrue(b.red(0) in 1..254) // dimmed, not blacked out
        // Colour balance survives: scaling all three channels equally.
        assertEquals(b.red(0), b.green(0))
        assertEquals(b.green(0), b.blue(0))
    }

    @Test
    fun `limiting preserves relative contrast`() {
        val stage = LedOutputStage(gammaEnabled = false, currentLimitMa = 500)
        val b = PixelBuffer(100)
        for (i in 0 until 50) b.setPacked(i, 0xFFFFFF)
        for (i in 50 until 100) b.setPacked(i, 0x282828)
        stage.apply(b)

        // Clipping the brightest pixels would flatten exactly the contrast the
        // effect was drawing.
        assertTrue(b.red(0) > b.red(50))
        assertTrue(b.red(50) > 0)
    }

    @Test
    fun `a budget below the quiescent draw blacks out instead of underflowing`() {
        val stage = LedOutputStage(gammaEnabled = false, currentLimitMa = 50)
        val b = PixelBuffer(100)
        for (i in 0 until 100) b.setPacked(i, 0xFFFFFF)
        stage.apply(b)

        assertEquals(0, stage.lastPowerScale)
        assertEquals(0, b.red(0))
    }

    @Test
    fun `brightness counts against the budget`() {
        val b = PixelBuffer(100)
        for (i in 0 until 100) b.setPacked(i, 0xFFFFFF)

        val full = LedOutputStage(brightness = 255, gammaEnabled = false, currentLimitMa = 500)
        full.estimateCurrentMa(b)
        val dim = LedOutputStage(brightness = 10, gammaEnabled = false, currentLimitMa = 500)

        // The limiter judges what will be driven, not what was composed.
        assertTrue(dim.estimateCurrentMa(b) < full.estimateCurrentMa(b))
        assertTrue(full.estimateCurrentMa(b) > 500)
        assertTrue(dim.estimateCurrentMa(b) < 500)
    }

    // ── Blend parity ────────────────────────────────────────────────────

    @Test
    fun `normal blend treats black as a colour`() {
        // Overwrite would let the base show through; Normal is what lets a
        // layer darken the stack below it at all.
        assertEquals(0x000000, ColorMath.blend(0xC8C8C8, 0x000000, BlendMode.NORMAL, 255))
        assertEquals(0xC8C8C8, ColorMath.blend(0xC8C8C8, 0x000000, BlendMode.OVERWRITE, 255))
    }

    @Test
    fun `normal blend at half alpha is the firmware's integer midpoint`() {
        // rgb_normal: (overlay * alpha + base * (255 - alpha)) / 255
        val out = ColorMath.blend(0xFFFFFF, 0x000000, BlendMode.NORMAL, 128)
        val expected = (0 * 128 + 255 * 127) / 255
        assertEquals(expected, out shr 16 and 0xFF)
    }

    @Test
    fun `opacity mixes the blended result back toward the base`() {
        // A half-opacity Add is a weaker glow, not a different computation:
        // add(200, 50) = 250, then mixed halfway back to 200.
        val full = ColorMath.blend(0xC80000, 0x320000, BlendMode.ADD, 255)
        assertEquals(250, full shr 16 and 0xFF)

        val half = ColorMath.blend(0xC80000, 0x320000, BlendMode.ADD, 128)
        val expected = (250 * 128 + 200 * 127) / 255
        assertEquals(expected, half shr 16 and 0xFF)
    }

    @Test
    fun `full opacity leaves every existing blend mode untouched`() {
        // The regression that matters most: opacity was added to a compositor
        // whose output every saved composition depends on.
        for (mode in BlendMode.entries) {
            if (mode == BlendMode.NORMAL) continue
            assertEquals(
                "blend($mode) changed at full opacity",
                ColorMath.blend(0x804020, 0x102030, mode),
                ColorMath.blend(0x804020, 0x102030, mode, 255)
            )
        }
    }
}
