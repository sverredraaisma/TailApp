package com.tailapp.composer

/**
 * The declared schema of one tunable effect parameter.
 *
 * A [ReactiveEffect] publishes a list of these; the composer editor renders the
 * right control for each ([Scalar] → slider, [Color] → colour picker, [Choice] →
 * dropdown, [Toggle] → switch) with no per-effect UI code, and persistence is
 * just the parameter map.
 *
 * Every value is stored as a single `Float` — colours as their packed
 * `0xRRGGBB` int (exact in a Float up to `0xFFFFFF`, well inside its 24-bit
 * mantissa), choices as the option index, toggles as `0`/`1` — so the storage
 * and the saved format stay uniform, exactly like the firmware's all-`float`
 * 8-slot parameter block, only keyed by name and typed by this schema.
 *
 * The subclasses are named `Scalar`/`Choice`/`Toggle` rather than the obvious
 * `Float`/`Enum`/`Bool` on purpose: a nested class called `Float` shadows
 * `kotlin.Float` throughout the sealed class's own body, which makes the
 * `default: Float` parameter it is declared with resolve to the wrong type.
 *
 * @property key stable identifier, also the persisted map key.
 * @property label human-readable name shown in the editor.
 * @property default value applied when a layer does not override it.
 */
sealed class EffectParam(val key: String, val label: String, val default: Float) {

    /** A continuous (or stepped) scalar in `[min, max]`. */
    class Scalar(
        key: String,
        label: String,
        default: Float,
        val min: Float,
        val max: Float,
        /** Snap increment, or `0` for continuous. */
        val step: Float = 0f,
        /** Optional suffix shown in the editor, e.g. `"Hz"`. */
        val unit: String = ""
    ) : EffectParam(key, label, default) {
        init {
            require(max > min) { "param $key: max must exceed min" }
            require(default in min..max) { "param $key: default $default outside [$min, $max]" }
        }
    }

    /** A packed `0xRRGGBB` colour. */
    class Color(
        key: String,
        label: String,
        defaultColor: Int
    ) : EffectParam(key, label, (defaultColor and 0xFFFFFF).toFloat())

    /** One of [options], stored as its index. */
    class Choice(
        key: String,
        label: String,
        val options: List<String>,
        defaultIndex: Int = 0
    ) : EffectParam(key, label, defaultIndex.toFloat()) {
        init {
            require(options.isNotEmpty()) { "param $key: choice needs options" }
            require(defaultIndex in options.indices) { "param $key: default index out of range" }
        }
    }

    /** An on/off flag, stored as `0`/`1`. */
    class Toggle(
        key: String,
        label: String,
        defaultOn: Boolean
    ) : EffectParam(key, label, if (defaultOn) 1f else 0f)
}

/**
 * Live parameter storage for one effect instance.
 *
 * Backed by a single `String → Float` map keyed on the effect's schema, so an
 * unknown key is ignored (a param removed in a later build cannot corrupt an
 * effect restored from an older save) and a missing key falls back to the schema
 * default. Typed accessors interpret the stored float according to the declared
 * [EffectParam] kind. Effects read these once per frame into locals rather than
 * per pixel.
 */
class ParamBag(schema: List<EffectParam>) {
    private val byKey: Map<String, EffectParam> = schema.associateBy { it.key }
    private val values: HashMap<String, Float> =
        HashMap<String, Float>(schema.size * 2).apply {
            schema.forEach { put(it.key, it.default) }
        }

    /** Overwrites any keys present in [map] that this bag knows about. */
    fun setAll(map: Map<String, Float>) {
        for ((k, v) in map) if (byKey.containsKey(k)) values[k] = v
    }

    /** Sets one known key; unknown keys are ignored. */
    fun set(key: String, value: Float) {
        if (byKey.containsKey(key)) values[key] = value
    }

    /** Raw stored value, or the schema default, or `0`. */
    fun raw(key: String): Float = values[key] ?: byKey[key]?.default ?: 0f

    fun float(key: String): Float = raw(key)

    /** Packed `0xRRGGBB`. */
    fun color(key: String): Int = raw(key).toInt() and 0xFFFFFF

    fun enumIndex(key: String): Int = raw(key).toInt()

    fun bool(key: String): Boolean = raw(key) != 0f

    /** An immutable snapshot for persistence. */
    fun toMap(): Map<String, Float> = HashMap(values)
}
