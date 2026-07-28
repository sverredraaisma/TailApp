package com.tailapp.composer

import com.tailapp.model.BlendMode

/**
 * Reads and writes [Composition]s as JSON, for saving user stacks.
 *
 * **Forgiving on the way in, strict on the way out.** A saved composition
 * outlives the build that wrote it: effects gain parameters, layers gain fields,
 * and an effect may be removed entirely. So every field is optional on read and
 * falls back to a sane default, unknown parameter keys are dropped by
 * [ParamBag.setAll] rather than rejected, and a layer naming an effect this
 * build does not have still loads — it simply renders nothing (see
 * [CompositionRenderer]). Only a document that is not JSON at all, or has no
 * object at its root, fails; [fromJson] answers `null` and the caller falls back
 * to a built-in.
 *
 * Blend modes are stored by **name**, not by their wire id, because the ids
 * belong to the BLE protocol and are free to change there without invalidating
 * everything a user has saved on their phone.
 */
object CompositionSerializer {

    /** Bumped only for a change old readers could not cope with. */
    const val VERSION = 1

    fun toJson(composition: Composition): String = Json.write(encode(composition))

    /** Parses one composition, or null when the document is unusable. */
    fun fromJson(text: String): Composition? = runCatching {
        decode(Json.obj(Json.parse(text)) ?: return null)
    }.getOrNull()

    fun listToJson(compositions: List<Composition>): String =
        Json.write(mapOf("version" to VERSION, "compositions" to compositions.map(::encode)))

    /** Parses a saved library; an unusable document yields an empty list. */
    fun listFromJson(text: String): List<Composition> = runCatching {
        val root = Json.obj(Json.parse(text)) ?: return emptyList()
        Json.arr(root["compositions"]).orEmpty().mapNotNull { raw ->
            Json.obj(raw)?.let(::decode)
        }
    }.getOrDefault(emptyList())

    // --- encoding ---

    private fun encode(composition: Composition): Map<String, Any?> = linkedMapOf(
        "version" to VERSION,
        "id" to composition.id,
        "name" to composition.name,
        "brightness" to composition.brightness,
        "layers" to composition.layers.map(::encodeNode)
    )

    private fun encodeNode(node: LayerNode): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["type"] = if (node is GroupLayer) TYPE_GROUP else TYPE_EFFECT
        map["id"] = node.id
        map["name"] = node.name

        when (node) {
            is EffectLayer -> {
                map["effect"] = node.effectId
                map["params"] = node.params
            }

            is GroupLayer -> {
                map["collapsed"] = node.collapsed
                map["children"] = node.children.map(::encodeNode)
            }
        }

