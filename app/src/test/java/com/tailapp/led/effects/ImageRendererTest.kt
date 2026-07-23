package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `main/led/effects/image_effect.cpp`. */
class ImageRendererTest {

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    // A 2x2 image, one distinct colour per corner.
    private val image = byteArrayOf(
        10, 20, 30, // (px=0,py=0)
        40, 50, 60, // (px=1,py=0)
        70, 80, 90, // (px=0,py=1)
        100, 110, 120, // (px=1,py=1)
    )

    private fun renderAt(orientation: Float, coord: LedCoord): Int {
        val r = ImageRenderer().apply {
            setImage(image, width = 2, height = 2)
            setParam(0, orientation)
        }
        val out = PixelBuffer(1)
        r.render(out, listOf(coord), dt = 0f)
        return out.packed(0)
    }

    @Test
    fun `nearest-neighbor samples the four corners exactly`() {
        assertEquals(rgb(10, 20, 30), renderAt(0f, LedCoord(0f, 0f)))
        assertEquals(rgb(40, 50, 60), renderAt(0f, LedCoord(1f, 0f)))
        assertEquals(rgb(70, 80, 90), renderAt(0f, LedCoord(0f, 1f)))
        assertEquals(rgb(100, 110, 120), renderAt(0f, LedCoord(1f, 1f)))
    }

    @Test
    fun `orientation 1 rotates 90 degrees clockwise around the center`() {
        // x' = 1-y, y' = x -> sampling (0,0) reads what was at (1,0).
        assertEquals(rgb(40, 50, 60), renderAt(1f, LedCoord(0f, 0f)))
    }

    @Test
    fun `orientation 2 rotates 180 degrees`() {
        assertEquals(rgb(100, 110, 120), renderAt(2f, LedCoord(0f, 0f)))
    }

    @Test
    fun `orientation 3 rotates 270 degrees clockwise`() {
        // x' = y, y' = 1-x -> sampling (0,0) reads what was at (0,1).
        assertEquals(rgb(70, 80, 90), renderAt(3f, LedCoord(0f, 0f)))
    }

    @Test
    fun `no image data samples black instead of crashing`() {
        val r = ImageRenderer()
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0.5f, 0.5f)), dt = 0f)
        assertEquals(0x000000, out.packed(0))
    }
}
