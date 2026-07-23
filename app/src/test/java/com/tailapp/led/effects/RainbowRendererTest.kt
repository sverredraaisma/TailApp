package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `main/led/effects/rainbow_effect.cpp`. */
class RainbowRendererTest {

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    @Test
    fun `direction 0 uses the x coordinate as the hue axis`() {
        val r = RainbowRenderer().apply { setParam(0, 0f); setParam(1, 0f); setParam(2, 1f) }
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0.5f, 0.9f)), dt = 0f) // hue = 0.5*360 = 180 -> cyan
        assertEquals(rgb(0, 255, 255), out.packed(0))
    }

    @Test
    fun `direction 1 uses the y coordinate as the hue axis`() {
        val r = RainbowRenderer().apply { setParam(0, 1f); setParam(1, 0f); setParam(2, 1f) }
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0.9f, 0.5f)), dt = 0f)
        assertEquals(rgb(0, 255, 255), out.packed(0))
    }

    @Test
    fun `direction 2 averages x and y for a diagonal hue axis`() {
        val r = RainbowRenderer().apply { setParam(0, 2f); setParam(1, 0f); setParam(2, 1f) }
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(1.0f, 0.0f)), dt = 0f) // (1+0)/2 = 0.5 -> hue 180
        assertEquals(rgb(0, 255, 255), out.packed(0))
    }

    @Test
    fun `scale multiplies how many hue cycles span the coordinate range`() {
        val r = RainbowRenderer().apply { setParam(0, 0f); setParam(1, 0f); setParam(2, 2f) }
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0.25f, 0f)), dt = 0f) // 0.25*2 = 0.5 cycle -> hue 180
        assertEquals(rgb(0, 255, 255), out.packed(0))
    }

    @Test
    fun `time offset accumulates across render calls and shifts hue`() {
        val r = RainbowRenderer().apply { setParam(0, 0f); setParam(1, 180f); setParam(2, 1f) }
        val out = PixelBuffer(1)
        val coords = listOf(LedCoord(0.0f, 0f))

        // time_offset_ += 180*0.5/360 = 0.25 -> hue 90.
        r.render(out, coords, dt = 0.5f)
        // region=1, remainder=(90-60)*255/60=127 -> p=0,q=128,t=127 -> (q,v,p)=(128,255,0)
        assertEquals(rgb(128, 255, 0), out.packed(0))

        // Another +0.25 -> total 0.5 cycle -> hue 180 -> cyan. Confirms the
        // phase carried over from the previous call rather than resetting.
        r.render(out, coords, dt = 0.5f)
        assertEquals(rgb(0, 255, 255), out.packed(0))
    }

    @Test
    fun `direction rounds half-up via truncated static_cast, not round-half-to-even`() {
        val r = RainbowRenderer().apply { setParam(0, 0.5f); setParam(1, 0f); setParam(2, 1f) }
        val out = PixelBuffer(1)
        // 0.5 + 0.5 = 1.0 -> truncates to 1 (vertical). Round-half-to-even
        // would leave direction at 0 (horizontal) instead.
        r.render(out, listOf(LedCoord(0.9f, 0.5f)), dt = 0f)
        assertEquals(rgb(0, 255, 255), out.packed(0)) // hue via y=0.5, not via x=0.9
    }
}
