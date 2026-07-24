package com.tailapp.composer

import android.content.SharedPreferences
import com.tailapp.model.BlendMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The compositions available to the user: a set of built-in stacks that ship
 * with the app, plus whatever they have saved.
 *
 * A user entry **shadows** a built-in with the same id, which is what makes the
 * built-ins editable without being destructible: saving over "Pulse" stores a
 * user copy under the same id and [byId] prefers it, while [resetToBuiltIn]
 * deletes that copy and the original reappears. Nothing the user does can leave
 * them with no compositions at all.
 *
 * [prefs] is nullable for the same reason `BeatLightViewModel`'s is: it needs a
 * real `Context`, which a JVM unit test has none of. With null the library is
 * simply in-memory, and every built-in still works — which is also exactly what
 * a fresh install looks like before anything has been saved.
 */
class CompositionLibrary(private val prefs: SharedPreferences? = null) {

    private val userCompositions: MutableList<Composition> =
        prefs?.getString(KEY_USER, null)
            ?.let { CompositionSerializer.listFromJson(it).toMutableList() }
            ?: mutableListOf()

    private val _compositions = MutableStateFlow(computeAll())

    /**
     * Everything selectable, built-ins first (each replaced by the user's edit
     * when one exists), then compositions they created outright. Re-emits on
     * every save, so the picker and the editor stay in step without either
     * having to poll the other.
     */
    val compositions: StateFlow<List<Composition>> = _compositions.asStateFlow()

    private val _activeId = MutableStateFlow(
        // A saved id whose composition has since been deleted must not strand the
        // session on nothing; fall back to the first built-in.
        prefs?.getString(KEY_ACTIVE, null)?.takeIf { byId(it) != null } ?: BUILT_INS.first().id
    )

    /** Which composition the lighting session renders. */
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    /** The stacks that ship with the app, in menu order. */
    val builtIns: List<Composition> get() = BUILT_INS

    fun byId(id: String): Composition? =
        userCompositions.firstOrNull { it.id == id } ?: BUILT_INS.firstOrNull { it.id == id }

    /** The active composition, falling back to the first built-in. */
    fun active(): Composition = byId(_activeId.value) ?: BUILT_INS.first()

    fun setActive(id: String) {
        if (byId(id) == null) return
        _activeId.value = id
        prefs?.edit()?.putString(KEY_ACTIVE, id)?.apply()
    }

    /** True when [id] names a built-in the user has edited. */
    fun isModifiedBuiltIn(id: String): Boolean =
        BUILT_INS.any { it.id == id } && userCompositions.any { it.id == id }

    /** Inserts or replaces [composition] in the user's set and persists. */
    fun save(composition: Composition) {
        val index = userCompositions.indexOfFirst { it.id == composition.id }
        if (index >= 0) userCompositions[index] = composition else userCompositions.add(composition)
        persist()
    }

    /**
     * Removes a user composition. A built-in cannot be deleted — asking to
     * delete one resets it instead, which is the only sane reading of "delete"
     * for something that ships with the app.
     */
    fun delete(id: String) {
        if (BUILT_INS.any { it.id == id }) {
            resetToBuiltIn(id)
            return
        }
        if (userCompositions.removeAll { it.id == id }) persist()
        if (_activeId.value == id) setActive(BUILT_INS.first().id)
    }

    /** Drops the user's edit of a built-in, restoring the shipped version. */
    fun resetToBuiltIn(id: String) {
        if (BUILT_INS.none { it.id == id }) return
        if (userCompositions.removeAll { it.id == id }) persist()
    }

    private fun computeAll(): List<Composition> {
        val builtInIds = BUILT_INS.mapTo(HashSet()) { it.id }
        return BUILT_INS.map { built -> userCompositions.firstOrNull { it.id == built.id } ?: built } +
            userCompositions.filter { it.id !in builtInIds }
    }

    private fun persist() {
        _compositions.value = computeAll()
        prefs?.edit()?.putString(KEY_USER, CompositionSerializer.listToJson(userCompositions))?.apply()
    }

