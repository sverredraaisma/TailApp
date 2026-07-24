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
        layers = Json.arr(root["layers"]).orEmpty().mapNotNull(::decodeNode),
        brightness = Json.float(root["brightness"], 1f).coerceIn(0f, 1f)
    )

    private fun decodeNode(raw: Any?): LayerNode? {
        val map = Json.obj(raw) ?: return null

        val id = Json.str(map["id"]) ?: newLayerId()
        val blend = blendOf(Json.str(map["blend"]))
        val opacity = Json.float(map["opacity"], 1f).coerceIn(0f, 1f)
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

    private fun decodeParams(raw: Any?): Map<String, Float> {
        val map = Json.obj(raw) ?: return emptyMap()
        val params = HashMap<String, Float>(map.size * 2)
        for ((key, value) in map) (value as? Number)?.let { params[key] = it.toFloat() }
        return params
    }

    private fun blendOf(name: String?): BlendMode =
        BlendMode.entries.firstOrNull { it.name == name } ?: BlendMode.ADD

    private const val TYPE_EFFECT = "effect"
    private const val TYPE_GROUP = "group"
}
