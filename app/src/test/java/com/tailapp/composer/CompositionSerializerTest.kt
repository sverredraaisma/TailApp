package com.tailapp.composer

import com.tailapp.model.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved compositions must survive a round trip exactly, and must survive the
 * app changing underneath them.
 */
class CompositionSerializerTest {

    private val nested = Composition(
        id = "saved",
        name = "Saved \"stack\"",
        brightness = 0.75f,
        layers = listOf(
            EffectLayer(
                id = "base",
                name = "Base",
                effectId = "solid",
                params = mapOf("color" to 0x402010.toFloat(), "brightness" to 0.5f),
                blendMode = BlendMode.OVERWRITE,
                opacity = 0.9f,
                enabled = false,
                flipX = true,
                mirrorY = true
            ),
            GroupLayer(
                id = "folder",
                name = "Folder",
                blendMode = BlendMode.MULTIPLY,
                opacity = 0.25f,
                collapsed = true,
                children = listOf(
                    EffectLayer(
                        id = "inner",
                        name = "Inner",
                        effectId = "beat_flash",
                        params = mapOf("decay" to 0.2f),
                        blendMode = BlendMode.MAX
                    )
                )
            )
        )
    )

    @Test
    fun `a nested composition round-trips exactly`() {
        val restored = CompositionSerializer.fromJson(CompositionSerializer.toJson(nested))

        assertEquals(nested, restored)
    }

    @Test
    fun `a list of compositions round-trips`() {
        val list = listOf(nested, Composition(id = "b", name = "Second"))

        val restored = CompositionSerializer.listFromJson(CompositionSerializer.listToJson(list))

        assertEquals(list, restored)
    }

    @Test
    fun `every built-in round-trips`() {
        CompositionLibrary.BUILT_INS.forEach { built ->
            assertEquals(
                "built-in ${built.id} did not survive a round trip",
                built,
                CompositionSerializer.fromJson(CompositionSerializer.toJson(built))
            )
        }
    }

    @Test
    fun `blend modes travel by name, not by wire id`() {
        // Names are stable across protocol changes; the ids belong to BLE and are
        // free to move there without invalidating everything the user has saved.
        val json = CompositionSerializer.toJson(nested)

        assertTrue(json.contains("\"MULTIPLY\""))
        assertTrue(json.contains("\"OVERWRITE\""))
    }

    @Test
    fun `a layer naming an unknown effect still loads`() {
        // An effect removed by a later build must not cost the user the rest of
        // the stack; the renderer skips it and everything else still runs.
        val json = """
            {"version":1,"id":"c","name":"C","brightness":1,"layers":[
              {"type":"effect","id":"x","name":"Gone","effect":"removed_effect","params":{}},
              {"type":"effect","id":"y","name":"Fine","effect":"solid","params":{}}
            ]}
        """.trimIndent()

        val restored = CompositionSerializer.fromJson(json)

        assertNotNull(restored)
        assertEquals(2, restored!!.layers.size)
        assertEquals("removed_effect", (restored.layers[0] as EffectLayer).effectId)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val json = """{"layers":[{"type":"effect","effect":"solid"}]}"""

        val restored = CompositionSerializer.fromJson(json)!!
        val layer = restored.layers.single() as EffectLayer

        assertEquals(1f, restored.brightness, 0f)
        assertEquals(BlendMode.ADD, layer.blendMode)
        assertEquals(1f, layer.opacity, 0f)
        assertTrue(layer.enabled)
        // The name falls back to the effect's own display name.
        assertEquals("Solid Colour", layer.name)
        // A missing id still has to be unique, so one is generated.
        assertTrue(layer.id.isNotBlank())
    }

    @Test
    fun `an unknown parameter key is dropped rather than rejected`() {
        val json = """
            {"layers":[{"type":"effect","effect":"solid",
             "params":{"color":16711680,"parameterFromTheFuture":3}}]}
        """.trimIndent()

        val layer = CompositionSerializer.fromJson(json)!!.layers.single() as EffectLayer

        // Both survive parsing; the effect's ParamBag is what ignores the unknown
        // one, so nothing is lost if a later build re-adds it.
        assertEquals(16711680f, layer.params["color"]!!, 0f)

        // And applying them cannot throw.
        val effect = ReactiveEffects.create(layer.effectId, layer.params)
        assertNotNull(effect)
    }

    @Test
    fun `a corrupt effect layer with no effect id is dropped`() {
        val json = """{"layers":[{"type":"effect","id":"x","name":"Broken"}]}"""

        assertEquals(0, CompositionSerializer.fromJson(json)!!.layers.size)
    }

    @Test
    fun `malformed json yields null rather than throwing`() {
        assertNull(CompositionSerializer.fromJson("not json at all"))
        assertNull(CompositionSerializer.fromJson("{\"layers\": [ "))
        assertNull(CompositionSerializer.fromJson("[1,2,3]"))
        assertNull(CompositionSerializer.fromJson(""))
    }

    @Test
    fun `a malformed library yields an empty list rather than throwing`() {
        assertEquals(0, CompositionSerializer.listFromJson("{{{").size)
        assertEquals(0, CompositionSerializer.listFromJson("").size)
    }

    @Test
    fun `names with quotes and newlines survive`() {
        val awkward = Composition(id = "q", name = "a \"b\" \\ c\nd\te")

        assertEquals(
            awkward.name,
            CompositionSerializer.fromJson(CompositionSerializer.toJson(awkward))?.name
        )
    }
}