    companion object {
        internal const val KEY_USER = "composer_user_compositions"
        internal const val KEY_ACTIVE = "composer_active_id"

        private fun rgb(value: Int): Float = (value and 0xFFFFFF).toFloat()

        private fun effect(
            id: String,
            effectId: String,
            blend: BlendMode = BlendMode.ADD,
            opacity: Float = 1f,
            name: String? = null,
            params: Map<String, Float> = emptyMap()
        ) = EffectLayer(
            id = id,
            name = name ?: ReactiveEffects.spec(effectId)?.displayName ?: effectId,
            effectId = effectId,
            params = params,
            blendMode = blend,
            opacity = opacity
        )

        /**
         * The shipped stacks.
         *
         * Chosen to be a tour of the system rather than six variations on a
         * flash: between them they use every blend mode that matters, both
         * modulator patterns (mask a folder, dim a folder), nesting, per-layer
         * opacity, and all four effect categories. Ids are fixed strings, not
         * generated, so a saved edit still matches its built-in after an update.
         */
        val BUILT_INS: List<Composition> = listOf(

            // The simplest thing that is unmistakably beat-reactive.
            Composition(
                id = "builtin.pulse",
                name = "Pulse",
                layers = listOf(
                    effect(
                        "pulse.base", "solid", BlendMode.OVERWRITE,
                        params = mapOf("color" to rgb(0x001830), "brightness" to 1f)
                    ),
                    effect(
                        "pulse.flash", "beat_flash",
                        params = mapOf(
                            "color" to rgb(0xFFFFFF),
                            "decay" to 0.28f,
                            "downbeatBoost" to 1.4f
                        )
                    )
                )
            ),

            // Motion along the tail rather than flashes; long drop burst.
            Composition(
                id = "builtin.trance",
                name = "Trance Drift",
                layers = listOf(
                    effect(
                        "trance.base", "gradient", BlendMode.OVERWRITE,
                        params = mapOf(
                            "colorA" to rgb(0x1040FF),
                            "colorB" to rgb(0x8020FF),
                            "speed" to 0.08f,
                            "repeat" to 1f,
                            "mirrored" to 1f,
                            "brightness" to 0.55f
                        )
                    ),
                    effect(
                        "trance.chase", "ring_chase",
                        params = mapOf(
                            "color" to rgb(0x00D0FF),
                            "step" to 0.2f,
                            "width" to 0.12f,
                            "hold" to 0.25f,
                            "decay" to 0.4f
                        )
                    ),
                    effect(
                        "trance.ripple", "beat_ripple", opacity = 0.7f,
                        params = mapOf(
                            "color" to rgb(0xFFFFFF),
                            "speed" to 1.4f,
                            "width" to 0.15f,
                            "decay" to 0.8f
                        )
                    ),
                    effect(
                        "trance.drop", "drop_flash",
                        params = mapOf(
                            "color" to rgb(0xFFFFFF),
                            "shape" to 1f,
                            "window" to 2.5f,
                            "width" to 0.3f
                        )
                    )
                )
            ),

            // The FFT on show: a folder whose analyser is gated by a beat mask.
            Composition(
                id = "builtin.spectrum",
                name = "Spectrum Lab",
                layers = listOf(
                    effect(
                        "spectrum.base", "solid", BlendMode.OVERWRITE,
                        params = mapOf("color" to rgb(0x050510), "brightness" to 1f)
                    ),
                    GroupLayer(
                        id = "spectrum.analyser",
                        name = "Analyser",
                        blendMode = BlendMode.ADD,
                        children = listOf(
                            effect(
                                "spectrum.bars", "spectrum_bars", BlendMode.OVERWRITE,
                                params = mapOf(
                                    "bars" to 12f,
                                    "barAxis" to 0f,
                                    "colorLow" to rgb(0xFF2000),
                                    "colorHigh" to rgb(0x00A0FF),
                                    "gain" to 1.3f,
                                    "fadeRate" to 1.8f
                                )
                            ),
                            // Multiplied over the bars: the analyser breathes with
                            // the beat instead of running flat.
                            effect(
                                "spectrum.mask", "beat_mask", BlendMode.MULTIPLY,
                                params = mapOf("floor" to 0.45f, "decay" to 0.2f)
                            )
                        )
                    ),
                    effect(
                        "spectrum.drop", "drop_flash", opacity = 0.8f,
                        params = mapOf("color" to rgb(0xFFFFFF), "shape" to 0f, "window" to 1.5f)
                    )
                )
            ),

            // Hard kicks, no idle glow, and the whole stack ducked by section.
            Composition(
                id = "builtin.hardstyle",
                name = "Hardstyle",
                layers = listOf(
                    GroupLayer(
                        id = "hard.core",
                        name = "Kick",
                        blendMode = BlendMode.ADD,
                        children = listOf(
                            effect(
                                "hard.flash", "beat_flash", BlendMode.OVERWRITE,
                                params = mapOf(
                                    "color" to rgb(0xFF2000),
                                    "decay" to 0.10f,
                                    "downbeatBoost" to 1.6f
                                )
                            ),
                            effect(
                                "hard.bass", "bass_pulse",
                                params = mapOf(
                                    "source" to 1f,
                                    "color" to rgb(0xFF8000),
                                    "gain" to 1.4f,
                                    "threshold" to 0.2f,
                                    "fall" to 4f
                                )
                            )
                        )
                    ),
                    // Sits above the folder, so it modulates everything below it:
                    // strobes through a build-up, dims through a breakdown.
                    effect(
                        "hard.section", "section_dimmer", BlendMode.MULTIPLY,
                        params = mapOf(
                            "buildupStartHz" to 4f,
                            "buildupEndHz" to 18f,
                            "breakdown" to 0.25f
                        )
                    ),
                    effect(
                        "hard.drop", "drop_flash",
                        params = mapOf("color" to rgb(0xFFFFFF), "shape" to 0f, "window" to 1.8f)
                    )
                )
            ),

            // Fire, with sparks that only appear when the music is actually loud.
            Composition(
                id = "builtin.ember",
                name = "Ember",
                layers = listOf(
                    effect(
                        "ember.fire", "fire", BlendMode.OVERWRITE,
                        params = mapOf(
                            "colorLow" to rgb(0xFF2000),
                            "colorHigh" to rgb(0xFFD040),
                            "speed" to 1.2f,
                            "intensity" to 1.4f,
                            "levelBoost" to 0.6f
                        )
                    ),
                    GroupLayer(
                        id = "ember.sparks",
                        name = "Sparks",
                        blendMode = BlendMode.ADD,
                        opacity = 0.8f,
                        children = listOf(
                            effect(
                                "ember.sparkle", "sparkle", BlendMode.OVERWRITE,
                                params = mapOf(
                                    "trigger" to 1f,
                                    "color" to rgb(0xFFD060),
                                    "density" to 0.12f,
                                    "decay" to 0.35f,
                                    "rate" to 10f
                                )
                            ),
                            effect(
                                "ember.dimmer", "volume_dimmer", BlendMode.MULTIPLY,
                                params = mapOf("source" to 0f, "floor" to 0.25f, "gain" to 1.2f)
                            )
                        )
                    ),
                    effect(
                        "ember.flash", "beat_flash", opacity = 0.35f,
                        params = mapOf("color" to rgb(0xFF6000), "decay" to 0.15f)
                    )
                )
            ),

            // Quiet material: slow, dim, nothing that snaps.
            Composition(
                id = "builtin.chill",
                name = "Chill",
                brightness = 0.75f,
                layers = listOf(
                    effect(
                        "chill.plasma", "plasma", BlendMode.OVERWRITE,
                        params = mapOf(
                            "colorA" to rgb(0x2060FF),
                            "colorB" to rgb(0x00C0A0),
                            "scale" to 1.2f,
                            "speed" to 0.12f,
                            "levelBoost" to 0.3f
                        )
                    ),
                    effect(
                        "chill.dimmer", "volume_dimmer", BlendMode.MULTIPLY,
                        params = mapOf("source" to 0f, "floor" to 0.5f)
                    ),
                    effect(
                        "chill.sparkle", "sparkle", opacity = 0.6f,
                        params = mapOf(
                            "trigger" to 0f,
                            "color" to rgb(0xFFFFFF),
                            "density" to 0.08f,
                            "decay" to 0.9f
                        )
                    )
                )
            ),

            // The tail reacting to itself rather than to sound: it glows as it
            // swings, shows which way is down, and ripples where you touch it.
            // Works in silence, which none of the others do.
            Composition(
                id = "builtin.alive",
                name = "Alive",
                layers = listOf(
                    effect(
                        "alive.glow", "motion_glow", BlendMode.OVERWRITE,
                        params = mapOf(
                            "baseHue" to 200f,
                            "hueSpread" to 70f,
                            "gain" to 2f,
                            "floor" to 0.1f,
                            "tipBias" to 0.5f
                        )
                    ),
                    effect(
                        "alive.level", "gravity_level", BlendMode.ADD, opacity = 0.35f,
                        params = mapOf(
                            "color" to rgb(0x2040A0),
                            "contrast" to 0.9f,
                            "floor" to 0f
                        )
                    ),
                    effect(
                        "alive.trail", "wag_trail", BlendMode.ADD, opacity = 0.7f,
                        params = mapOf(
                            "color" to rgb(0x1030C0),
                            "headColor" to rgb(0xC0E0FF),
                            "width" to 0.2f,
                            "persistence" to 0.5f
                        )
                    ),
                    effect(
                        "alive.tap", "tap_ripple", BlendMode.ADD,
                        params = mapOf(
                            "color" to rgb(0xFFFFFF),
                            "respondsTo" to 0f,
                            "speed" to 2.2f,
                            "width" to 0.15f,
                            "decay" to 0.8f
                        )
                    )
                )
            )
        )
    }
}
