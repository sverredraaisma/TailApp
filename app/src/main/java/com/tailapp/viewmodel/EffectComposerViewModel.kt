package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import com.tailapp.composer.Composition
import com.tailapp.composer.CompositionLibrary
import com.tailapp.composer.EffectCategory
import com.tailapp.composer.EffectLayer
import com.tailapp.composer.GroupLayer
import com.tailapp.composer.LayerNode
import com.tailapp.composer.ReactiveEffects
import com.tailapp.composer.addNode
import com.tailapp.composer.duplicateNode
import com.tailapp.composer.findNode
import com.tailapp.composer.groupNode
import com.tailapp.composer.moveNode
import com.tailapp.composer.newCompositionId
import com.tailapp.composer.removeNode
import com.tailapp.composer.ungroup
import com.tailapp.composer.updateNode
import com.tailapp.effects.LightingEngine
import com.tailapp.led.PixelBuffer
import com.tailapp.lighting.PreviewLightingOutput
import com.tailapp.model.BlendMode
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backs the effect composer: the editable layer/folder tree, and the edits the
 * screen performs on it.
 *
 * **Every edit is applied to the running session immediately**, saved or not.
 * That is the whole ergonomics of the screen — a stack is built by watching the
 * tail (or the preview) react while dragging a slider, not by editing blind and
 * committing. [hasUnsavedChanges] tracks whether those live edits have been
 * written back to the [CompositionLibrary]; [revert] drops them by reloading the
 * stored version.
 *
 * Applying live is safe because the tree is immutable and
 * `LightingEngine.composition` hands it to the scene, which swaps it in at a
 * frame boundary on the render thread. Nothing here touches renderer state.
 */
