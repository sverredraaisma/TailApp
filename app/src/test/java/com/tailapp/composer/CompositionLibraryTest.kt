package com.tailapp.composer

import com.tailapp.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped stacks and the user's saved ones.
 *
 * The built-in checks matter more than they look: those compositions are written
 * by hand as literal parameter maps, so a typo'd key or an out-of-range value
 * would silently render as the schema default with nothing to indicate why.
 */
class CompositionLibraryTest {

    private fun library(prefs: FakeSharedPreferences = FakeSharedPreferences()) =
        CompositionLibrary(prefs)

    // --- the shipped stacks ---

    @Test
    fun `built-in ids are unique`() {
        val ids = CompositionLibrary.BUILT_INS.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every built-in layer id is unique within its composition`() {
        // The renderer keys live effect instances on layer id; a collision would
        // make two layers share one instance and its animation state.
        CompositionLibrary.BUILT_INS.forEach { composition ->
            val ids = mutableListOf<String>()
            fun walk(nodes: List<LayerNode>) {
                nodes.forEach {
                    ids.add(it.id)
                    if (it is GroupLayer) walk(it.children)
                }
            }
            walk(composition.layers)

            assertEquals("duplicate layer id in ${composition.id}", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun `every built-in layer names a registered effect`() {
        forEachBuiltInEffectLayer { composition, layer ->
            assertNotNull(
                "${composition.id} uses unknown effect '${layer.effectId}'",
                ReactiveEffects.spec(layer.effectId)
            )
        }
    }

    @Test
    fun `every built-in parameter key exists in its effect's schema`() {
        forEachBuiltInEffectLayer { composition, layer ->
            val schema = ReactiveEffects.spec(layer.effectId)!!.schema.associateBy { it.key }
            layer.params.keys.forEach { key ->
                assertTrue(
                    "${composition.id}/${layer.id}: '$key' is not a parameter of ${layer.effectId}",
                    schema.containsKey(key)
                )
            }
        }
    }

    @Test
    fun `every built-in scalar parameter is inside its declared range`() {
        forEachBuiltInEffectLayer { composition, layer ->
            val schema = ReactiveEffects.spec(layer.effectId)!!.schema.associateBy { it.key }
            layer.params.forEach { (key, value) ->
                val param = schema[key]
                if (param is EffectParam.Scalar) {
                    assertTrue(
                        "${composition.id}/${layer.id}: $key = $value outside " +
                            "[${param.min}, ${param.max}]",
                        value >= param.min && value <= param.max
                    )
                }
                if (param is EffectParam.Choice) {
                    assertTrue(
                        "${composition.id}/${layer.id}: $key = $value is not a valid option",
                        value.toInt() in param.options.indices
                    )
                }
            }
        }
    }

    @Test
    fun `every built-in renders something for a loud beat`() {
        // A stack that is black under every condition is a broken stack.
        val coords = com.tailapp.led.LedLayout.coordsFor(listOf(8, 10, 12, 10, 8))
        val ctx = testContext(
            timeSeconds = 4f,
            dtSeconds = 1f / 30f,
            bpm = 128f,
            lastBeat = com.tailapp.beat.BeatEvent(
                com.tailapp.beat.BeatType.DOWNBEAT, 0L, 128f, 0, 0.9f
            ),
            beatCount = 8,
            secondsSinceBeat = 0f,
            onDownbeat = true,
            level = 1f,
            bass = 1f,
            mid = 0.8f,
            high = 0.6f,
            bands = FloatArray(205) { 0.8f }
        )

        CompositionLibrary.BUILT_INS.forEach { composition ->
            val renderer = CompositionRenderer()
            renderer.setLayout(listOf(8, 10, 12, 10, 8))
            renderer.setComposition(composition)
            val frame = renderer.render(ctx)

            val lit = (0 until frame.ledCount).any { frame.packed(it) != 0 }
            assertTrue("built-in '${composition.name}' renders black on a loud beat", lit)
            assertEquals(coords.size, frame.ledCount)
        }
    }

    private fun forEachBuiltInEffectLayer(check: (Composition, EffectLayer) -> Unit) {
        CompositionLibrary.BUILT_INS.forEach { composition ->
            fun walk(nodes: List<LayerNode>) {
                nodes.forEach { node ->
                    when (node) {
                        is EffectLayer -> check(composition, node)
                        is GroupLayer -> walk(node.children)
                    }
                }
            }
            walk(composition.layers)
        }
    }

