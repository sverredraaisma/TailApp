package com.tailapp.composer

import com.tailapp.model.BlendMode
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedEffect

/**
 * Translates a composition into a layer stack the *device* can render on its own.
 *
 * The composer renders on the phone and streams finished pixels over FF0A, which
 * stops the moment the phone does. The firmware has its own effect stack that
 * runs with nothing connected — a much smaller one. This maps what it can onto
 * that stack, so a look survives the phone leaving.
 *
 * It is an approximation and says so. The device has seven effects against the
 * composer's twenty-five, no folders, no per-frame beat tracking of its own, and
 * eight layers total. Layers that have no counterpart are **reported**, not
 * silently dropped: a user who exports a stack and gets something that looks
 * nothing like the preview, with no explanation, is worse off than one who is
 * told which three layers could not come along.
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

            "bass_pulse", "breathe" -> {
                notes += "\"${layer.name}\" becomes the device's Audio Power, which follows " +
                    "overall loudness rather than ${if (layer.effectId == "bass_pulse") "the bass band" else "a timed curve"}"
                val (r, g, b) = colour("color")
                config(LedEffect.AUDIO_POWER, listOf(r, g, b, 3f))
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
