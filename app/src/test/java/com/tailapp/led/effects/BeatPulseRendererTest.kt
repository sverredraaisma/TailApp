package com.tailapp.led.effects

import com.tailapp.led.AudioLevelSource
import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors `main/led/effects/beat_pulse_effect.cpp`.
 *
 * The interesting case is the downbeat boost: it is unclamped in `set_param`
 * on both sides, so a boost above 1 asks for a pixel brighter than full white.
 * The clamp test below pins down why this port clamps there rather than
 * reproducing the firmware's wrapping `static_cast<uint8_t>`.
 */
class BeatPulseRendererTest {

    private val coords = listOf(LedCoord(0f, 0f), LedCoord(0f, 1f))

    private fun audioWithBeat(onBeat: Boolean, onDownbeat: Boolean): AudioLevelSource =
        AudioLevelSource().apply {
            write(
                loudness = 200,
                bins = ByteArray(8),
                beat = AudioLevelSource.BeatInfo(
                    phase = 0f, bpm = 120f, onBeat = onBeat, onDownbeat = onDownbeat, onDrop = false
                )
            )
        }

    private fun render(r: BeatPulseRenderer, dt: Float): PixelBuffer =
        PixelBuffer(coords.size).also { r.render(it, coords, dt) }

    @Test
    fun `with no beat trailer at all the effect stays dark`() {
        val r = BeatPulseRenderer(AudioLevelSource())
        val out = render(r, 0.1f)
        for (i in 0 until out.ledCount) assertEquals(0x000000, out.packed(i))
    }

    @Test
    fun `a beat lights the strip at the configured colour`() {
        val r = BeatPulseRenderer(audioWithBeat(onBeat = true, onDownbeat = false))
        r.setParam(0, 200f); r.setParam(1, 100f); r.setParam(2, 50f)

        // dt=0: brightness latches to 1.0 and nothing has decayed yet.
        val out = render(r, 0f)
        for (i in 0 until out.ledCount) assertEquals((200 shl 16) or (100 shl 8) or 50, out.packed(i))
    }

    @Test
    fun `brightness above full white is clamped rather than wrapped`() {
        // Default colour is white. downbeat_boost = 1.5 -> 255 * 1.5 = 382.5.
        // The firmware's out-of-range float-to-uint8_t conversion yields 126
        // (a *dark* downbeat), but that is C++ UB, not a designed look: the
        // downbeat frame must be at least as bright as a plain beat.
        val r = BeatPulseRenderer(audioWithBeat(onBeat = true, onDownbeat = true))
        r.setParam(4, 1.5f)

        val out = render(r, 0f)
        for (i in 0 until out.ledCount) assertEquals(0xFFFFFF, out.packed(i))
    }

    @Test
    fun `a downbeat boost below 1 still dims the downbeat`() {
        val r = BeatPulseRenderer(audioWithBeat(onBeat = true, onDownbeat = true))
        r.setParam(4, 0.5f)

        // 255 * 0.5 = 127.5, truncated to 127 like the firmware's cast.
        val out = render(r, 0f)
        for (i in 0 until out.ledCount) assertEquals((127 shl 16) or (127 shl 8) or 127, out.packed(i))
    }

    @Test
    fun `brightness decays at the configured rate and bottoms out at black`() {
        val r = BeatPulseRenderer(audioWithBeat(onBeat = true, onDownbeat = false))
        r.setParam(3, 4f) // 4 units of brightness per second

        // The beat latches brightness to 1.0, then 0.125s at rate 4 takes 0.5
        // straight back off it: 255 * 0.5 = 127.5, truncated to 127.
        render(r, 0.125f).let {
            assertEquals((127 shl 16) or (127 shl 8) or 127, it.packed(0))
        }
        val out = render(r, 1f)
        for (i in 0 until out.ledCount) assertEquals(0x000000, out.packed(i))
    }

    @Test
    fun `params round-trip through setParam and getParam`() {
        val r = BeatPulseRenderer(AudioLevelSource())
        val values = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        values.forEachIndexed { id, v -> r.setParam(id, v) }
        values.forEachIndexed { id, v -> assertEquals(v, r.getParam(id), 0f) }
        assertEquals(0f, r.getParam(6), 0f)
    }
}
