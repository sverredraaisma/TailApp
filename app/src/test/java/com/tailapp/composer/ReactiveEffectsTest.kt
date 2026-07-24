package com.tailapp.composer

import com.tailapp.beat.BeatEvent
import com.tailapp.beat.BeatType
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.led.LedLayout
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registry-wide invariants.
 *
 * Every effect in the library is exercised here rather than only the ones with
 * their own test: a new effect gets this coverage for free, which is the point —
 * the cost of adding one should stay "one file and one registry row".
 */
class ReactiveEffectsTest {

    private val layout = listOf(8, 10, 12, 10, 8)
    private val coords = LedLayout.coordsFor(layout)

    /** Contexts spanning silence, a loud beat, a drop and a build-up. */
    private fun contexts(): List<ReactiveContext> {
        val beat = BeatEvent(BeatType.DOWNBEAT, 0L, 128f, 0, 0.9f)
        val drop = DropEvent(0L, 1f, 4f, 4f, SectionState.BUILDUP)
        val spectrum = FloatArray(205) { it / 204f }

        return listOf(
            // Nothing has happened yet: no beat, no audio, no tempo.
            testContext(),
            // Mid-session, loud, on a downbeat, with a full spectrum.
            testContext(
                timeSeconds = 12.5f,
                dtSeconds = 1f / 30f,
                bpm = 128f,
                lastBeat = beat,
                beatCount = 40,
                secondsSinceBeat = 0.05f,
                beatPhase = 0.1f,
                barPhase = 0.02f,
                onDownbeat = true,
                level = 1f,
                rms = 0.4f,
                bass = 1f,
                mid = 0.7f,
                high = 0.3f,
                bands = spectrum,
                lastDrop = drop,
                secondsSinceDrop = 0.2f,
                section = SectionState.BUILDUP,
                sectionRamp = 0.8f
            ),
            // A predicted beat that has not arrived yet, mid-breakdown.
            testContext(
                timeSeconds = 30f,
                dtSeconds = 1f / 30f,
                bpm = 90f,
                lastBeat = beat,
                beatCount = 1,
                secondsSinceBeat = -0.02f,
                level = 0.2f,
                bands = FloatArray(205),
                section = SectionState.BREAKDOWN
            )
        )
    }

    @Test
    fun `effect ids are unique`() {
        val ids = ReactiveEffects.ALL.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every effect declares unique parameter keys`() {
        ReactiveEffects.ALL.forEach { spec ->
            val keys = spec.schema.map { it.key }
            assertEquals("duplicate param key in ${spec.id}", keys.size, keys.toSet().size)
        }
    }

    @Test
    fun `every scalar default sits inside its own declared range`() {
        ReactiveEffects.ALL.forEach { spec ->
            spec.schema.filterIsInstance<EffectParam.Scalar>().forEach { param ->
                assertTrue(
                    "${spec.id}.${param.key} default ${param.default} outside " +
                        "[${param.min}, ${param.max}]",
                    param.default >= param.min && param.default <= param.max
                )
            }
        }
    }

    @Test
    fun `every choice default indexes a real option`() {
        ReactiveEffects.ALL.forEach { spec ->
            spec.schema.filterIsInstance<EffectParam.Choice>().forEach { param ->
                assertTrue(
                    "${spec.id}.${param.key} default out of range",
                    param.default.toInt() in param.options.indices
                )
            }
        }
    }

    @Test
    fun `defaultParams covers the whole schema`() {
        ReactiveEffects.ALL.forEach { spec ->
            val defaults = ReactiveEffects.defaultParams(spec.id)
            assertEquals(spec.schema.size, defaults.size)
            spec.schema.forEach { param ->
                assertEquals("${spec.id}.${param.key}", param.default, defaults[param.key]!!, 0f)
            }
        }
    }

    @Test
    fun `create returns a fresh instance carrying the schema defaults`() {
        ReactiveEffects.ALL.forEach { spec ->
            val first = ReactiveEffects.create(spec.id)
            val second = ReactiveEffects.create(spec.id)

            assertNotNull(spec.id, first)
            assertTrue("${spec.id} handed out the same instance twice", first !== second)
            assertEquals(spec.id, first!!.spec.id)
        }
    }

    @Test
    fun `an unknown id yields null`() {
        assertNull(ReactiveEffects.create("no_such_effect"))
        assertNull(ReactiveEffects.spec("no_such_effect"))
        assertEquals(0, ReactiveEffects.defaultParams("no_such_effect").size)
    }

    @Test
    fun `byCategory accounts for every effect`() {
        val grouped = ReactiveEffects.byCategory()

        assertEquals(ReactiveEffects.ALL.size, grouped.values.sumOf { it.size })
        grouped.forEach { (category, specs) ->
            specs.forEach { assertEquals(category, it.category) }
        }
    }

    /**
     * The blanket robustness check: no effect may throw, and none may write a
     * channel outside `0..255`, on any of the states the pipeline can hand it —
     * including the awkward ones (silence, a beat in the future, a zero `dt`).
     */
    @Test
    fun `every effect renders every context without throwing or clipping`() {
        val buffer = PixelBuffer(coords.size)

        ReactiveEffects.ALL.forEach { spec ->
            contexts().forEachIndexed { index, ctx ->
                val effect = ReactiveEffects.create(spec.id)!!
                buffer.clear()

                effect.render(buffer, coords, ctx)

                for (i in 0 until buffer.ledCount) {
                    assertTrue(
                        "${spec.id} wrote an out-of-range channel in context $index",
                        buffer.red(i) in 0..255 &&
                            buffer.green(i) in 0..255 &&
                            buffer.blue(i) in 0..255
                    )
                }
            }
        }
    }

    @Test
    fun `every effect copes with an empty strip`() {
        val empty = PixelBuffer(0)

        ReactiveEffects.ALL.forEach { spec ->
            ReactiveEffects.create(spec.id)!!.render(empty, emptyList(), testContext())
        }
    }

    /**
     * Rendering the same frame twice must produce the same pixels. Effects that
     * sparkle or flicker use a hash of the LED index and a tick, never an RNG,
     * precisely so this holds — a beat is drawn across many frames, and a set
     * re-rolled per frame would shimmer into mush.
     */
    @Test
    fun `rendering is deterministic for a given context`() {
        val first = PixelBuffer(coords.size)
        val second = PixelBuffer(coords.size)
        val ctx = contexts()[1]

        ReactiveEffects.ALL.forEach { spec ->
            ReactiveEffects.create(spec.id)!!.render(first, coords, ctx)
            ReactiveEffects.create(spec.id)!!.render(second, coords, ctx)

            for (i in 0 until first.ledCount) {
                assertEquals(
                    "${spec.id} is not deterministic at LED $i",
                    first.packed(i),
                    second.packed(i)
                )
            }
        }
    }

    @Test
    fun `modulators emit neutral grey so they multiply cleanly`() {
        val buffer = PixelBuffer(coords.size)
        val ctx = contexts()[1]

        ReactiveEffects.ALL.filter { it.category == EffectCategory.MODULATOR }.forEach { spec ->
            buffer.clear()
            ReactiveEffects.create(spec.id)!!.render(buffer, coords, ctx)

            for (i in 0 until buffer.ledCount) {
                assertEquals(
                    "${spec.id} is not grey at LED $i — it would tint what it modulates",
                    buffer.red(i),
                    buffer.green(i)
                )
                assertEquals(buffer.green(i), buffer.blue(i))
            }
        }
    }
}
