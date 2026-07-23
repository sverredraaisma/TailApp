package com.tailapp.led.effects

import com.tailapp.led.AudioLevelSource
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `main/led/effects/audio_freq_bars_effect.cpp`. */
class AudioFreqBarsRendererTest {

    private var now = 0L

    @Test
    fun `each bar tracks the max bin value across its mapped bin range`() {
        val audio = AudioLevelSource(clock = { now })
        audio.write(0, byteArrayOf(50, 200.toByte(), 10, 30)) // bar0 <- bins[0,2), bar1 <- bins[2,4)
        val r = AudioFreqBarsRenderer(audio).apply {
            setParam(0, 2f); setParam(1, 255f); setParam(2, 0f); setParam(3, 0f); setParam(5, 0f) // horizontal
        }
        val out = PixelBuffer(4)
        val coords = listOf(
            LedCoord(0.25f, 0.5f), // bar 0, mid height: level 200/255=0.784 -> lit
            LedCoord(0.25f, 0.9f), // bar 0, near top: above level -> off
            LedCoord(0.75f, 0.05f), // bar 1, low height: level 30/255=0.118 -> lit
            LedCoord(0.75f, 0.5f), // bar 1, mid height: above level -> off
        )
        r.render(out, coords, dt = 0f)

        assertEquals(0xFF0000, out.packed(0))
        assertEquals(0x000000, out.packed(1))
        assertEquals(0xFF0000, out.packed(2))
        assertEquals(0x000000, out.packed(3))
    }

    @Test
    fun `orientation 1 swaps which axis selects the bar`() {
        val audio = AudioLevelSource(clock = { now })
        audio.write(0, byteArrayOf(200.toByte(), 10)) // 2 bars, 1 bin each
        val r = AudioFreqBarsRenderer(audio).apply {
            setParam(0, 2f); setParam(1, 0f); setParam(2, 255f); setParam(3, 0f); setParam(5, 1f) // vertical bars
        }
        val out = PixelBuffer(1)
        // bar axis is now y; y=0.25 -> bar 0 (level 200/255=0.784); height axis is x=0.5 <= 0.784 -> lit
        r.render(out, listOf(LedCoord(0.5f, 0.25f)), dt = 0f)
        assertEquals(0x00FF00, out.packed(0))
    }

    @Test
    fun `each bar decays independently`() {
        val audio = AudioLevelSource(clock = { now })
        val r = AudioFreqBarsRenderer(audio).apply {
            setParam(0, 2f); setParam(1, 255f); setParam(2, 0f); setParam(4, 1f) // red only, fade 1/sec
        }
        val out = PixelBuffer(2)
        val coords = listOf(LedCoord(0.25f, 0.5f), LedCoord(0.75f, 0.05f))

        audio.write(0, byteArrayOf(255.toByte(), 0)) // bar0 loud, bar1 silent
        r.render(out, coords, dt = 0f) // bar0 level -> 1.0, bar1 -> 0.0
        assertEquals(0xFF0000, out.packed(0)) // bar0 lit
        assertEquals(0x000000, out.packed(1)) // bar1 off (0.05 > 0.0)

        now += 300_000_000L // audio goes stale, no new peaks
        r.render(out, coords, dt = 0.3f) // bar0 decays: 1.0 - 1*0.3 = 0.7, still >= 0.5 -> lit
        assertEquals(0xFF0000, out.packed(0))

        r.render(out, coords, dt = 0.3f) // bar0: 0.7 - 0.3 = 0.4, now < 0.5 -> off
        assertEquals(0x000000, out.packed(0))
    }
}
