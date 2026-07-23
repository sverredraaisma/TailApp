package com.tailapp.led

import com.tailapp.led.effects.StaticColorRenderer
import com.tailapp.model.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors `LayerCompositor::render` (`main/led/layer_compositor.cpp`): layers
 * composite bottom-to-top into an initially-black output buffer, and a
 * disabled or absent layer contributes nothing.
 */
class LayerCompositorTest {

    private fun redLayer() = StaticColorRenderer().apply { setParam(0, 255f); setParam(1, 0f); setParam(2, 0f) }
    private fun blueLayer() = StaticColorRenderer().apply { setParam(0, 0f); setParam(1, 0f); setParam(2, 255f) }

    private val coords = LedLayout.coordsFor(listOf(4))

    @Test
    fun `empty stack renders black`() {
        val out = PixelBuffer(coords.size)
        LayerCompositor().render(emptyList(), coords, dt = 0f, out = out)
        for (i in 0 until out.ledCount) assertEquals(0, out.packed(i))
    }

    @Test
    fun `a later layer overwrites an earlier one, bottom-to-top`() {
        val out = PixelBuffer(coords.size)
        val layers = listOf(
            LayerCompositor.Layer(redLayer(), BlendMode.OVERWRITE),
            LayerCompositor.Layer(blueLayer(), BlendMode.OVERWRITE),
        )
        LayerCompositor().render(layers, coords, dt = 0f, out = out)
        for (i in 0 until out.ledCount) assertEquals(0x0000FF, out.packed(i))
    }

    @Test
    fun `blend mode is applied when compositing a layer`() {
        val out = PixelBuffer(coords.size)
        val white = StaticColorRenderer().apply { setParam(0, 255f); setParam(1, 255f); setParam(2, 255f) }
        val half = StaticColorRenderer().apply { setParam(0, 128f); setParam(1, 128f); setParam(2, 128f) }
        val layers = listOf(
            LayerCompositor.Layer(white, BlendMode.OVERWRITE),
            LayerCompositor.Layer(half, BlendMode.MULTIPLY),
        )
        LayerCompositor().render(layers, coords, dt = 0f, out = out)
        // 255*128/255 = 128 per channel.
        for (i in 0 until out.ledCount) assertEquals(0x808080, out.packed(i))
    }

    @Test
    fun `a disabled layer is skipped entirely`() {
        val out = PixelBuffer(coords.size)
        val layers = listOf(
            LayerCompositor.Layer(redLayer(), BlendMode.OVERWRITE),
            LayerCompositor.Layer(blueLayer(), BlendMode.OVERWRITE, enabled = false),
        )
        LayerCompositor().render(layers, coords, dt = 0f, out = out)
        for (i in 0 until out.ledCount) assertEquals(0xFF0000, out.packed(i))
    }

    @Test
    fun `a null renderer slot is skipped entirely`() {
        val out = PixelBuffer(coords.size)
        val layers = listOf(
            LayerCompositor.Layer(redLayer(), BlendMode.OVERWRITE),
            LayerCompositor.Layer(null, BlendMode.OVERWRITE),
        )
        LayerCompositor().render(layers, coords, dt = 0f, out = out)
        for (i in 0 until out.ledCount) assertEquals(0xFF0000, out.packed(i))
    }

    @Test
    fun `out buffer must match coords size`() {
        var threw = false
        try {
            LayerCompositor().render(emptyList(), coords, dt = 0f, out = PixelBuffer(coords.size + 1))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertEquals(true, threw)
    }
}
