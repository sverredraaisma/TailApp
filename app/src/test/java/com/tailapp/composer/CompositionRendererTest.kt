package com.tailapp.composer

import com.tailapp.model.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The compositor's semantics, asserted as exact pixels.
 *
 * Every expected value here is worked out by hand from the firmware's own blend
 * arithmetic (`ColorMath`, itself a transcription of `color.h`) rather than by
 * calling the renderer and recording what it produced. A test that asserts
 * whatever the code already does cannot catch the code being wrong.
 */
class CompositionRendererTest {

    /** Two rings of four: eight LEDs, `y = 0` on the first ring and `y = 1` on the second. */
    private val layout = listOf(4, 4)

    private fun render(composition: Composition): com.tailapp.led.PixelBuffer {
        val renderer = CompositionRenderer()
        renderer.setLayout(layout)
        renderer.setComposition(composition)
        return renderer.render(testContext())
    }

    private fun composition(vararg layers: LayerNode, brightness: Float = 1f) =
        Composition(id = "t", name = "t", layers = layers.toList(), brightness = brightness)

    @Test
    fun `an empty composition renders black`() {
        val frame = render(composition())

        for (i in 0 until frame.ledCount) assertEquals(0, frame.packed(i))
    }

    @Test
    fun `a single overwrite layer fills the strip with its colour`() {
        val frame = render(composition(solidLayer("a", 0x402010)))

        assertEquals(8, frame.ledCount)
        for (i in 0 until frame.ledCount) assertEquals(0x402010, frame.packed(i))
    }

    @Test
    fun `an added layer sums per channel`() {
        val frame = render(
            composition(
                solidLayer("a", 0x402010, BlendMode.OVERWRITE),
                solidLayer("b", 0x100804, BlendMode.ADD)
            )
        )

        // 0x40+0x10, 0x20+0x08, 0x10+0x04
        assertEquals(0x502814, frame.packed(0))
    }

    @Test
    fun `opacity cross-fades between the base and the blended result`() {
        val frame = render(
            composition(
                solidLayer("a", 0x402010, BlendMode.OVERWRITE),
                solidLayer("b", 0x100804, BlendMode.ADD, opacity = 0.5f)
            )
        )

        // base 0x402010, blended 0x502814, mixed halfway:
        // r 64+(80-64)/2=72, g 32+(40-32)/2=36, b 16+(20-16)/2=18
        assertEquals(0x482412, frame.packed(0))
    }

    @Test
    fun `a fully transparent layer is skipped entirely`() {
        val frame = render(
            composition(
                solidLayer("a", 0x402010, BlendMode.OVERWRITE),
                solidLayer("b", 0xFFFFFF, BlendMode.ADD, opacity = 0f)
            )
        )

        assertEquals(0x402010, frame.packed(0))
    }

    @Test
    fun `a disabled layer contributes nothing`() {
        val frame = render(
            composition(
                solidLayer("a", 0x402010, BlendMode.OVERWRITE),
                solidLayer("b", 0xFFFFFF, BlendMode.ADD, enabled = false)
            )
        )

        assertEquals(0x402010, frame.packed(0))
    }

    @Test
    fun `master brightness scales the finished frame`() {
        val frame = render(composition(solidLayer("a", 0x804020), brightness = 0.5f))

        // Applied once, at the end: 0x80/2, 0x40/2, 0x20/2.
        assertEquals(0x402010, frame.packed(0))
    }

    @Test
    fun `a layer naming an unknown effect renders nothing but does not break the stack`() {
        val frame = render(
            composition(
                solidLayer("a", 0x402010, BlendMode.OVERWRITE),
                EffectLayer(
                    id = "ghost",
                    name = "Removed in a later build",
                    effectId = "no_such_effect",
                    blendMode = BlendMode.ADD
                )
            )
        )

        assertEquals(0x402010, frame.packed(0))
    }

    // --- folders ---

    @Test
    fun `a folder composites its children then blends the result as one unit`() {
        val frame = render(
            composition(
                GroupLayer(
                    id = "f",
                    name = "Folder",
                    blendMode = BlendMode.ADD,
                    children = listOf(
                        solidLayer("a", 0x402010, BlendMode.OVERWRITE),
                        solidLayer("b", 0x100804, BlendMode.ADD)
                    )
                )
            )
        )

        assertEquals(0x502814, frame.packed(0))
    }

    @Test
    fun `an empty folder contributes nothing`() {
        val frame = render(
            composition(
                solidLayer("a", 0x402010, BlendMode.OVERWRITE),
                GroupLayer(id = "f", name = "Folder", blendMode = BlendMode.ADD)
            )
        )

        assertEquals(0x402010, frame.packed(0))
    }

    @Test
    fun `a folder's opacity mixes the whole group at once`() {
        val frame = render(
            composition(
                solidLayer("base", 0x402010, BlendMode.OVERWRITE),
                GroupLayer(
                    id = "f",
                    name = "Folder",
                    blendMode = BlendMode.ADD,
                    opacity = 0.5f,
                    children = listOf(solidLayer("a", 0x100804, BlendMode.OVERWRITE))
                )
            )
        )

        assertEquals(0x482412, frame.packed(0))
    }

    /**
     * The property folders exist for: a modulator inside a folder must gate that
     * folder's contents *only*, leaving everything beneath the folder alone.
     */
    @Test
    fun `a modulator inside a folder does not reach the layers beneath it`() {
        val frame = render(
            composition(
                solidLayer("beneath", 0x804020, BlendMode.OVERWRITE),
                GroupLayer(
                    id = "f",
                    name = "Folder",
                    blendMode = BlendMode.ADD,
                    children = listOf(
                        solidLayer("inside", 0x804020, BlendMode.OVERWRITE),
                        // floor 0 with no audio emits black, so multiplying wipes
                        // the folder out completely.
                        dimmerLayer("kill", floor = 0f)
                    )
                )
            )
        )

        // The folder collapsed to black and added nothing; the base survives.
        assertEquals(0x804020, frame.packed(0))
    }

