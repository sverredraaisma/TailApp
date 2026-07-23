package com.tailapp.led

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors `LedEffect::transform_coord` (`main/led/led_effect.h`): mirror
 * folds a coordinate around its axis midpoint, and mirror is applied
 * *before* flip.
 */
class LedEffectRendererTest {

    /** Minimal concrete renderer that exposes the protected transform for testing. */
    private class ProbeRenderer : LedEffectRenderer() {
        fun transform(c: LedCoord): LedCoord = transformCoord(c)
        override fun render(out: PixelBuffer, coords: List<LedCoord>, dt: Float) {}
        override fun setParam(id: Int, value: Float) {}
        override fun getParam(id: Int): Float = 0f
    }

    @Test
    fun `no transforms is the identity`() {
        val r = ProbeRenderer()
        assertEquals(LedCoord(0.3f, 0.7f), r.transform(LedCoord(0.3f, 0.7f)))
    }

    @Test
    fun `flipX mirrors x around the strip center`() {
        val r = ProbeRenderer().apply { flipX = true }
        assertEquals(LedCoord(0.8f, 0.5f), r.transform(LedCoord(0.2f, 0.5f)))
    }

    @Test
    fun `flipY mirrors y around the strip center`() {
        val r = ProbeRenderer().apply { flipY = true }
        assertEquals(LedCoord(0.5f, 0.8f), r.transform(LedCoord(0.5f, 0.2f)))
    }

    @Test
    fun `mirrorX folds x around 0-5 instead of reflecting end-to-end`() {
        val r = ProbeRenderer().apply { mirrorX = true }
        assertEquals(0.0f, r.transform(LedCoord(0.0f, 0f)).x, 1e-6f)
        assertEquals(0.5f, r.transform(LedCoord(0.25f, 0f)).x, 1e-6f)
        assertEquals(1.0f, r.transform(LedCoord(0.5f, 0f)).x, 1e-6f)
        assertEquals(0.5f, r.transform(LedCoord(0.75f, 0f)).x, 1e-6f)
        assertEquals(0.0f, r.transform(LedCoord(1.0f, 0f)).x, 1e-6f)
    }

    @Test
    fun `mirrorY folds y around 0-5`() {
        val r = ProbeRenderer().apply { mirrorY = true }
        assertEquals(0.5f, r.transform(LedCoord(0f, 0.75f)).y, 1e-6f)
    }

    @Test
    fun `mirror is applied before flip, changing the result whenever both are set`() {
        val r = ProbeRenderer().apply { mirrorX = true; flipX = true }
        // mirror(0.2) = 0.4, then flip: 1-0.4 = 0.6
        assertEquals(0.6f, r.transform(LedCoord(0.2f, 0f)).x, 1e-6f)

        // Sanity check the wrong order would have given a different answer:
        // flip(0.2) = 0.8, then mirror: 0.8 > 0.5 -> (1-0.8)*2 = 0.4 != 0.6
    }

    @Test
    fun `mirror and flip combine independently on x and y`() {
        val r = ProbeRenderer().apply { mirrorX = true; flipY = true }
        val result = r.transform(LedCoord(0.75f, 0.3f))
        assertEquals(0.5f, result.x, 1e-6f) // mirrored only
        assertEquals(0.7f, result.y, 1e-6f) // flipped only
    }
}
