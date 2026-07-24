package com.tailapp.model

import com.tailapp.ble.protocol.Protocol

enum class LedEffect(val id: Byte, val displayName: String, val params: List<ParamMetadata>) {
    RAINBOW(
        0x00, "Rainbow", listOf(
            ParamMetadata(0, "Direction", 0f, 0f, 2f),
            ParamMetadata(1, "Speed", 60f, 0f, 360f, "°/s"),
            ParamMetadata(2, "Scale", 1.0f, 0.1f, 10f)
        )
    ),
    STATIC_COLOR(
        0x01, "Static Color", listOf(
            ParamMetadata(0, "Red", 255f, 0f, 255f),
            ParamMetadata(1, "Green", 255f, 0f, 255f),
            ParamMetadata(2, "Blue", 255f, 0f, 255f)
        )
    ),
    IMAGE(
        0x02, "Image", listOf(
            ParamMetadata(0, "Orientation", 0f, 0f, 3f)
        )
    ),
    AUDIO_POWER(
        0x03, "Audio Power", listOf(
            ParamMetadata(0, "Red", 0f, 0f, 255f),
            ParamMetadata(1, "Green", 255f, 0f, 255f),
            ParamMetadata(2, "Blue", 0f, 0f, 255f),
            ParamMetadata(3, "Fade rate", 3.0f, 0f, 10f)
        )
    ),
    AUDIO_BAR(
        0x04, "Audio Bar", listOf(
            ParamMetadata(0, "Red", 0f, 0f, 255f),
            ParamMetadata(1, "Green", 0f, 0f, 255f),
            ParamMetadata(2, "Blue", 255f, 0f, 255f),
            ParamMetadata(3, "Direction", 0f, 0f, 1f),
            ParamMetadata(4, "Fade rate", 3.0f, 0f, 10f)
        )
    ),
    AUDIO_FREQ_BARS(
        0x05, "Audio Freq Bars", listOf(
            ParamMetadata(0, "Bars", 8f, 1f, 32f),
            ParamMetadata(1, "Red", 0f, 0f, 255f),
            ParamMetadata(2, "Green", 255f, 0f, 255f),
            ParamMetadata(3, "Blue", 0f, 0f, 255f),
            ParamMetadata(4, "Fade rate", 5.0f, 0f, 10f),
            ParamMetadata(5, "Orientation", 0f, 0f, 1f)
        )
    ),

    /**
     * Flashes on beats streamed in the FF05 trailer.
     *
     * The device has no microphone and no beat detector, so this is dark unless
     * an app is streaming audio with beat information — it renders the phone's
     * beat tracker's output rather than deriving anything itself.
     */
    BEAT_PULSE(
        0x06, "Beat Pulse", listOf(
            ParamMetadata(0, "Red", 255f, 0f, 255f),
            ParamMetadata(1, "Green", 255f, 0f, 255f),
            ParamMetadata(2, "Blue", 255f, 0f, 255f),
            ParamMetadata(3, "Decay rate", 4.0f, 0.5f, 20f, "/s"),
            ParamMetadata(4, "Downbeat boost", 1.0f, 1f, 3f),
            ParamMetadata(5, "Sweep", 0f, 0f, 1f)
        )
    );

    companion object {
        fun fromId(id: Byte): LedEffect? = entries.find { it.id == id }
    }
}

enum class BlendMode(val id: Byte, val displayName: String) {
    MULTIPLY(0x00, "Multiply"),
    ADD(0x01, "Add"),
    SUBTRACT(0x02, "Subtract"),
    MIN(0x03, "Min"),
    MAX(0x04, "Max"),

    /**
     * Replaces the pixel below unless the overlay is black, which is treated as
     * transparent. A quirk rather than a design, but every saved composition
     * depends on it, so it stays exactly as it is.
     */
    OVERWRITE(0x05, "Overwrite"),

    /**
     * Alpha blend honouring the layer's opacity, treating black as a colour.
     * The mode to reach for when a layer should *darken* what is under it —
     * impossible with [OVERWRITE], where black simply disappears.
     */
    NORMAL(0x06, "Normal");

    companion object {
        fun fromId(id: Byte): BlendMode? = entries.find { it.id == id }
    }
}

data class LayerConfig(
    val effectId: Byte,
    val blendMode: Byte,
    val enabled: Boolean,
    val flipX: Boolean,
    val flipY: Boolean,
    val mirrorX: Boolean,
    val mirrorY: Boolean,
    val params: List<Float>,
    /**
     * Per-layer mix, 0-255 (protocol v5). Defaulted to fully opaque so a layer
     * built without one behaves as it did before opacity existed.
     */
    val opacity: Int = 255
) {
    val effect: LedEffect? get() = LedEffect.fromId(effectId)
    val blend: BlendMode? get() = BlendMode.fromId(blendMode)

    /**
     * True for a slot the firmware has cleared. `LCMD_REMOVE_LAYER` does *not*
     * shift the remaining layers down — it stamps `effect_id = 0xFF` and leaves
     * `num_layers` alone — so layer indices stay stable across a removal.
     */
    val isEmpty: Boolean get() = effectId == Protocol.EMPTY_EFFECT_ID

    companion object {
        /** The placeholder a removed slot reads back as. */
        fun empty(): LayerConfig = LayerConfig(
            effectId = Protocol.EMPTY_EFFECT_ID,
            blendMode = 0,
            enabled = false,
            flipX = false, flipY = false,
            mirrorX = false, mirrorY = false,
            params = List(8) { 0f }
        )
    }
}

/**
 * The device's output stage (protocol v5) — what happens to a composited frame
 * on its way to the strip.
 *
 * @property lastPowerScale what the current limiter applied to the last frame,
 *   0-255. Anything below 255 means the frame asked for more current than the
 *   budget allows and was dimmed to fit, which is worth surfacing: the look the
 *   user designed is not the look the tail is showing.
 */
data class LedOutputState(
    val brightness: Int,
    val gammaEnabled: Boolean,
    val currentLimitMa: Int,
    val lastPowerScale: Int
) {
    val isPowerLimited: Boolean get() = lastPowerScale < 255
}

data class LedState(
    val numRings: Int,
    val ledsPerRing: List<Int>,
    val layers: List<LayerConfig>,
    /** Null on firmware older than protocol v5, which does not publish it. */
    val output: LedOutputState? = null
) {
    val totalLeds: Int get() = ledsPerRing.sum()

    /** Layer indices that currently hold an effect, in slot order. */
    val occupiedLayerIndices: List<Int>
        get() = layers.indices.filter { !layers[it].isEmpty }

    /**
     * First slot a new layer can be written to, or null when the stack is full.
     * Reuses a cleared slot before extending the stack.
     */
    fun firstFreeLayerIndex(maxLayers: Int): Int? {
        val reusable = layers.indexOfFirst { it.isEmpty }
        if (reusable >= 0) return reusable
        return layers.size.takeIf { it < maxLayers }
    }
}
