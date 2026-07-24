package com.tailapp.composer

import com.tailapp.model.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tree operations the editor is built from. */
class CompositionEditsTest {

    private fun tree() = Composition(
        id = "c",
        name = "c",
        layers = listOf(
            solidLayer("a", 0x111111),
            GroupLayer(
                id = "folder",
                name = "Folder",
                children = listOf(
                    solidLayer("b", 0x222222),
                    solidLayer("c", 0x333333)
                )
            ),
            solidLayer("d", 0x444444)
        )
    )

    private fun ids(nodes: List<LayerNode>): List<String> = nodes.map { it.id }

    @Test
    fun `findNode reaches inside folders`() {
        assertEquals("b", tree().findNode("b")?.id)
        assertEquals("folder", tree().findNode("folder")?.id)
        assertNull(tree().findNode("nope"))
    }

    @Test
    fun `parentOf distinguishes top level from nested`() {
        assertNull(tree().parentOf("a"))
        assertEquals("folder", tree().parentOf("b"))
    }

    @Test
    fun `updateNode replaces a nested node in place`() {
        val updated = tree().updateNode("b") { (it as EffectLayer).copy(name = "renamed") }

        assertEquals("renamed", updated.findNode("b")?.name)
        // Its siblings and position are untouched.
        val folder = updated.findNode("folder") as GroupLayer
        assertEquals(listOf("b", "c"), ids(folder.children))
    }

    @Test
    fun `removeNode deletes a nested node`() {
        val folder = tree().removeNode("b").findNode("folder") as GroupLayer

        assertEquals(listOf("c"), ids(folder.children))
    }

    @Test
    fun `removing a folder takes its children with it`() {
        val updated = tree().removeNode("folder")

        assertEquals(listOf("a", "d"), ids(updated.layers))
        assertNull(updated.findNode("b"))
    }

    @Test
    fun `addNode appends to the top of the stack`() {
        val updated = tree().addNode(solidLayer("new", 0x555555))

        assertEquals(listOf("a", "folder", "d", "new"), ids(updated.layers))
    }

    @Test
    fun `addNode can target a folder`() {
        val updated = tree().addNode(solidLayer("new", 0x555555), parentId = "folder")
        val folder = updated.findNode("folder") as GroupLayer

        assertEquals(listOf("b", "c", "new"), ids(folder.children))
        assertEquals(listOf("a", "folder", "d"), ids(updated.layers))
    }

    @Test
    fun `moveNode reorders within the top level`() {
        assertEquals(listOf("folder", "a", "d"), ids(tree().moveNode("folder", -1).layers))
        assertEquals(listOf("a", "d", "folder"), ids(tree().moveNode("folder", 1).layers))
    }

    @Test
    fun `moveNode reorders within a folder`() {
        val folder = tree().moveNode("c", -1).findNode("folder") as GroupLayer

        assertEquals(listOf("c", "b"), ids(folder.children))
    }

    @Test
    fun `moveNode past an end is a no-op`() {
        assertEquals(listOf("a", "folder", "d"), ids(tree().moveNode("a", -1).layers))
        assertEquals(listOf("a", "folder", "d"), ids(tree().moveNode("d", 1).layers))
    }

    @Test
    fun `moveNode never lifts a layer out of its folder`() {
        // "b" is first inside the folder; moving it up must not promote it to the
        // top level, because that would silently change which modulators apply.
        val updated = tree().moveNode("b", -1)

        assertEquals("folder", updated.parentOf("b"))
        assertEquals(listOf("a", "folder", "d"), ids(updated.layers))
    }

    @Test
    fun `duplicateNode inserts a copy directly above with a fresh id`() {
        val updated = tree().duplicateNode("a")

        assertEquals(4, updated.layers.size)
        assertEquals("a", updated.layers[0].id)
        assertNotEquals("a", updated.layers[1].id)
        assertEquals("Solid a", updated.layers[1].name)
    }

    @Test
    fun `duplicating a folder gives every descendant a fresh id`() {
        val updated = tree().duplicateNode("folder")
        val copy = updated.layers[2] as GroupLayer

        assertNotEquals("folder", copy.id)
        assertEquals(2, copy.children.size)
        // Ids must be unique across the whole tree — the renderer keys its live
        // effect instances on them, so a duplicate id means a shared instance.
        val all = mutableListOf<String>()
        fun walk(nodes: List<LayerNode>) {
            nodes.forEach {
                all.add(it.id)
                if (it is GroupLayer) walk(it.children)
            }
        }
        walk(updated.layers)
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `groupNode wraps a layer in a folder and moves the mix onto the folder`() {
        val start = tree().updateNode("a") {
            (it as EffectLayer).copy(blendMode = BlendMode.MULTIPLY, opacity = 0.25f)
        }

        val folder = start.groupNode("a", "Wrapped").layers[0] as GroupLayer

        // The folder takes the layer's blend and opacity...
        assertEquals("Wrapped", folder.name)
        assertEquals(BlendMode.MULTIPLY, folder.blendMode)
        assertEquals(0.25f, folder.opacity, 0f)
        // ...and the layer inside is reset, so the mix is not applied twice.
        val inner = folder.children.single()
        assertEquals("a", inner.id)
        assertEquals(BlendMode.ADD, inner.blendMode)
        assertEquals(1f, inner.opacity, 0f)
    }

    @Test
    fun `ungroup splices a folder's children into its place`() {
        val updated = tree().ungroup("folder")

        assertEquals(listOf("a", "b", "c", "d"), ids(updated.layers))
    }

    @Test
    fun `ungrouping an empty folder just removes it`() {
        val start = Composition(
            id = "c", name = "c",
            layers = listOf(solidLayer("a", 0x111111), GroupLayer(id = "f", name = "F"))
        )

        assertEquals(listOf("a"), ids(start.ungroup("f").layers))
    }

    @Test
    fun `withNewIds deep-copies without touching the original`() {
        val original = tree().findNode("folder") as GroupLayer
        val copy = original.withNewIds() as GroupLayer

        assertNotEquals(original.id, copy.id)
        assertTrue(original.children.map { it.id }.none { it in copy.children.map { c -> c.id } })
        // The original is untouched.
        assertEquals(listOf("b", "c"), ids(original.children))
    }
}