        map["blend"] = node.blendMode.name
        map["opacity"] = node.opacity
        map["enabled"] = node.enabled
        map["flipX"] = node.flipX
        map["flipY"] = node.flipY
        map["mirrorX"] = node.mirrorX
        map["mirrorY"] = node.mirrorY
        return map
    }

    // --- decoding ---

    private fun decode(root: Map<String, Any?>): Composition = Composition(
        id = Json.str(root["id"]) ?: newCompositionId(),
        name = Json.str(root["name"]) ?: "Untitled",
        layers = withUniqueIds(
            Json.arr(root["layers"]).orEmpty().mapNotNull(::decodeNode),
            HashSet()
        ),
        brightness = unitInterval(root["brightness"])
    )

    /**
     * A `0..1` field from the document, defaulting to fully on.
     *
     * `coerceIn` cannot clamp a `NaN` — every comparison against it is false, so
     * it passes straight through — and a `NaN` brightness would multiply the
     * whole frame to nothing while looking, in the editor, like full brightness.
     */
    private fun unitInterval(raw: Any?): Float {
        val value = Json.float(raw, 1f)
        return if (value.isFinite()) value.coerceIn(0f, 1f) else 1f
    }

    /**
     * Guarantees the tree-wide uniqueness [CompositionRenderer] depends on.
     *
     * The renderer keys its live effect instances by [LayerNode.id], so two nodes
     * sharing one id collapse onto a single [ReactiveEffect]: its parameters are
     * applied twice (the last one wins for *both* layers) and it is rendered
     * twice per frame, so anything integrating `dtSeconds` advances at double
     * rate. In-app editing cannot produce that — [CompositionEdits] mints a fresh
     * UUID for every node it creates — but an imported document carries whatever
     * ids its author wrote, including none at all and including duplicates.
     *
     * A collision keeps the *first* occurrence's id and re-mints the later one,
     * so the common case (a hand-edited file with one copy-pasted layer) leaves
     * the original layer's identity, and therefore its running state, alone.
     */
    private fun withUniqueIds(nodes: List<LayerNode>, seen: MutableSet<String>): List<LayerNode> =
        nodes.map { node ->
            val id = if (node.id.isNotBlank() && seen.add(node.id)) {
                node.id
            } else {
                generateSequence(::newLayerId).first(seen::add)
            }
            when (node) {
                is EffectLayer -> if (id == node.id) node else node.copy(id = id)
                is GroupLayer -> node.copy(id = id, children = withUniqueIds(node.children, seen))
            }
        }

    private fun decodeNode(raw: Any?): LayerNode? {
        val map = Json.obj(raw) ?: return null

        val id = Json.str(map["id"]) ?: newLayerId()
        val blend = blendOf(Json.str(map["blend"]))
        val opacity = unitInterval(map["opacity"])
        val enabled = Json.bool(map["enabled"], true)
        val flipX = Json.bool(map["flipX"], false)
        val flipY = Json.bool(map["flipY"], false)
        val mirrorX = Json.bool(map["mirrorX"], false)
        val mirrorY = Json.bool(map["mirrorY"], false)

        return when (Json.str(map["type"])) {
            TYPE_GROUP -> GroupLayer(
                id = id,
                name = Json.str(map["name"]) ?: "Folder",
                children = Json.arr(map["children"]).orEmpty().mapNotNull(::decodeNode),
                blendMode = blend,
                opacity = opacity,
                enabled = enabled,
                flipX = flipX,
                flipY = flipY,
                mirrorX = mirrorX,
                mirrorY = mirrorY,
                collapsed = Json.bool(map["collapsed"], false)
            )

            TYPE_EFFECT -> {
                // The one genuinely required field: a layer with no effect id is
                // not an unknown effect, it is a corrupt record.
                val effectId = Json.str(map["effect"]) ?: return null
                EffectLayer(
                    id = id,
                    name = Json.str(map["name"])
                        ?: ReactiveEffects.spec(effectId)?.displayName
                        ?: effectId,
                    effectId = effectId,
                    params = decodeParams(map["params"]),
                    blendMode = blend,
                    opacity = opacity,
                    enabled = enabled,
                    flipX = flipX,
                    flipY = flipY,
                    mirrorX = mirrorX,
                    mirrorY = mirrorY
                )
            }

            else -> null
        }
    }

    /**
     * Parameter values are carried through as written — the range check belongs
     * to the effect's own schema, and [ParamBag.setAll] applies it when the layer
     * is instantiated, so a key this build does not recognise keeps its value for
     * a build that does.
     *
     * Non-finite values are the exception: they are dropped rather than stored,
     * because there is no schema clamp that can rescue a `NaN` and nothing may
     * write one back out.
     */
    private fun decodeParams(raw: Any?): Map<String, Float> {
        val map = Json.obj(raw) ?: return emptyMap()
        val params = HashMap<String, Float>(map.size * 2)
        for ((key, value) in map) {
            val number = (value as? Number)?.toFloat() ?: continue
            if (number.isFinite()) params[key] = number
        }
        return params
    }

    private fun blendOf(name: String?): BlendMode =
        BlendMode.entries.firstOrNull { it.name == name } ?: BlendMode.ADD

    private const val TYPE_EFFECT = "effect"
    private const val TYPE_GROUP = "group"
}
