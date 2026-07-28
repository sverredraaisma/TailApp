package com.tailapp.composer

import com.tailapp.led.LedLayout
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parameter values arrive from a saved — or *imported* — file, which is arbitrary
 * user-supplied JSON. Nothing upstream promises a `Choice` index names one of its
 * options or that a `Scalar` is inside the range the editor's slider offers, so
 * the bag itself is the last place that can promise it.
 *
 * The failure this prevents is not a wrong colour: an effect subscripting a
 * parallel array with a raw index throws out of the render coroutine, which kills
 * the render loop while the analysis loop lives on — a session that reports
 * itself as running with the tail frozen on its last frame.
 */
class EffectParamTest {

    private val schema = listOf(
        EffectParam.Scalar("rate", "Rate", 1f, 0.5f, 4f),
        EffectParam.Choice("mode", "Mode", listOf("A", "B", "C"), 1),
        EffectParam.Color("colour", "Colour", 0x102030),
        EffectParam.Toggle("on", "On", false)
    )

    private fun bag() = ParamBag(schema)

    @Test
    fun `an out-of-range choice index is clamped to a real option`() {
        val p = bag()

        p.setAll(mapOf("mode" to 5f))
        assertEquals(2, p.enumIndex("mode"))

        p.setAll(mapOf("mode" to -3f))
        assertEquals(0, p.enumIndex("mode"))
    }

    @Test
    fun `a scalar outside its declared range is clamped to the range`() {
        val p = bag()

        p.setAll(mapOf("rate" to 900f))
        assertEquals(4f, p.float("rate"), 0f)

        p.set("rate", -12f)
        assertEquals(0.5f, p.float("rate"), 0f)
    }

    @Test
    fun `a non-finite value falls back to the schema default`() {
        val p = bag()

        // NaN cannot be clamped — every comparison against it is false — so
        // coerceIn would pass it straight through and an effect would multiply
        // by it.
        p.setAll(mapOf("rate" to Float.NaN, "mode" to Float.NaN, "on" to Float.NaN))

        assertEquals(1f, p.float("rate"), 0f)
        assertEquals(1, p.enumIndex("mode"))
        assertEquals(false, p.bool("on"))

        p.set("rate", Float.POSITIVE_INFINITY)
        assertEquals(1f, p.float("rate"), 0f)
    }

    @Test
    fun `a toggle stores only zero or one`() {
        val p = bag()

        p.setAll(mapOf("on" to 7.5f))
        assertEquals(1f, p.raw("on"), 0f)
        assertTrue(p.bool("on"))
    }

    @Test
    fun `a colour is masked to its packed 24 bits`() {
        val p = bag()

        p.setAll(mapOf("colour" to -1f))
        assertEquals(0xFFFFFF, p.color("colour"))

        p.setAll(mapOf("colour" to 0x11223344.toFloat()))
        assertTrue(p.color("colour") in 0..0xFFFFFF)
    }

    @Test
    fun `an unknown key is still ignored`() {
        val p = bag()

        p.setAll(mapOf("parameterFromTheFuture" to 3f))

        assertEquals(0f, p.raw("parameterFromTheFuture"), 0f)
    }

    /**
     * The concrete case: a hand-edited `.tailstack.json` naming a strobe division
     * that does not exist. Before the clamp this threw
     * `ArrayIndexOutOfBoundsException` out of `StrobeEffect.render`.
     */
    @Test
    fun `an imported layer with an impossible choice index renders instead of throwing`() {
        val json = """
            {"layers":[{"type":"effect","id":"s","effect":"strobe",
             "params":{"division":5,"duty":0.35,"brightness":1,"floor":0,"freeHz":8}}]}
        """.trimIndent()

        val composition = requireNotNull(CompositionSerializer.fromJson(json))
        val renderer = CompositionRenderer()
        renderer.setLayout(listOf(4, 4))
        renderer.setComposition(composition)

        val beat = com.tailapp.beat.BeatEvent(
            com.tailapp.beat.BeatType.BEAT, 0L, 120f, 0, 0.9f
        )
        val frame = renderer.render(
            testContext(bpm = 120f, lastBeat = beat, beatCount = 0, beatPhase = 0f)
        )

        // The clamped division still strobes; the point is that it renders.
        assertEquals(8, frame.ledCount)
    }

    @Test
    fun `every registered effect survives every parameter being far out of range`() {
        val coords = LedLayout.coordsFor(listOf(6, 6))
        val buffer = PixelBuffer(coords.size)

        for (spec in ReactiveEffects.ALL) {
            for (value in listOf(-1e9f, -1f, 1e9f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val effect = ReactiveEffects.create(
                    spec.id,
                    spec.schema.associate { it.key to value }
                )!!
                buffer.clear()

                // The claim is narrow and load-bearing: junk parameters may make
                // an effect look wrong, but they may never take the render loop
                // down with them.
                effect.render(buffer, coords, testContext(bpm = 120f, level = 1f))
            }
        }
    }
}
