package com.tailapp.led

import com.tailapp.model.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reference values below are derived by hand from `hsv_to_rgb` / the blend
 * helpers in `main/led/color.h`, not read off this class's own output -
 * see the derivation notes on each hue case.
 */
class ColorMathTest {

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    // --- hsvToRgb -----------------------------------------------------

    @Test
    fun `hue 0 is pure red`() {
        // region=0, remainder=0 -> p=0,q=255,t=0 -> {v,t,p} = (255,0,0)
        assertEquals(rgb(255, 0, 0), ColorMath.hsvToRgb(0, 255, 255))
    }

    @Test
    fun `hue 60 is pure yellow`() {
        // region=1, remainder=0 -> p=0,q=255,t=0 -> {q,v,p} = (255,255,0)
        assertEquals(rgb(255, 255, 0), ColorMath.hsvToRgb(60, 255, 255))
    }

    @Test
    fun `hue 120 is pure green`() {
        // region=2, remainder=0 -> p=0,q=255,t=0 -> {p,v,t} = (0,255,0)
        assertEquals(rgb(0, 255, 0), ColorMath.hsvToRgb(120, 255, 255))
    }

    @Test
    fun `hue 180 is pure cyan`() {
        // region=3, remainder=0 -> p=0,q=255,t=0 -> {p,q,v} = (0,255,255)
        assertEquals(rgb(0, 255, 255), ColorMath.hsvToRgb(180, 255, 255))
    }

    @Test
    fun `hue 240 is pure blue`() {
        // region=4, remainder=0 -> p=0,q=255,t=0 -> {t,p,v} = (0,0,255)
        assertEquals(rgb(0, 0, 255), ColorMath.hsvToRgb(240, 255, 255))
    }

    @Test
    fun `hue 300 is pure magenta`() {
        // region=5 (default branch), remainder=0 -> p=0,q=255 -> {v,p,q} = (255,0,255)
        assertEquals(rgb(255, 0, 255), ColorMath.hsvToRgb(300, 255, 255))
    }

    @Test
    fun `hue 359 shows the firmware's integer-truncation artifact`() {
        // region = 359/60 = 5 (default branch).
        // remainder = (359-300)*255/60 = 59*255/60 = 15045/60 = 250 (truncated from 250.75)
        // p = 255*(255-255)/255 = 0
        // q: inner = 255*250/255 = 250 -> 255-250=5 -> q = 255*5/255 = 5
        // t: inner = 255*(255-250)/255 = 255*5/255=5 -> 255-5=250 -> t = 255*250/255 = 250
        // region 5 (default) -> {v, p, q} = (255, 0, 5)
        assertEquals(rgb(255, 0, 5), ColorMath.hsvToRgb(359, 255, 255))
    }

    @Test
    fun `zero saturation is grey at v regardless of hue`() {
        // Early-return branch: s==0 -> {v,v,v}, hue is irrelevant.
        for (h in intArrayOf(0, 60, 120, 180, 240, 300, 359)) {
            assertEquals("hue $h", rgb(128, 128, 128), ColorMath.hsvToRgb(h, 0, 128))
        }
    }

    // --- blend modes ----------------------------------------------------

    @Test
    fun `multiply divides the product by 255 per channel`() {
        // 128*128/255 = 16384/255 = 64 (truncated from 64.25)
        assertEquals(rgb(64, 64, 64), ColorMath.blend(rgb(128, 128, 128), rgb(128, 128, 128), BlendMode.MULTIPLY))
        // Multiplying by white is a no-op; by black zeroes everything.
        assertEquals(rgb(200, 100, 50), ColorMath.blend(rgb(200, 100, 50), rgb(255, 255, 255), BlendMode.MULTIPLY))
        assertEquals(rgb(0, 0, 0), ColorMath.blend(rgb(200, 100, 50), rgb(0, 0, 0), BlendMode.MULTIPLY))
    }

    @Test
    fun `add clamps at 255 per channel`() {
        assertEquals(rgb(255, 150, 30), ColorMath.blend(rgb(200, 100, 10), rgb(100, 50, 20), BlendMode.ADD))
    }

    @Test
    fun `subtract clamps at 0 per channel`() {
        assertEquals(rgb(0, 50, 0), ColorMath.blend(rgb(50, 100, 10), rgb(100, 50, 20), BlendMode.SUBTRACT))
    }

    @Test
    fun `min takes the lower value per channel`() {
        assertEquals(rgb(10, 50, 0), ColorMath.blend(rgb(10, 100, 0), rgb(200, 50, 255), BlendMode.MIN))
    }

    @Test
    fun `max takes the higher value per channel`() {
        assertEquals(rgb(200, 100, 255), ColorMath.blend(rgb(10, 100, 0), rgb(200, 50, 255), BlendMode.MAX))
    }

    @Test
    fun `overwrite passes base through only when overlay is fully black`() {
        assertEquals(rgb(10, 20, 30), ColorMath.blend(rgb(10, 20, 30), rgb(0, 0, 0), BlendMode.OVERWRITE))
    }

    @Test
    fun `overwrite replaces every channel even if only one overlay channel is non-zero`() {
        // This is a whole-pixel comparison against black, not per-channel -
        // overlay (0,0,1) is not black, so it replaces (10,20,30) entirely.
        assertEquals(rgb(0, 0, 1), ColorMath.blend(rgb(10, 20, 30), rgb(0, 0, 1), BlendMode.OVERWRITE))
    }
}