class EffectComposerViewModel(
    private val engine: LightingEngine,
    private val library: CompositionLibrary,
    preview: PreviewLightingOutput,
    deviceRepository: DeviceRepository
) : ViewModel() {

    private val _composition = MutableStateFlow(library.active())

    /** The stack being edited. */
    val composition: StateFlow<Composition> = _composition.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    /** Which layer's parameter panel is open; only one at a time. */
    private val _expandedLayerId = MutableStateFlow<String?>(null)
    val expandedLayerId: StateFlow<String?> = _expandedLayerId.asStateFlow()

    /** The frame actually being rendered — the same one the tail is getting. */
    val frame: StateFlow<PixelBuffer?> = preview.frame

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    /** Every saved stack, for the picker. */
    val compositions: StateFlow<List<Composition>> = library.compositions

    /** The effect palette, grouped for the "add layer" sheet. */
    val effectsByCategory: Map<EffectCategory, List<com.tailapp.composer.EffectSpec>> =
        ReactiveEffects.byCategory()

    /** True when the open stack ships with the app (so it can be reset, not deleted). */
    fun isBuiltIn(id: String): Boolean = library.builtIns.any { it.id == id }

    // --- structure ---

    /**
     * Adds a layer running [effectId], at the top of the stack or inside a folder.
     *
     * The blend mode is chosen to be the one that is almost always wanted:
     * a modulator multiplies (that is the only way it does anything), the first
     * layer in an empty stack overwrites (it is the base), and anything else
     * adds. All three are one tap away from being changed.
     */
    fun addEffect(effectId: String, parentId: String? = null) {
        val spec = ReactiveEffects.spec(effectId) ?: return
        val isFirst = parentId == null && _composition.value.layers.isEmpty()
        val blend = when {
            spec.category == EffectCategory.MODULATOR -> BlendMode.MULTIPLY
            isFirst -> BlendMode.OVERWRITE
            else -> BlendMode.ADD
        }
        val layer = EffectLayer(
            name = spec.displayName,
            effectId = effectId,
            params = ReactiveEffects.defaultParams(effectId),
            blendMode = blend
        )
        edit { it.addNode(layer, parentId) }
        _expandedLayerId.value = layer.id
    }

    fun addFolder(parentId: String? = null) {
        edit { it.addNode(GroupLayer(name = "Folder"), parentId) }
    }

    fun deleteLayer(id: String) {
        if (_expandedLayerId.value == id) _expandedLayerId.value = null
        edit { it.removeNode(id) }
    }

    /** Moves a layer one step within its own folder; `-1` is down the stack. */
    fun moveLayer(id: String, delta: Int) = edit { it.moveNode(id, delta) }

    fun duplicateLayer(id: String) = edit { it.duplicateNode(id) }

    /** Wraps a layer in a new folder, so modulators can be added beside it. */
    fun groupLayer(id: String) {
        val name = (_composition.value.findNode(id)?.name ?: "Folder")
        edit { it.groupNode(id, name) }
    }

    fun ungroupFolder(id: String) = edit { it.ungroup(id) }

    // --- per-layer properties ---

    fun setParam(layerId: String, key: String, value: Float) = edit { composition ->
        composition.updateNode(layerId) { node ->
            if (node is EffectLayer) node.copy(params = node.params + (key to value)) else node
        }
    }

    fun setBlend(layerId: String, mode: BlendMode) =
        edit { it.updateNode(layerId) { node -> node.withBlend(mode) } }

    fun setOpacity(layerId: String, opacity: Float) =
        edit { it.updateNode(layerId) { node -> node.withOpacity(opacity.coerceIn(0f, 1f)) } }

    fun setEnabled(layerId: String, enabled: Boolean) =
        edit { it.updateNode(layerId) { node -> node.withEnabled(enabled) } }

    fun setName(layerId: String, name: String) =
        edit { it.updateNode(layerId) { node -> node.withName(name) } }

    fun setTransform(
        layerId: String,
        flipX: Boolean,
        flipY: Boolean,
        mirrorX: Boolean,
        mirrorY: Boolean
    ) = edit {
        it.updateNode(layerId) { node -> node.withTransform(flipX, flipY, mirrorX, mirrorY) }
    }

    /** Folds a folder shut in the tree view. Structural, so it counts as an edit. */
    fun toggleFolderCollapsed(id: String) = edit {
        it.updateNode(id) { node -> if (node is GroupLayer) node.copy(collapsed = !node.collapsed) else node }
    }

    fun toggleExpanded(id: String) {
        _expandedLayerId.value = if (_expandedLayerId.value == id) null else id
    }

    // --- composition-level ---

    fun setBrightness(brightness: Float) = edit { it.copy(brightness = brightness.coerceIn(0f, 1f)) }

    fun setCompositionName(name: String) = edit { it.copy(name = name) }

    /** Writes the working tree back to the library and makes it the active stack. */
    fun save() {
        val current = _composition.value
        library.save(current)
        library.setActive(current.id)
        _hasUnsavedChanges.value = false
    }

    /** Throws away unsaved edits, restoring the stored version. */
    fun revert() {
        val stored = library.byId(_composition.value.id) ?: library.active()
        _composition.value = stored
        engine.composition = stored
        _hasUnsavedChanges.value = false
        _expandedLayerId.value = null
    }

    /** Opens another stack for editing and makes it the active one. */
    fun selectComposition(id: String) {
        val next = library.byId(id) ?: return
        library.setActive(id)
        _composition.value = next
        engine.composition = next
        _hasUnsavedChanges.value = false
        _expandedLayerId.value = null
    }

    /** Creates and opens an empty stack with a single base layer to build on. */
    fun newComposition(name: String = "New stack") {
        val base = EffectLayer(
            name = "Solid Colour",
            effectId = "solid",
            params = ReactiveEffects.defaultParams("solid"),
            blendMode = BlendMode.OVERWRITE
        )
        val created = Composition(id = newCompositionId(), name = name, layers = listOf(base))
        library.save(created)
        library.setActive(created.id)
        _composition.value = created
        engine.composition = created
        _hasUnsavedChanges.value = false
        _expandedLayerId.value = null
    }

    /**
     * Deletes a user stack, or resets a built-in to the version that ships with
     * the app; either way the editor reopens on whatever the library now
     * considers active, so the screen is never left editing nothing.
     */
    fun deleteOrReset() {
        library.delete(_composition.value.id)
        val next = library.active()
        _composition.value = next
        engine.composition = next
        _hasUnsavedChanges.value = false
        _expandedLayerId.value = null
    }

    private fun edit(transform: (Composition) -> Composition) {
        val next = transform(_composition.value)
        _composition.value = next
        // Live, unconditionally: the tail should show the edit before the finger
        // leaves the slider.
        engine.composition = next
        _hasUnsavedChanges.value = true
    }
}

// Small `copy`-with-common-field helpers. `LayerNode` is a sealed interface, so
// there is no shared `copy`; these keep the call sites above readable.

private fun LayerNode.withBlend(mode: BlendMode): LayerNode = when (this) {
    is EffectLayer -> copy(blendMode = mode)
    is GroupLayer -> copy(blendMode = mode)
}

private fun LayerNode.withOpacity(opacity: Float): LayerNode = when (this) {
    is EffectLayer -> copy(opacity = opacity)
    is GroupLayer -> copy(opacity = opacity)
}

private fun LayerNode.withEnabled(enabled: Boolean): LayerNode = when (this) {
    is EffectLayer -> copy(enabled = enabled)
    is GroupLayer -> copy(enabled = enabled)
}

private fun LayerNode.withName(name: String): LayerNode = when (this) {
    is EffectLayer -> copy(name = name)
    is GroupLayer -> copy(name = name)
}

private fun LayerNode.withTransform(
    flipX: Boolean,
    flipY: Boolean,
    mirrorX: Boolean,
    mirrorY: Boolean
): LayerNode = when (this) {
    is EffectLayer -> copy(flipX = flipX, flipY = flipY, mirrorX = mirrorX, mirrorY = mirrorY)
    is GroupLayer -> copy(flipX = flipX, flipY = flipY, mirrorX = mirrorX, mirrorY = mirrorY)
}
