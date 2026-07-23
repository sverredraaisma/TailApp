package com.tailapp.led

import com.tailapp.model.BlendMode
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedEffect
import com.tailapp.model.LedState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `LedStackRenderer` is the piece the live effect-stack preview drives every
 * frame: builds the layout, keeps one renderer per layer slot, composites.
 */
class LedStackRendererTest {

    private fun layer(
        effectId: Byte,
        params: List<Float> = List(8) { 0f },
        blend: Byte = BlendMode.OVERWRITE.id,
        enabled: Boolean = true,
    ) = LayerConfig(
        effectId = effectId,
        blendMode = blend,
        enabled = enabled,
        flipX = false, flipY = false, mirrorX = false, mirrorY = false,
        params = params,
    )

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    @Test
    fun `renders the expected frame for a single static color layer`() {
        val renderer = LedStackRenderer(AudioLevelSource()) { null }
        val state = LedState(
            numRings = 1,
            ledsPerRing = listOf(4),
            layers = listOf(layer(LedEffect.STATIC_COLOR.id, params = listOf(255f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))),
        )

        renderer.setState(state)
        val frame = renderer.renderFrame(0f)

        assertEquals(4, frame.ledCount)
        for (i in 0 until frame.ledCount) assertEquals(rgb(255, 0, 0), frame.packed(i))
    }

    @Test
    fun `renderFrame before any setState returns an empty buffer instead of crashing`() {
        val renderer = LedStackRenderer(AudioLevelSource()) { null }
        val frame = renderer.renderFrame(0.016f)
        assertEquals(0, frame.ledCount)
    }

    @Test
    fun `an empty layer stack renders black`() {
        val renderer = LedStackRenderer(AudioLevelSource()) { null }
        renderer.setState(LedState(numRings = 1, ledsPerRing = listOf(3), layers = emptyList()))

        val frame = renderer.renderFrame(0f)
        for (i in 0 until frame.ledCount) assertEquals(0x000000, frame.packed(i))
    }

    @Test
    fun `a same-effect update preserves running state, but changing effect id rebuilds it`() {
        val renderer = LedStackRenderer(AudioLevelSource()) { null }
        // 2 LEDs at x=0 and x=1, so index 0 is exactly axis=0 for the rainbow's hue calc.
        val ledsPerRing = listOf(2)
        val rainbowParams = listOf(0f, 90f, 1f, 0f, 0f, 0f, 0f, 0f) // direction=0, speed=90 deg/s, scale=1

        renderer.setState(
            LedState(1, ledsPerRing, listOf(layer(LedEffect.RAINBOW.id, rainbowParams, blend = BlendMode.OVERWRITE.id)))
        )
        // time_offset_ += 90*1/360 = 0.25 -> hue 90 at x=0 -> (128,255,0).
        var frame = renderer.renderFrame(1f)
        assertEquals(rgb(128, 255, 0), frame.packed(0))

        // Same effect id (RAINBOW): blend mode changes, but that's a
        // compositor-level field, not part of the effect object, so the
        // firmware wouldn't reset time_offset_ for it either.
        renderer.setState(
            LedState(1, ledsPerRing, listOf(layer(LedEffect.RAINBOW.id, rainbowParams, blend = BlendMode.ADD.id)))
        )
        frame = renderer.renderFrame(0f) // dt=0 -> no new accumulation
        assertEquals(rgb(128, 255, 0), frame.packed(0)) // phase preserved, not reset to 0

        // Switch away to a different effect id...
        renderer.setState(
            LedState(1, ledsPerRing, listOf(layer(LedEffect.STATIC_COLOR.id, listOf(9f, 9f, 9f, 0f, 0f, 0f, 0f, 0f))))
        )
        renderer.renderFrame(0f)

        // ...then back to Rainbow with identical params. This *does* force a
        // rebuild (a brand new RainbowRenderer, same as the firmware
        // replacing the layer's unique_ptr<LedEffect>), so time_offset_ is
        // back at 0: hue = 0 at x=0 -> red, not the (128,255,0) held above.
        renderer.setState(
            LedState(1, ledsPerRing, listOf(layer(LedEffect.RAINBOW.id, rainbowParams, blend = BlendMode.OVERWRITE.id)))
        )
        frame = renderer.renderFrame(0f)
        assertEquals(rgb(255, 0, 0), frame.packed(0))
    }

    @Test
    fun `feeds the image supplier's pixels into a live Image layer every frame`() {
        val image = ImageData(rgb = byteArrayOf(9, 8, 7), width = 1, height = 1)
        val renderer = LedStackRenderer(AudioLevelSource()) { image }

        renderer.setState(
            LedState(1, listOf(1), listOf(layer(LedEffect.IMAGE.id, params = List(8) { 0f })))
        )
        val frame = renderer.renderFrame(0f)

        assertEquals(9, frame.red(0))
        assertEquals(8, frame.green(0))
        assertEquals(7, frame.blue(0))
    }
}