    // --- the user's stacks ---

    @Test
    fun `a fresh library offers the built-ins and starts on the first`() {
        val library = library()

        assertEquals(CompositionLibrary.BUILT_INS.size, library.compositions.value.size)
        assertEquals(CompositionLibrary.BUILT_INS.first().id, library.activeId.value)
        assertEquals(CompositionLibrary.BUILT_INS.first(), library.active())
    }

    @Test
    fun `saving a new composition adds it after the built-ins`() {
        val library = library()

        library.save(Composition(id = "mine", name = "Mine"))

        assertEquals(CompositionLibrary.BUILT_INS.size + 1, library.compositions.value.size)
        assertEquals("mine", library.compositions.value.last().id)
        assertEquals("Mine", library.byId("mine")?.name)
    }

    @Test
    fun `saving over a built-in shadows it in place rather than duplicating it`() {
        val library = library()
        val builtIn = CompositionLibrary.BUILT_INS.first()

        library.save(builtIn.copy(name = "Edited"))

        assertEquals(CompositionLibrary.BUILT_INS.size, library.compositions.value.size)
        assertEquals("Edited", library.compositions.value.first().name)
        assertEquals("Edited", library.byId(builtIn.id)?.name)
        assertTrue(library.isModifiedBuiltIn(builtIn.id))
    }

    @Test
    fun `resetting a built-in restores the shipped version`() {
        val library = library()
        val builtIn = CompositionLibrary.BUILT_INS.first()
        library.save(builtIn.copy(name = "Edited"))

        library.resetToBuiltIn(builtIn.id)

        assertEquals(builtIn, library.byId(builtIn.id))
        assertFalse(library.isModifiedBuiltIn(builtIn.id))
    }

    @Test
    fun `deleting a built-in resets it instead, because it cannot be removed`() {
        val library = library()
        val builtIn = CompositionLibrary.BUILT_INS.first()
        library.save(builtIn.copy(name = "Edited"))

        library.delete(builtIn.id)

        assertEquals(builtIn, library.byId(builtIn.id))
        assertEquals(CompositionLibrary.BUILT_INS.size, library.compositions.value.size)
    }

    @Test
    fun `deleting a user composition removes it and moves off it`() {
        val library = library()
        library.save(Composition(id = "mine", name = "Mine"))
        library.setActive("mine")

        library.delete("mine")

        assertNull(library.byId("mine"))
        assertEquals(CompositionLibrary.BUILT_INS.first().id, library.activeId.value)
    }

    @Test
    fun `the saved set and the active choice survive a new library over the same prefs`() {
        val prefs = FakeSharedPreferences()
        val first = library(prefs)
        first.save(Composition(id = "mine", name = "Mine", brightness = 0.5f))
        first.setActive("mine")

        val restored = library(prefs)

        assertEquals("mine", restored.activeId.value)
        assertEquals("Mine", restored.active().name)
        assertEquals(0.5f, restored.active().brightness, 0f)
    }

    @Test
    fun `an active id pointing at a deleted composition falls back to a built-in`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(CompositionLibrary.KEY_ACTIVE, "long-gone").apply()

        val library = library(prefs)

        assertEquals(CompositionLibrary.BUILT_INS.first().id, library.activeId.value)
        assertNotNull(library.active())
    }

    @Test
    fun `setActive ignores an id the library does not have`() {
        val library = library()

        library.setActive("nonsense")

        assertEquals(CompositionLibrary.BUILT_INS.first().id, library.activeId.value)
    }

    @Test
    fun `a corrupt saved library degrades to the built-ins`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(CompositionLibrary.KEY_USER, "{not json").apply()

        val library = library(prefs)

        assertEquals(CompositionLibrary.BUILT_INS.size, library.compositions.value.size)
        assertNotNull(library.active())
    }

    @Test
    fun `the compositions flow re-emits when a stack is saved`() {
        val library = library()
        val before = library.compositions.value

        library.save(Composition(id = "mine", name = "Mine"))

        assertTrue(library.compositions.value !== before)
        assertEquals("mine", library.compositions.value.last().id)
    }
}
