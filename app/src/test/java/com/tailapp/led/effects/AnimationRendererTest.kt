package com.tailapp.led.effects

import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `AnimationRenderer` is the one effect with nothing to preview: its frames
 * live in a device flash slot and never reach the phone. These tests pin the
 * documented contract - render black, but keep the parameters - so a future
 * change that starts inventing a placeholder animation fails loudly instead of
 * quietly showing the user something the tail is not doing.
 */
class AnimationRendererTest {

    private val coords = listOf(LedCoord(0f, 0f), LedCoord(0.5f, 0.5f), LedCoord(1f, 1f))

    @Test
    fun `renders black regardless of params, time or transform`() {
        val r = AnimationRenderer()
        r.setParam(0, 3f) // slot
        r.setParam(1, 4f) // speed
        r.flipX = true
        r.mirrorY = true

        val out = PixelBuffer(coords.size)
        out.fill(255, 255, 255) // start non-black so a no-op render would show
        r.render(out, coords, dt = 0.5f)
        for (i in 0 until out.ledCount) assertEquals(0x000000, out.packed(i))

        // Still black on a later frame - nothing accumulates into a picture.
        r.render(out, coords, dt = 5f)
        for (i in 0 until out.ledCount) assertEquals(0x000000, out.packed(i))
    }

    @Test
    fun `parameters are stored and reported so the effect stays configurable`() {
        val r = AnimationRenderer()
        // Defaults mirror AnimationEffect::describe_params().
        assertEquals(0f, r.getParam(0), 0f)
        assertEquals(1f, r.getParam(1), 0f)

        r.setParam(2, 0f)
        assertEquals(0f, r.getParam(2), 0f)

        // Out-of-range ids are ignored on write and read back as 0.
        r.setParam(99, 7f)
        assertEquals(0f, r.getParam(99), 0f)
    }
}
