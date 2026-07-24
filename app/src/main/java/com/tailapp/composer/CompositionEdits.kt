package com.tailapp.composer

import com.tailapp.model.BlendMode

/**
 * Tree operations the composer editor is built from.
 *
 * Every one is a pure function returning a **new** [Composition]: the editor
 * holds one immutable tree, replaces it wholesale on each edit, and hands the
 * result to [CompositionScene.setComposition]. That is what makes editing safe
 * while the render loop is running — the loop only ever sees a finished tree,
 * never a half-applied edit — and it makes undo a matter of keeping the previous
 * value.
 *
 * Recursion is by [LayerNode.id], so an operation reaches a layer at any folder
 * depth without the caller tracking a path.
 */

/** The node with [id] anywhere in the tree, or null. */
fun Composition.findNode(id: String): LayerNode? = layers.findNode(id)

private fun List<LayerNode>.findNode(id: String): LayerNode? {
    for (node in this) {
        if (node.id == id) return node
        if (node is GroupLayer) node.children.findNode(id)?.let { return it }
    }
    return null
}

/** The id of the folder containing [id], or null when it sits at the top level. */
fun Composition.parentOf(id: String): String? = layers.parentOf(id, null)

private fun List<LayerNode>.parentOf(id: String, current: String?): String? {
    for (node in this) {
        if (node.id == id) return current
        if (node is GroupLayer) node.children.parentOf(id, node.id)?.let { return it }
    }
    return null
}

/** Replaces the node with [id] by [transform]'s result. */
fun Composition.updateNode(id: String, transform: (LayerNode) -> LayerNode): Composition =
    copy(layers = layers.updateNode(id, transform))

private fun List<LayerNode>.updateNode(id: String, transform: (LayerNode) -> LayerNode): List<LayerNode> =
    map { node ->
        when {
            node.id == id -> transform(node)
            node is GroupLayer -> node.copy(children = node.children.updateNode(id, transform))
            else -> node
        }
    }

/** Removes the node with [id], and everything inside it if it is a folder. */
fun Composition.removeNode(id: String): Composition = copy(layers = layers.removeNode(id))

private fun List<LayerNode>.removeNode(id: String): List<LayerNode> =
    mapNotNull { node ->
        when {
            node.id == id -> null
            node is GroupLayer -> node.copy(children = node.children.removeNode(id))
            else -> node
        }
    }

/**
 * Appends [node] to the top level, or inside the folder [parentId].
 *
 * Appending puts it on *top* of the stack, which is what "add a layer" means
 * visually — later layers blend over earlier ones.
 */
fun Composition.addNode(node: LayerNode, parentId: String? = null): Composition =
    if (parentId == null) copy(layers = layers + node)
    else copy(layers = layers.addInto(parentId, node))

private fun List<LayerNode>.addInto(parentId: String, node: LayerNode): List<LayerNode> =
    map {
        when {
            it.id == parentId && it is GroupLayer -> it.copy(children = it.children + node)
            it is GroupLayer -> it.copy(children = it.children.addInto(parentId, node))
            else -> it
        }
    }

/**
 * Moves [id] by [delta] positions **within its own sibling list**.
 *
 * Deliberately does not hop folders: a move that silently reparented a layer
 * would change which folder's modulators apply to it, which is a much bigger
 * change than "one step up" looks like. Use group/ungroup to change nesting.
 */
fun Composition.moveNode(id: String, delta: Int): Composition =
    copy(layers = layers.moveNode(id, delta))

private fun List<LayerNode>.moveNode(id: String, delta: Int): List<LayerNode> {
    val index = indexOfFirst { it.id == id }
    if (index >= 0) {
        val target = index + delta
        if (target < 0 || target >= size) return this
        return toMutableList().apply { add(target, removeAt(index)) }
    }
    return map { if (it is GroupLayer) it.copy(children = it.children.moveNode(id, delta)) else it }
}

/** Inserts a fresh-id copy of [id] directly above the original. */
fun Composition.duplicateNode(id: String): Composition =
    copy(layers = layers.duplicateNode(id))

private fun List<LayerNode>.duplicateNode(id: String): List<LayerNode> {
    val index = indexOfFirst { it.id == id }
    if (index >= 0) {
        return toMutableList().apply { add(index + 1, this[index].withNewIds()) }
    }
    return map { if (it is GroupLayer) it.copy(children = it.children.duplicateNode(id)) else it }
}

/**
 * A deep copy with fresh ids throughout.
 *
 * Ids must be unique across the whole tree: [CompositionRenderer] keys its live
 * effect instances on them, so two layers sharing an id would share one
 * instance — and one of them would silently render the other's animation state.
 */
fun LayerNode.withNewIds(): LayerNode = when (this) {
    is EffectLayer -> copy(id = newLayerId())
    is GroupLayer -> copy(id = newLayerId(), children = children.map { it.withNewIds() })
}

/**
 * Wraps [id] in a new folder in its place, so a modulator can then be dropped in
 * beside it.
 *
 * The folder inherits the layer's blend mode and opacity and the layer is reset
 * to `ADD` at full opacity inside it. Without that the mix would be applied
 * twice — once by the layer, once by its new folder — and grouping a layer would
 * visibly darken it, which is not what "put this in a folder" should ever do.
 */
fun Composition.groupNode(id: String, folderName: String = "Folder"): Composition =
    copy(layers = layers.groupNode(id, folderName))

private fun List<LayerNode>.groupNode(id: String, folderName: String): List<LayerNode> =
    map { node ->
        when {
            node.id == id -> GroupLayer(
                name = folderName,
                children = listOf(node.withBlend(BlendMode.ADD, 1f)),
                blendMode = node.blendMode,
                opacity = node.opacity,
                enabled = node.enabled
            )

            node is GroupLayer -> node.copy(children = node.children.groupNode(id, folderName))
            else -> node
        }
    }

/** Replaces the folder [id] with its children, keeping their order. */
fun Composition.ungroup(id: String): Composition = copy(layers = layers.ungroup(id))

private fun List<LayerNode>.ungroup(id: String): List<LayerNode> =
    flatMap { node ->
        when {
            node.id == id && node is GroupLayer -> node.children
            node is GroupLayer -> listOf(node.copy(children = node.children.ungroup(id)))
            else -> listOf(node)
        }
    }

private fun LayerNode.withBlend(blend: BlendMode, opacity: Float): LayerNode =
    when (this) {
        is EffectLayer -> copy(blendMode = blend, opacity = opacity)
        is GroupLayer -> copy(blendMode = blend, opacity = opacity)
    }
