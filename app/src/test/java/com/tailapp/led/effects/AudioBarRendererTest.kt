package com.tailapp.led.effects

import com.tailapp.led.AudioLevelSource
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `main/led/effects/audio_bar_effect.cpp`. */
class AudioBarRendererTest {

    private var now = 0L

    @Test
    fun `direction 0 lights leds whose x is at or below the current level`() {
        val audio = AudioLevelSource(clock = { now })
        audio.write(128, byteArrayOf()) // normalized ~0.502
        val r = AudioBarRenderer(audio).apply {
            setParam(0, 255f); setParam(1, 0f); setParam(2, 0f); setParam(3, 0f)
        }
        val out = PixelBuffer(3)
        val coords = listOf(LedCoord(0.0f, 0.9f), LedCoord(0.5f, 0.9f), LedCoord(0.9f, 0.9f))
        r.render(out, coords, dt = 0f)

        assertEquals(0xFF0000, out.packed(0)) // 0.0 <= level
        assertEquals(0xFF0000, out.packed(1)) // 0.5 <= 0.502
        assertEquals(0x000000, out.packed(2)) // 0.9 > level -> off
    }

    @Test
    fun `direction 1 uses y instead of x`() {
        val audio = AudioLevelSource(clock = { now })
        audio.write(128, byteArrayOf())
        val r = AudioBarRenderer(audio).apply {
            setParam(0, 0f); setParam(1, 255f); setParam(2, 0f); setParam(3, 1f)
        }
        val out = PixelBuffer(2)
        val coords = listOf(LedCoord(0.9f, 0.0f), LedCoord(0.9f, 0.9f))
        r.render(out, coords, dt = 0f)

        assertEquals(0x00FF00, out.packed(0)) // y=0.0 <= level, x is ignored
        assertEquals(0x000000, out.packed(1)) // y=0.9 > level
    }

    @Test
    fun `off leds are black regardless of the configured color`() {
        val audio = AudioLevelSource(clock = { now })
        audio.write(0, byteArrayOf())
        val r = AudioBarRenderer(audio).apply { setParam(2, 255f) } // blue, never sampled while off
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0.5f, 0f)), dt = 0f)
        assertEquals(0x000000, out.packed(0))
    }

    @Test
    fun `level decays like the other audio effects once stale`() {
        val audio = AudioLevelSource(clock = { now })
        val r = AudioBarRenderer(audio).apply {
            setParam(0, 255f); setParam(2, 0f); setParam(4, 2f) // red only, fade 2/sec
        }
        val out = PixelBuffer(1)

        audio.write(255, byteArrayOf())
        r.render(out, listOf(LedCoord(0.9f, 0f)), dt = 0f) // level -> 1.0, 0.9 <= 1.0 -> lit
        assertEquals(0xFF0000, out.packed(0))

        now += 300_000_000L
        r.render(out, listOf(LedCoord(0.9f, 0f)), dt = 0.1f) // level -= 2*0.1 = 0.2 -> 0.8; 0.9 > 0.8 -> off
        assertEquals(0x000000, out.packed(0))
    }
}
