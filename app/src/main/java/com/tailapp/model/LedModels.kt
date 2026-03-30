package com.tailapp.model

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
    OVERWRITE(0x05, "Overwrite");

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
    val params: List<Float>
) {
    val effect: LedEffect? get() = LedEffect.fromId(effectId)
    val blend: BlendMode? get() = BlendMode.fromId(blendMode)
}

data class LedState(
    val numRings: Int,
    val ledsPerRing: List<Int>,
    val layers: List<LayerConfig>
) {
    val totalLeds: Int get() = ledsPerRing.sum()
}
