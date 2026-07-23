package com.tailapp.led.effects

import com.tailapp.led.AudioLevelSource
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `main/led/effects/audio_power_effect.cpp`. */
class AudioPowerRendererTest {

    private var now = 0L

    @Test
    fun `brightness jumps instantly on a fresh loud frame`() {
        val audio = AudioLevelSource(clock = { now })
        audio.write(255, byteArrayOf())
        val r = AudioPowerRenderer(audio).apply { setParam(0, 0f); setParam(1, 255f); setParam(2, 0f) }
        val out = PixelBuffer(1)
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0f)
        assertEquals(255, out.green(0))
    }

    @Test
    fun `brightness never snaps down on a quieter fresh frame, only decays`() {
        val audio = AudioLevelSource(clock = { now })
        val r = AudioPowerRenderer(audio).apply { setParam(1, 255f); setParam(3, 3f) }
        val out = PixelBuffer(1)

        audio.write(255, byteArrayOf())
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0f)
        assertEquals(255, out.green(0))

        audio.write(0, byteArrayOf()) // quiet, but still fresh
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0f)
        assertEquals(255, out.green(0)) // held at the peak, not the new quiet value
    }

    @Test
    fun `decays at fade rate per second once the audio source goes stale`() {
        val audio = AudioLevelSource(clock = { now })
        val r = AudioPowerRenderer(audio).apply { setParam(1, 255f); setParam(3, 3f) } // fade 3/sec
        val out = PixelBuffer(1)

        audio.write(255, byteArrayOf())
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0f) // brightness -> 1.0

        now += 300_000_000L // > 200ms staleness window
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0.1f) // brightness -= 3*0.1 = 0.3 -> 0.7
        // 255 * 0.7 = 178.5, truncated to 178.
        assertEquals(178, out.green(0))
    }

    @Test
    fun `decay floors at zero instead of going negative`() {
        val audio = AudioLevelSource(clock = { now })
        val r = AudioPowerRenderer(audio).apply { setParam(1, 255f); setParam(3, 3f) }
        val out = PixelBuffer(1)

        audio.write(255, byteArrayOf())
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 0f)

        now += 300_000_000L
        r.render(out, listOf(LedCoord(0f, 0f)), dt = 10f) // way more than enough to fully decay
        assertEquals(0, out.green(0))
    }
}
