package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `main/led/effects/static_color_effect.cpp`. */
class StaticColorRendererTest {

    @Test
    fun `defaults render solid white`() {
        val r = StaticColorRenderer()
        val out = PixelBuffer(3)
        r.render(out, List(3) { LedCoord(0f, 0f) }, dt = 0f)
        for (i in 0 until 3) assertEquals(0xFFFFFF, out.packed(i))
    }

    @Test
    fun `sets each channel independently`() {
        val r = StaticColorRenderer().apply { setParam(0, 10f); setParam(1, 20f); setParam(2, 30f) }
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0f)
        assertEquals(10, out.red(0))
        assertEquals(20, out.green(0))
        assertEquals(30, out.blue(0))
    }

    @Test
    fun `clamps above 255 down to 255`() {
        val r = StaticColorRenderer().apply { setParam(0, 999f) }
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0f)
        assertEquals(255, out.red(0))
    }

    @Test
    fun `clamps below 0 up to 0`() {
        val r = StaticColorRenderer().apply { setParam(1, -50f) }
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0f)
        assertEquals(0, out.green(0))
    }

    @Test
    fun `getParam returns the raw stored value, not the clamped render value`() {
        val r = StaticColorRenderer().apply { setParam(0, 999f) }
        assertEquals(999f, r.getParam(0), 0f)
    }
}
