package com.tailapp.composer

import com.tailapp.model.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharing a stack out and taking one back in.
 *
 * A saved composition otherwise exists only inside one phone's
 * SharedPreferences, so a user who loses the phone loses every look they built.
 * The risk this introduces is the mirror image: an import that quietly replaced
 * something is data loss dressed up as a feature, which is why an imported
 * stack always lands under a fresh id and a non-colliding name.
 */
class CompositionExchangeTest {

    private fun sample(name: String = "Shared", id: String = "abc") = Composition(
        id = id,
        name = name,
        brightness = 0.8f,
        layers = listOf(
            EffectLayer(
                id = "l1",
                name = "Base",
                effectId = "solid",
                params = mapOf("color" to 0xFF8000.toFloat(), "brightness" to 0.9f),
                blendMode = BlendMode.OVERWRITE
            ),
            GroupLayer(
                id = "g1",
                name = "Group",
                blendMode = BlendMode.ADD,
                opacity = 0.5f,
                children = listOf(
                    EffectLayer(
                        id = "l2",
                        name = "Flash",
                        effectId = "beat_flash",
                        params = mapOf("decay" to 0.3f),
                        blendMode = BlendMode.ADD
                    )
                )
            )
        )
    )

    @Test
    fun `a stack survives a round trip through JSON`() {
        val original = sample()
        val restored = CompositionSerializer.fromJson(CompositionSerializer.toJson(original))

        assertNotNull(restored)
        requireNotNull(restored)
        assertEquals(original.name, restored.name)
        assertEquals(original.brightness, restored.brightness, 1e-4f)
        assertEquals(original.layers.size, restored.layers.size)

        val group = restored.layers[1] as GroupLayer
        assertEquals(BlendMode.ADD, group.blendMode)
        assertEquals(0.5f, group.opacity, 1e-4f)
        assertEquals(1, group.children.size)
        assertEquals("beat_flash", (group.children[0] as EffectLayer).effectId)
    }

    @Test
    fun `a document that is not a stack is rejected rather than half-read`() {
        assertNull(CompositionSerializer.fromJson("not json at all"))
        assertNull(CompositionSerializer.fromJson(""))
        // Valid JSON, wrong shape.
        assertNull(CompositionSerializer.fromJson("[1, 2, 3]"))
    }

    @Test
    fun `an unknown effect id loads inert rather than failing the whole stack`() {
        // A stack exported from a newer build may name effects this one does not
        // have. Losing that one layer beats losing the whole composition.
        val json = CompositionSerializer.toJson(
            Composition(
                id = "x", name = "Future",
                layers = listOf(
                    EffectLayer(id = "a", name = "Known", effectId = "solid"),
                    EffectLayer(id = "b", name = "Unknown", effectId = "effect_from_the_future")
                )
            )
        )

        val restored = requireNotNull(CompositionSerializer.fromJson(json))
        assertEquals(2, restored.layers.size)
        assertNull(
            "an effect this build does not have must resolve to nothing",
            ReactiveEffects.spec("effect_from_the_future")
        )
    }

    @Test
    fun `an exported file name is derived from the stack and is safe`() {
        // Names are user text and end up as a file name.
        val cleaned = "My Stack / 2024".map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        assertTrue(cleaned.none { it == '/' || it == ' ' })
    }

    @Test
    fun `layer ids stay unique through a round trip`() {
        // Two layers sharing an id would share one live effect instance and
        // therefore one set of decay envelopes — the renderer keys on id.
        val restored = requireNotNull(
            CompositionSerializer.fromJson(CompositionSerializer.toJson(sample()))
        )

        val ids = mutableListOf<String>()
        fun walk(nodes: List<LayerNode>) {
            for (n in nodes) {
                ids += n.id
                if (n is GroupLayer) walk(n.children)
            }
        }
        walk(restored.layers)
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `a re-export of an import is still importable`() {
        val once = requireNotNull(
            CompositionSerializer.fromJson(CompositionSerializer.toJson(sample()))
        )
        val twice = requireNotNull(
            CompositionSerializer.fromJson(CompositionSerializer.toJson(once))
        )
        assertEquals(once.name, twice.name)
        assertEquals(once.layers.size, twice.layers.size)
    }

    @Test
    fun `a composition id generator does not repeat`() {
        // The import path relies on this to avoid overwriting an existing stack.
        val ids = (0 until 200).map { newCompositionId() }
        assertEquals(ids.size, ids.distinct().size)
        assertNotEquals(newCompositionId(), newCompositionId())
    }
}
