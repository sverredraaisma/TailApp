package com.tailapp.viewmodel

import com.tailapp.composer.Composition
import com.tailapp.composer.CompositionLibrary
import com.tailapp.composer.EffectLayer
import com.tailapp.composer.GroupLayer
import com.tailapp.composer.findNode
import com.tailapp.effects.LightingEngine
import com.tailapp.lighting.PreviewLightingOutput
import com.tailapp.model.BlendMode
import com.tailapp.repository.DeviceRepository
import com.tailapp.testutil.FakeBleTransport
import com.tailapp.testutil.FakeSharedPreferences
import com.tailapp.testutil.RecordingLightingOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's behaviour, driven against a real [LightingEngine] and a real
 * [CompositionLibrary] over fake preferences — no Android runtime.
 *
 * The property most of these assert is the one the screen depends on: **an edit
 * reaches the engine immediately**, saved or not, because the stack is built by
 * watching the tail react.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EffectComposerViewModelTest {

    private var repositoryScope: CoroutineScope? = null

    @After
    fun cancelRepositoryScope() {
        repositoryScope?.cancel()
        repositoryScope = null
    }

    private fun newRepository(): DeviceRepository {
        val scope = CoroutineScope(StandardTestDispatcher())
        repositoryScope = scope
        return DeviceRepository(FakeBleTransport(), scope)
    }

    private fun newEngine(): LightingEngine = LightingEngine(
        output = RecordingLightingOutput(),
        ledLayout = MutableStateFlow(listOf(4, 4)),
        scope = CoroutineScope(Job())
    )

    private class Fixture(
        val viewModel: EffectComposerViewModel,
        val engine: LightingEngine,
        val library: CompositionLibrary
    )

    private fun fixture(
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        engine: LightingEngine = newEngine()
    ): Fixture {
        val library = CompositionLibrary(prefs)
        val viewModel = EffectComposerViewModel(
            engine = engine,
            library = library,
            preview = PreviewLightingOutput(),
            deviceRepository = newRepository()
        )
        return Fixture(viewModel, engine, library)
    }

    // --- opening ---

    @Test
    fun `opens on the library's active stack`() {
        val fixture = fixture()

        assertEquals(CompositionLibrary.BUILT_INS.first().id, fixture.viewModel.composition.value.id)
        assertFalse(fixture.viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `the effect palette covers the whole registry`() {
        val fixture = fixture()

        assertEquals(
            com.tailapp.composer.ReactiveEffects.ALL.size,
            fixture.viewModel.effectsByCategory.values.sumOf { it.size }
        )
    }

    // --- edits reach the engine ---

    @Test
    fun `adding a layer appends it and applies to the engine at once`() {
        val fixture = fixture()
        val before = fixture.viewModel.composition.value.layers.size

        fixture.viewModel.addEffect("beat_flash")

        val layers = fixture.viewModel.composition.value.layers
        assertEquals(before + 1, layers.size)
        assertEquals("beat_flash", (layers.last() as EffectLayer).effectId)
        // The engine holds the edited tree, not the stored one.
        assertEquals(layers.size, fixture.engine.composition.layers.size)
        assertTrue(fixture.viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `a new layer carries its effect's schema defaults`() {
        val fixture = fixture()

        fixture.viewModel.addEffect("beat_flash")

        val layer = fixture.viewModel.composition.value.layers.last() as EffectLayer
        assertEquals(
            com.tailapp.composer.ReactiveEffects.defaultParams("beat_flash"),
            layer.params
        )
    }

    @Test
    fun `a modulator is added as multiply, since that is the only way it does anything`() {
        val fixture = fixture()

        fixture.viewModel.addEffect("volume_dimmer")

        assertEquals(
            BlendMode.MULTIPLY,
            fixture.viewModel.composition.value.layers.last().blendMode
        )
    }

    @Test
    fun `the first layer of an empty stack is added as the base`() {
        val fixture = fixture()
        fixture.viewModel.newComposition()
        // newComposition seeds a base layer; clear it to get a genuinely empty stack.
        val seeded = fixture.viewModel.composition.value.layers.single().id
        fixture.viewModel.deleteLayer(seeded)

        fixture.viewModel.addEffect("rainbow")

        assertEquals(
            BlendMode.OVERWRITE,
            fixture.viewModel.composition.value.layers.single().blendMode
        )
    }

    @Test
    fun `an unknown effect id adds nothing`() {
        val fixture = fixture()
        val before = fixture.viewModel.composition.value.layers.size

        fixture.viewModel.addEffect("no_such_effect")

        assertEquals(before, fixture.viewModel.composition.value.layers.size)
    }

    @Test
    fun `a parameter edit reaches the engine`() {
        val fixture = fixture()
        fixture.viewModel.addEffect("beat_flash")
        val id = fixture.viewModel.composition.value.layers.last().id

        fixture.viewModel.setParam(id, "decay", 1.25f)

        val edited = fixture.engine.composition.findNode(id) as EffectLayer
        assertEquals(1.25f, edited.params["decay"]!!, 0f)
    }

    @Test
    fun `blend, opacity, enabled and name edits all apply`() {
        val fixture = fixture()
        fixture.viewModel.addEffect("beat_flash")
        val id = fixture.viewModel.composition.value.layers.last().id

        fixture.viewModel.setBlend(id, BlendMode.MAX)
        fixture.viewModel.setOpacity(id, 0.4f)
        fixture.viewModel.setEnabled(id, false)
        fixture.viewModel.setName(id, "Renamed")
        fixture.viewModel.setTransform(id, flipX = true, flipY = false, mirrorX = true, mirrorY = false)

        val node = fixture.engine.composition.findNode(id)!!
        assertEquals(BlendMode.MAX, node.blendMode)
        assertEquals(0.4f, node.opacity, 0f)
        assertFalse(node.enabled)
        assertEquals("Renamed", node.name)
        assertTrue(node.flipX)
        assertTrue(node.mirrorX)
        assertFalse(node.flipY)
    }

    @Test
    fun `opacity is clamped`() {
        val fixture = fixture()
        val id = fixture.viewModel.composition.value.layers.first().id

        fixture.viewModel.setOpacity(id, 5f)
        assertEquals(1f, fixture.viewModel.composition.value.findNode(id)!!.opacity, 0f)

        fixture.viewModel.setOpacity(id, -5f)
        assertEquals(0f, fixture.viewModel.composition.value.findNode(id)!!.opacity, 0f)
    }

    @Test
    fun `master brightness is clamped and applied`() {
        val fixture = fixture()

        fixture.viewModel.setBrightness(3f)

        assertEquals(1f, fixture.viewModel.composition.value.brightness, 0f)
        assertEquals(1f, fixture.engine.composition.brightness, 0f)
    }

    // --- structure ---

    @Test
    fun `adding into a folder nests rather than appending`() {
        val fixture = fixture()
        fixture.viewModel.addFolder()
        val folderId = fixture.viewModel.composition.value.layers.last().id

        fixture.viewModel.addEffect("sparkle", parentId = folderId)

        val folder = fixture.viewModel.composition.value.findNode(folderId) as GroupLayer
        assertEquals(1, folder.children.size)
        assertEquals("sparkle", (folder.children.single() as EffectLayer).effectId)
    }

    @Test
    fun `wrapping a layer in a folder then ungrouping restores it`() {
        val fixture = fixture()
        val id = fixture.viewModel.composition.value.layers.first().id

        fixture.viewModel.groupLayer(id)
        val folder = fixture.viewModel.composition.value.layers.first()
        assertTrue(folder is GroupLayer)
        assertEquals(id, (folder as GroupLayer).children.single().id)

        fixture.viewModel.ungroupFolder(folder.id)

        assertEquals(id, fixture.viewModel.composition.value.layers.first().id)
    }

    @Test
    fun `deleting a layer removes it and closes its parameter panel`() {
        val fixture = fixture()
        fixture.viewModel.addEffect("beat_flash")
        val id = fixture.viewModel.composition.value.layers.last().id
        assertEquals(id, fixture.viewModel.expandedLayerId.value)

        fixture.viewModel.deleteLayer(id)

        assertNull(fixture.viewModel.composition.value.findNode(id))
        assertNull(fixture.viewModel.expandedLayerId.value)
    }

    @Test
    fun `moving a layer reorders the stack`() {
        val fixture = fixture()
        fixture.viewModel.addEffect("beat_flash")
        val layers = fixture.viewModel.composition.value.layers
        val movedId = layers.last().id

        fixture.viewModel.moveLayer(movedId, -1)

        assertEquals(movedId, fixture.viewModel.composition.value.layers[layers.size - 2].id)
    }

    @Test
    fun `duplicating a layer gives the copy a distinct id`() {
        val fixture = fixture()
        val id = fixture.viewModel.composition.value.layers.first().id

        fixture.viewModel.duplicateLayer(id)

        val ids = fixture.viewModel.composition.value.layers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `toggling expansion is exclusive`() {
        val fixture = fixture()
        val first = fixture.viewModel.composition.value.layers[0].id
        val second = fixture.viewModel.composition.value.layers[1].id

        fixture.viewModel.toggleExpanded(first)
        assertEquals(first, fixture.viewModel.expandedLayerId.value)

        fixture.viewModel.toggleExpanded(second)
        assertEquals(second, fixture.viewModel.expandedLayerId.value)

        fixture.viewModel.toggleExpanded(second)
        assertNull(fixture.viewModel.expandedLayerId.value)
    }

    // --- saving ---

    @Test
    fun `save writes the edit to the library and clears the dirty flag`() {
        val prefs = FakeSharedPreferences()
        val fixture = fixture(prefs)
        fixture.viewModel.addEffect("beat_flash")
        val expected = fixture.viewModel.composition.value

        fixture.viewModel.save()

        assertFalse(fixture.viewModel.hasUnsavedChanges.value)
        assertEquals(expected, fixture.library.byId(expected.id))
        // And it survives a fresh library over the same preferences.
        assertEquals(expected, CompositionLibrary(prefs).byId(expected.id))
    }

    @Test
    fun `revert throws away unsaved edits and restores the engine`() {
        val fixture = fixture()
        val original = fixture.viewModel.composition.value
        fixture.viewModel.addEffect("beat_flash")

        fixture.viewModel.revert()

        assertEquals(original, fixture.viewModel.composition.value)
        assertEquals(original, fixture.engine.composition)
        assertFalse(fixture.viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `reverting an unsaved brand-new stack falls back to the active one`() {
        // newComposition saves immediately, so there is always something to
        // revert to — the editor must never be left holding nothing.
        val fixture = fixture()
        fixture.viewModel.newComposition("Fresh")

        fixture.viewModel.revert()

        assertNotNull(fixture.viewModel.composition.value)
        assertEquals("Fresh", fixture.viewModel.composition.value.name)
    }

    @Test
    fun `newComposition creates, activates and opens a stack with a base layer`() {
        val fixture = fixture()

        fixture.viewModel.newComposition("Fresh")

        val created = fixture.viewModel.composition.value
        assertEquals("Fresh", created.name)
        assertEquals(1, created.layers.size)
        assertEquals("solid", (created.layers.single() as EffectLayer).effectId)
        assertEquals(created.id, fixture.library.activeId.value)
        assertEquals(created.id, fixture.engine.composition.id)
        assertFalse(fixture.viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `selecting another stack opens it and makes it active`() {
        val fixture = fixture()
        val target = CompositionLibrary.BUILT_INS[1]

        fixture.viewModel.selectComposition(target.id)

        assertEquals(target.id, fixture.viewModel.composition.value.id)
        assertEquals(target.id, fixture.engine.composition.id)
        assertEquals(target.id, fixture.library.activeId.value)
    }

    @Test
    fun `resetting an edited built-in restores the shipped version`() {
        val fixture = fixture()
        val builtInId = fixture.viewModel.composition.value.id
        assertTrue(fixture.viewModel.isBuiltIn(builtInId))
        fixture.viewModel.addEffect("beat_flash")
        fixture.viewModel.save()

        fixture.viewModel.deleteOrReset()

        assertEquals(
            CompositionLibrary.BUILT_INS.first(),
            fixture.viewModel.composition.value
        )
        assertEquals(CompositionLibrary.BUILT_INS.first(), fixture.engine.composition)
    }

    @Test
    fun `deleting a user stack reopens on whatever is active`() {
        val fixture = fixture()
        fixture.viewModel.newComposition("Temporary")
        val temporaryId = fixture.viewModel.composition.value.id

        fixture.viewModel.deleteOrReset()

        assertNull(fixture.library.byId(temporaryId))
        assertNotNull(fixture.viewModel.composition.value)
        assertEquals(fixture.viewModel.composition.value.id, fixture.engine.composition.id)
    }

    @Test
    fun `renaming marks the stack dirty and applies`() {
        val fixture = fixture()

        fixture.viewModel.setCompositionName("Renamed")

        assertEquals("Renamed", fixture.viewModel.composition.value.name)
        assertEquals("Renamed", fixture.engine.composition.name)
        assertTrue(fixture.viewModel.hasUnsavedChanges.value)
    }

    @Test
    fun `leaving the editor without saving hands the engine back the stored stack`() {
        val fixture = fixture()
        val stored = fixture.library.active()
        fixture.viewModel.setCompositionName("Never saved")
        assertEquals("Never saved", fixture.engine.composition.name)

        fixture.viewModel.restoreEngineToLibrary()

        // Otherwise the tail keeps rendering a tree the library does not have,
        // while reopening the editor loads the stored one — and hasUnsavedChanges
        // reads false on the fresh view model, so nothing says they diverged.
        assertEquals(stored, fixture.engine.composition)
    }

    @Test
    fun `leaving after saving changes nothing`() {
        val fixture = fixture()
        fixture.viewModel.setCompositionName("Kept")
        fixture.viewModel.save()

        fixture.viewModel.restoreEngineToLibrary()

        assertEquals("Kept", fixture.engine.composition.name)
    }
}