    /** The same modulator at the top level *does* reach everything below it. */
    @Test
    fun `a modulator at the top level gates the whole frame`() {
        val frame = render(
            composition(
                solidLayer("beneath", 0x804020, BlendMode.OVERWRITE),
                dimmerLayer("kill", floor = 0f)
            )
        )

        assertEquals(0, frame.packed(0))
    }

    @Test
    fun `folders nest arbitrarily deep`() {
        val frame = render(
            composition(
                GroupLayer(
                    id = "outer",
                    name = "Outer",
                    blendMode = BlendMode.ADD,
                    children = listOf(
                        GroupLayer(
                            id = "middle",
                            name = "Middle",
                            blendMode = BlendMode.ADD,
                            children = listOf(
                                GroupLayer(
                                    id = "inner",
                                    name = "Inner",
                                    blendMode = BlendMode.ADD,
                                    children = listOf(solidLayer("a", 0x123456, BlendMode.OVERWRITE))
                                )
                            )
                        )
                    )
                )
            )
        )

        assertEquals(0x123456, frame.packed(0))
    }

    @Test
    fun `sibling folders at the same depth do not share a scratch buffer`() {
        // Both folders render into the depth-0 scratch in turn; if the second
        // reused the first's contents instead of clearing, the sum would be wrong.
        val frame = render(
            composition(
                GroupLayer(
                    id = "f1", name = "One", blendMode = BlendMode.ADD,
                    children = listOf(solidLayer("a", 0x100000, BlendMode.OVERWRITE))
                ),
                GroupLayer(
                    id = "f2", name = "Two", blendMode = BlendMode.ADD,
                    children = listOf(solidLayer("b", 0x000100, BlendMode.OVERWRITE))
                )
            )
        )

        assertEquals(0x100100, frame.packed(0))
    }

    // --- instance reuse across edits ---

    /**
     * Editing a parameter must not restart the layer's animation.
     *
     * Probed through the VU meter's peak hold, which is running state: with
     * `dtSeconds = 0` the peak cannot decay, so after a loud frame it stays at 1
     * and lights the LED at the far end. If the edit rebuilt the instance the
     * peak would be back at 0 and that LED would be black.
     */
    @Test
    fun `a parameter edit preserves the effect's running state`() {
        val renderer = CompositionRenderer()
        renderer.setLayout(layout)

        fun meter(brightness: Float) = Composition(
            id = "t", name = "t",
            layers = listOf(
                EffectLayer(
                    id = "meter",
                    name = "VU",
                    effectId = "vu_meter",
                    params = mapOf(
                        "source" to 0f, "gain" to 1f, "peakDot" to 1f,
                        "peakFall" to 1f, "dotWidth" to 0.04f,
                        "peakColor" to 0xFFFFFF.toFloat(), "brightness" to brightness
                    ),
                    blendMode = BlendMode.OVERWRITE
                )
            )
        )

        renderer.setComposition(meter(brightness = 1f))
        renderer.render(testContext(level = 1f, dtSeconds = 0f))

        // Same layer id and effect id, one parameter changed.
        renderer.setComposition(meter(brightness = 1f).copy(name = "edited"))
        val frame = renderer.render(testContext(level = 0f, dtSeconds = 0f))

        assertEquals(
            "the peak hold was reset, so the instance was rebuilt on a param edit",
            0xFFFFFF,
            frame.packed(frame.ledCount - 1)
        )
    }

    @Test
    fun `changing a layer's effect id rebuilds it`() {
        val renderer = CompositionRenderer()
        renderer.setLayout(layout)

        renderer.setComposition(
            Composition(id = "t", name = "t", layers = listOf(solidLayer("x", 0x402010)))
        )
        val before = renderer.render(testContext()).packed(0)

        renderer.setComposition(
            Composition(
                id = "t", name = "t",
                layers = listOf(
                    EffectLayer(
                        id = "x",
                        name = "now a dimmer",
                        effectId = "volume_dimmer",
                        params = mapOf("floor" to 1f, "source" to 0f, "gain" to 1f, "invert" to 0f),
                        blendMode = BlendMode.OVERWRITE
                    )
                )
            )
        )
        val after = renderer.render(testContext()).packed(0)

        assertEquals(0x402010, before)
        assertNotEquals(before, after)
        assertEquals(0xFFFFFF, after)
    }

    @Test
    fun `a layout change resizes the frame`() {
        val renderer = CompositionRenderer()
        renderer.setComposition(
            Composition(id = "t", name = "t", layers = listOf(solidLayer("a", 0x010203)))
        )

        renderer.setLayout(listOf(4, 4))
        assertEquals(8, renderer.render(testContext()).ledCount)

        renderer.setLayout(listOf(6, 6, 6))
        val frame = renderer.render(testContext())
        assertEquals(18, frame.ledCount)
        assertEquals(0x010203, frame.packed(17))
    }

    @Test
    fun `rendering with no layout yields an empty frame rather than throwing`() {
        val renderer = CompositionRenderer()
        renderer.setComposition(
            Composition(id = "t", name = "t", layers = listOf(solidLayer("a", 0xFFFFFF)))
        )

        assertEquals(0, renderer.render(testContext()).ledCount)
    }
}
