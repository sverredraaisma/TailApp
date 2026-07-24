package com.tailapp.composer

import com.tailapp.model.BlendMode
import java.util.UUID

/**
 * One node in a composition's layer tree — either a single effect
 * ([EffectLayer]) or a **folder** of nested layers ([GroupLayer]).
 *
 * Nodes are immutable values. The editor rebuilds the tree on every change and
 * hands the new snapshot to the engine, which swaps it in whole at a frame
 * boundary; that is what lets the UI thread edit freely while the render loop
 * reads, with no lock and no torn tree. Running effect state survives the swap
 * because [CompositionRenderer] keys its live instances on [id] — see
 * `CompositionRenderer.setComposition`.
 *
 * The composite fields below mirror the firmware's per-layer controls
 * (`blend_mode`, `enabled`, and the four transform flags in `LayerConfig`), so a
 * stack behaves the way someone who has built a firmware stack expects.
 * [opacity] is the one addition: app-side layers are not wire-constrained, and a
 * per-layer mix is what makes deep folder stacks tractable.
 */
sealed interface LayerNode {
    /** Stable identity, used to preserve running effect state across edits. */
    val id: String

    /** Label shown in the editor. */
    val name: String

    /** How this node's rendered output combines with everything beneath it. */
    val blendMode: BlendMode

    /** `0..1` mix between the layers below and the blended result. */
    val opacity: Float

    /** A disabled node contributes nothing — not even black. */
    val enabled: Boolean

    val flipX: Boolean
    val flipY: Boolean
    val mirrorX: Boolean
    val mirrorY: Boolean
}

/**
 * A leaf layer: one effect from [ReactiveEffects], with its parameter values.
 *
 * @param effectId the [EffectSpec.id] this layer renders. An id no build
 *   recognises renders nothing rather than failing the whole composition.
 * @param params overrides for the effect's schema defaults; unknown keys are
 *   ignored when applied, so an old save cannot corrupt a newer effect.
 */
data class EffectLayer(
    override val id: String = newLayerId(),
    override val name: String,
    val effectId: String,
    val params: Map<String, Float> = emptyMap(),
    override val blendMode: BlendMode = BlendMode.ADD,
    override val opacity: Float = 1f,
    override val enabled: Boolean = true,
    override val flipX: Boolean = false,
    override val flipY: Boolean = false,
    override val mirrorX: Boolean = false,
    override val mirrorY: Boolean = false
) : LayerNode

/**
 * A folder: its [children] are composited among themselves into the folder's own
 * buffer, and that single result is then blended into the parent with the
 * folder's [blendMode] and [opacity].
 *
 * That two-step is the whole point of folders — it makes a modulator apply to a
 * *group* rather than to one layer. A folder holding a rainbow and a spectrum
 * meter, blended into the stack at 40% opacity, dims both together; a
 * [EffectCategory.MODULATOR] layer multiplied over a folder gates everything
 * inside it at once. Nesting is unbounded.
 */
data class GroupLayer(
    override val id: String = newLayerId(),
    override val name: String,
    val children: List<LayerNode> = emptyList(),
    override val blendMode: BlendMode = BlendMode.ADD,
    override val opacity: Float = 1f,
    override val enabled: Boolean = true,
    override val flipX: Boolean = false,
    override val flipY: Boolean = false,
    override val mirrorX: Boolean = false,
    override val mirrorY: Boolean = false,
    /** Editor-only: whether the folder is folded shut in the tree view. */
    val collapsed: Boolean = false
) : LayerNode

/** A fresh unique layer id. Tests pass explicit ids instead. */
fun newLayerId(): String = UUID.randomUUID().toString()
