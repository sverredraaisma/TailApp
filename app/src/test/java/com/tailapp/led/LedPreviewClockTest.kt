package com.tailapp.led

import com.tailapp.model.BlendMode
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedEffect
import com.tailapp.model.LedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [LedPreviewClock] is the timing wrapper the live preview drives every
 * frame: it turns absolute `withFrameNanos` instants into the `dt`
 * [LedStackRenderer] actually needs. These tests pin down that bookkeeping
 * (first frame, elapsed time, clamping, reset) rather than any one effect's
 * math - a concrete effect (Rainbow/Static Color) is just the simplest way to
 * observe `dt` from the outside, the same trick [LedStackRendererTest] uses.
 */
class LedPreviewClockTest {

    private fun layer(effectId: Byte, params: List<Float>) = LayerConfig(
        effectId = effectId,
        blendMode = BlendMode.OVERWRITE.id,
        enabled = true,
        flipX = false, flipY = false, mirrorX = false, mirrorY = false,
        params = params,
    )

    // 2 LEDs at x=0 and x=1, direction=0 (x-axis), speed=90 deg/s, scale=1 -
    // matches LedStackRendererTest's setup, so LED 0's hue is timeOffset*360.
    private fun rainbowState() = LedState(
        numRings = 1,
        ledsPerRing = listOf(2),
        layers = listOf(layer(LedEffect.RAINBOW.id, listOf(0f, 90f, 1f, 0f, 0f, 0f, 0f, 0f))),
    )

    private fun staticColorState(r: Float, g: Float, b: Float) = LedState(
        numRings = 1,
        ledsPerRing = listOf(2),
        layers = listOf(layer(LedEffect.STATIC_COLOR.id, listOf(r, g, b, 0f, 0f, 0f, 0f, 0f))),
    )

    @Test
    fun `the first frame after construction has dt 0 regardless of the timestamp passed in`() {
        val clock = LedPreviewClock()
        clock.setState(rainbowState())

        // dt=0 means no phase accumulation yet, so LED 0 (axis=0) must still
        // read as hue 0 (red) even though nowNanos here is a large, arbitrary
        // instant unrelated to "time zero".
        val frame = clock.frameAt(5_000_000_000L)
        assertEquals(0xFF0000, frame.packed(0))
    }

    @Test
    fun `subsequent frames use the elapsed wall-clock time as dt`() {
        val clock = LedPreviewClock()
        val reference = LedStackRenderer(AudioLevelSource()) { null }
        val state = rainbowState()
        clock.setState(state)
        reference.setState(state)

        clock.frameAt(0L)
        reference.renderFrame(0f)

        // 0.05s later - well under the clamp, so this dt should reach the
        // renderer unmodified.
        val frame = clock.frameAt(50_000_000L)
        val referenceFrame = reference.renderFrame(0.05f)

        assertEquals(referenceFrame.packed(0), frame.packed(0))
    }

    @Test
    fun `a gap longer than the clamp is capped instead of being handed to the renderer whole`() {
        val clock = LedPreviewClock()
        val reference = LedStackRenderer(AudioLevelSource()) { null }
        val state = rainbowState()
        clock.setState(state)
        reference.setState(state)

        clock.frameAt(0L)
        reference.renderFrame(0f)
        clock.frameAt(50_000_000L)
        reference.renderFrame(0.05f)

        // A 10 second gap - e.g. the app was backgrounded - must be clamped,
        // not handed to the renderer as dt=10.
        val frame = clock.frameAt(50_000_000L + 10_000_000_000L)
        val referenceFrame = reference.renderFrame(LedPreviewClock.MAX_DT_SECONDS)

        assertEquals(referenceFrame.packed(0), frame.packed(0))
    }

    @Test
    fun `reset forgets the previous timestamp so the next frame is dt 0 again`() {
        val clock = LedPreviewClock()
        clock.setState(rainbowState())

        clock.frameAt(0L)
        // dt=0.05s, phase advances a bit. Read packed(0) out to a plain Int
        // *now* - like LedStackRenderer/LayerCompositor's scratch buffers, the
        // PixelBuffer a clock returns is reused and mutated in place on the
        // next frameAt() call, so holding onto the PixelBuffer itself across
        // that next call would silently alias to the newer frame instead of
        // the one captured here.
        val beforeReset = clock.frameAt(50_000_000L).packed(0)

        clock.reset()
        // A huge gap in nanos - if the clock weren't reset this would still
        // advance the phase (clamped, but nonzero); reset means dt is exactly
        // 0, so the colour must be unchanged from beforeReset.
        val afterReset = clock.frameAt(999_000_000_000L).packed(0)

        assertEquals(beforeReset, afterReset)
    }

    @Test
    fun `an animated effect's frame changes over time`() {
        val clock = LedPreviewClock()
        clock.setState(rainbowState())

        // See the reset test above for why these are read out immediately
        // rather than compared as PixelBuffer references.
        val first = clock.frameAt(0L).packed(0)
        val second = clock.frameAt(50_000_000L).packed(0)

        assertNotEquals(first, second)
    }

    @Test
    fun `a static effect's frame does not change over time`() {
        val clock = LedPreviewClock()
        clock.setState(staticColorState(10f, 20f, 30f))

        val firstFrame = clock.frameAt(0L)
        val first0 = firstFrame.packed(0)
        val first1 = firstFrame.packed(1)

        val second = clock.frameAt(50_000_000L)

        assertEquals(first0, second.packed(0))
        assertEquals(first1, second.packed(1))
    }

    @Test
    fun `a LedState change is picked up by the next frame`() {
        val clock = LedPreviewClock()
        clock.setState(staticColorState(255f, 0f, 0f))
        val red = clock.frameAt(0L)
        assertEquals(0xFF0000, red.packed(0))

        clock.setState(staticColorState(0f, 0f, 255f))
        val blue = clock.frameAt(50_000_000L)
        assertEquals(0x0000FF, blue.packed(0))
    }
}
