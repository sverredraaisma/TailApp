package com.tailapp.composer

import com.tailapp.model.BlendMode
import com.tailapp.model.LayerConfig
import com.tailapp.led.Palettes
import com.tailapp.model.LedEffect

/**
 * Translates a composition into a layer stack the *device* can render on its own.
 *
 * The composer renders on the phone and streams finished pixels over FF0A, which
 * stops the moment the phone does. The firmware has its own effect stack that
 * runs with nothing connected — a much smaller one. This maps what it can onto
 * that stack, so a look survives the phone leaving.
 *
 * It is an approximation and says so. The device has seventeen effects against
 * the composer's twenty-five, no folders, no per-frame beat tracking of its own,
 * and eight layers total. Layers that have no counterpart are **reported**, not
 * silently dropped: a user who exports a stack and gets something that looks
 * nothing like the preview, with no explanation, is worse off than one who is
 * told which three layers could not come along.
 *
 * Eight of the mappings became exact when the device gained its own effect
 * catalogue — fire, gradient, plasma, sparkle, breathe and the three
 * tail-reactive effects now have real counterparts rather than approximations.
 * The remaining lossiness is concentrated in one place: the device selects a
 * **palette** where the composer takes explicit colours, so a look built around
 * particular colours will come back in the palette's, and that is called out per
 * layer rather than left to be discovered.
 */
object FirmwareExport {

    /**
     * @property layers what to write to the device, bottom-to-top.
     * @property unmapped names of layers that had no device counterpart.
     * @property notes per-layer caveats where the mapping is lossy but usable.
     */
    data class Result(
        val layers: List<LayerConfig>,
        val unmapped: List<String>,
        val notes: List<String>
    ) {
        val isExact: Boolean get() = unmapped.isEmpty() && notes.isEmpty()
        val isEmpty: Boolean get() = layers.isEmpty()
    }

    /** Device-side layer slots. Mirrors `MAX_LED_LAYERS`. */
    const val MAX_LAYERS = 8

    /**
     * Palette choices for the effects that take one.
     *
     * Each is the firmware effect's *own* declared default, so an exported layer
     * lands on the look the device was tuned for rather than on a palette picked
     * here. Deriving a palette from the composer's colours was the alternative,
     * and it would be a guess dressed up as a translation — six fixed tables
     * cannot represent an arbitrary colour pair, and pretending otherwise is
     * exactly the kind of quiet lie this mapping is written to avoid.
     */
    private val DEFAULT_PLASMA_PALETTE = Palettes.SUNSET.toFloat()
    private val DEFAULT_GRADIENT_PALETTE = Palettes.RAINBOW.toFloat()
    private val DEFAULT_SPARKLE_PALETTE = Palettes.RAINBOW.toFloat()
    private val DEFAULT_MOTION_PALETTE = Palettes.RAINBOW.toFloat()

    /** Guards the reciprocals below; both come from user-editable sliders. */
    private const val MIN_RATE_HZ = 0.02f
    private const val MIN_DECAY_SECONDS = 0.02f

    private fun paletteNote(layerName: String, deviceEffect: String): String =
        "\"$layerName\" becomes the device's $deviceEffect, which draws from a " +
            "built-in palette rather than the colours you chose"

    fun export(composition: Composition): Result {
        val layers = mutableListOf<LayerConfig>()
        val unmapped = mutableListOf<String>()
        val notes = mutableListOf<String>()

        // Folders composite twice on the phone: children blend among themselves
        // and the result blends into the parent as one unit. The device has a
        // flat stack, so a folder cannot be reproduced — flattening it changes
        // what a modulator inside it reaches. Its children are exported
        // individually and the difference is called out.
        flatten(composition.layers, into = mutableListOf(), notes = notes)
            .forEach { layer ->
                if (layers.size >= MAX_LAYERS) {
                    unmapped += "${layer.name} (no free device layer)"
                    return@forEach
                }
                val mapped = mapLayer(layer, notes)
                if (mapped == null) unmapped += layer.name else layers += mapped
            }

        return Result(layers, unmapped, notes)
    }

    private fun flatten(
        nodes: List<LayerNode>,
        into: MutableList<EffectLayer>,
        notes: MutableList<String>
    ): List<EffectLayer> {
        for (node in nodes) {
            when (node) {
                is EffectLayer -> if (node.enabled) into += node
                is GroupLayer -> {
                    if (node.enabled) {
                        notes += "\"${node.name}\" is a folder; the device has a flat stack, " +
                            "so its layers were merged into the main stack"
                        flatten(node.children, into, notes)
                    }
                }
            }
        }
        return into
    }

