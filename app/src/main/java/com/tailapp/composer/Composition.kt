package com.tailapp.composer

import java.util.UUID

/**
 * A complete, named lighting stack: the tree of layers and folders that
 * [CompositionRenderer] draws, and the unit the user saves, loads and edits.
 *
 * This replaces the old genre-selected `EffectProfile`. A profile was a fixed
 * set of knobs on one hard-coded renderer; a composition is an arbitrary tree of
 * independent effects, so a new look is a new *arrangement* rather than a new
 * render path — and every layer in it reads the same [ReactiveContext].
 *
 * @param layers bottom-to-top: `layers[0]` is drawn first and everything after
 *   blends over it, exactly like the firmware's layer indices.
 * @param brightness master multiplier applied to the finished frame, `0..1`.
 */
data class Composition(
    val id: String = newCompositionId(),
    val name: String,
    val layers: List<LayerNode> = emptyList(),
    val brightness: Float = 1f
) {
    /** True when nothing would be drawn. */
    val isEmpty: Boolean get() = layers.isEmpty()

    companion object {
        /** Renders black; the state before a composition has been chosen. */
        val EMPTY = Composition(id = "empty", name = "Empty", layers = emptyList())
    }
}

/** A fresh unique composition id. Tests pass explicit ids instead. */
fun newCompositionId(): String = UUID.randomUUID().toString()