    /** Returns null when nothing on the device resembles this effect. */
    private fun mapLayer(layer: EffectLayer, notes: MutableList<String>): LayerConfig? {
        val blend = layer.blendMode
        val opacity = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()

        fun config(effect: LedEffect, params: List<Float>) = LayerConfig(
            effectId = effect.id,
            blendMode = blend.id,
            enabled = true,
            flipX = layer.flipX,
            flipY = layer.flipY,
            mirrorX = layer.mirrorX,
            mirrorY = layer.mirrorY,
            params = List(8) { params.getOrElse(it) { 0f } },
            opacity = opacity
        )

        val p = layer.params
        fun colour(key: String, fallback: Int = 0xFFFFFF): Triple<Float, Float, Float> {
            val packed = p[key]?.toInt() ?: fallback
            return Triple(
                ((packed shr 16) and 0xFF).toFloat(),
                ((packed shr 8) and 0xFF).toFloat(),
                (packed and 0xFF).toFloat()
            )
        }

        return when (layer.effectId) {
            "solid" -> {
                val (r, g, b) = colour("color")
                val brightness = p["brightness"] ?: 1f
                config(LedEffect.STATIC_COLOR, listOf(r * brightness, g * brightness, b * brightness))
            }

            "rainbow" -> config(
                LedEffect.RAINBOW,
                // Device rainbow: direction, speed (deg/s), scale.
                listOf(1f, (p["speed"] ?: 0.15f) * 360f, p["scale"] ?: 1f)
            )

            "spectrum_bars" -> {
                notes += "\"${layer.name}\" becomes the device's Audio Freq Bars, which " +
                    "reads the 64-bin stream instead of the full analysis spectrum"
                val (r, g, b) = colour("colorHigh", 0x00FF80)
                config(LedEffect.AUDIO_FREQ_BARS, listOf(8f, r, g, b, 5f, 0f))
            }

            "vu_meter" -> {
                val (r, g, b) = colour("color", 0x00FF00)
                config(LedEffect.AUDIO_BAR, listOf(r, g, b, 0f, 3f))
            }

            "bass_pulse" -> {
                notes += "\"${layer.name}\" becomes the device's Audio Power, which follows " +
                    "overall loudness rather than the bass band"
                val (r, g, b) = colour("color")
                config(LedEffect.AUDIO_POWER, listOf(r, g, b, 3f))
            }

            // --- The device's own catalogue: exact or near-exact counterparts ---

            "breathe" -> {
                val (r, g, b) = colour("color", 0x2060FF)
                // The composer states a rate in Hz and the device a period in
                // seconds; they are the same curve described from either end.
                val rate = (p["rate"] ?: 0.18f).coerceAtLeast(MIN_RATE_HZ)
                if (p["syncToBar"] == 1f) {
                    notes += "\"${layer.name}\" breathes at a fixed rate on the tail; " +
                        "one breath per bar needs a beat the device only has while streaming"
                }
                config(
                    LedEffect.BREATHING_GLOW,
                    listOf(r, g, b, 1f / rate, p["floor"] ?: 0.25f)
                )
            }

            "fire" -> {
                notes += paletteNote(layer.name, "Fire")
                config(
                    LedEffect.FIRE,
                    // The composer's intensity runs to 3 against the device's 1;
                    // clamping rather than rescaling keeps a normal setting
                    // looking the same and only flattens the deliberately blown-out end.
                    listOf(
                        (p["intensity"] ?: 1.4f).coerceIn(0f, 1f),
                        (p["speed"] ?: 1.2f).coerceIn(0f, 3f),
                        (p["falloff"] ?: 1.2f).coerceIn(0f, 4f),
                        Palettes.FIRE.toFloat()
                    )
                )
            }

            "plasma" -> {
                notes += paletteNote(layer.name, "Plasma")
                config(
                    LedEffect.PLASMA,
                    listOf(
                        DEFAULT_PLASMA_PALETTE,
                        (p["scale"] ?: 1.5f).coerceIn(0.1f, 10f),
                        (p["speed"] ?: 0.2f).coerceIn(0f, 2f)
                    )
                )
            }

            "gradient" -> {
                notes += paletteNote(layer.name, "Gradient Scroll")
                val speed = p["speed"] ?: 0.1f
                if (speed < 0f) {
                    // The device scrolls one way only, so a reversed gradient
                    // would otherwise come back scrolling the wrong direction
                    // with no hint that anything was dropped.
                    notes += "\"${layer.name}\" scrolls the other way on the tail; " +
                        "the device has no reverse"
                }
                config(
                    LedEffect.GRADIENT_SCROLL,
                    listOf(
                        DEFAULT_GRADIENT_PALETTE,
                        kotlin.math.abs(speed).coerceIn(0f, 2f),
                        (p["repeat"] ?: 1f).coerceIn(0.1f, 10f),
                        if (p["axis"] == 1f) 1f else 0f
                    )
                )
            }

            "sparkle" -> {
                notes += paletteNote(layer.name, "Twinkle")
                config(
                    LedEffect.TWINKLE,
                    listOf(
                        (p["density"] ?: 0.25f).coerceIn(0f, 1f),
                        // Decay is a time; the device wants a rate.
                        (1f / (p["decay"] ?: 0.25f).coerceAtLeast(MIN_DECAY_SECONDS))
                            .coerceIn(0.05f, 5f),
                        DEFAULT_SPARKLE_PALETTE
                    )
                )
            }

            // --- The tail-reactive three: the device reads its own sensors ---

            "motion_glow" -> {
                notes += "\"${layer.name}\" glows from a palette on the tail rather than " +
                    "your hue range; the movement it reacts to is the same"
                config(
                    LedEffect.MOTION_GLOW,
                    listOf(
                        DEFAULT_MOTION_PALETTE,
                        (p["gain"] ?: 1.5f).coerceIn(0f, 5f),
                        (p["floor"] ?: 0.12f).coerceIn(0f, 1f)
                    )
                )
            }

            "tap_ripple" -> {
                val (r, g, b) = colour("color")
                if (p["respondsTo"] != null && p["respondsTo"] != 0f) {
                    // The device ripples from whichever end felt the tap, always.
                    notes += "\"${layer.name}\" answers taps at either end on the tail; " +
                        "the device cannot be told to ignore one"
                }
                config(
                    LedEffect.TAP_RIPPLE,
                    listOf(r, g, b, (p["speed"] ?: 2f).coerceIn(0.1f, 5f), (p["width"] ?: 0.16f).coerceIn(0.01f, 1f))
                )
            }

            "gravity_level" -> {
                val (r, g, b) = colour("color", 0x30FF80)
                config(
                    LedEffect.GRAVITY_LEVEL,
                    // Contrast is 0..1 in the composer and 0..3 on the device;
                    // the composer's full setting is the device's 1, not its 3,
                    // so a level look does not arrive three times harder.
                    listOf(r, g, b, (p["contrast"] ?: 0.85f).coerceIn(0f, 1f))
                )
            }

            "beat_flash", "drop_flash", "strobe" -> {
                val (r, g, b) = colour("color")
                // The device can only do this at all because the app streams the
                // beat to it in the FF05 trailer.
                config(
                    LedEffect.BEAT_PULSE,
                    listOf(r, g, b, 1f / (p["decay"] ?: 0.25f), p["downbeatBoost"] ?: 1f, 0f)
                )
            }

            "beat_ripple", "ring_chase", "bar_sweep" -> {
                val (r, g, b) = colour("color")
                notes += "\"${layer.name}\" becomes a sweeping Beat Pulse; the device has no " +
                    "travelling-wave effect of its own"
                config(
                    LedEffect.BEAT_PULSE,
                    listOf(r, g, b, 1f / (p["decay"] ?: 0.5f), 1f, 1f)
                )
            }

            else -> null
        }
    }

    /**
     * A human-readable summary of what an export will and will not carry over.
     * Deliberately blunt — the point is that the user knows before they save
     * over a profile slot.
     */
    fun describe(result: Result): String = buildString {
        if (result.isEmpty) {
            append("Nothing in this stack can run on the tail by itself.")
            return@buildString
        }
        append("${result.layers.size} of ${result.layers.size + result.unmapped.size} layers ")
        append("will run on the tail.")
        if (result.unmapped.isNotEmpty()) {
            append("\n\nLeft behind: ${result.unmapped.joinToString(", ")}.")
        }
        if (result.notes.isNotEmpty()) {
            append("\n\n")
            append(result.notes.joinToString("\n"))
        }
    }
}
