package com.tailapp.model

data class ParamMetadata(
    val id: Int,
    val name: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val unit: String = ""
)

enum class MotionPattern(val id: Byte, val displayName: String, val params: List<ParamMetadata>) {
    STATIC(
        0x00, "Static", listOf(
            ParamMetadata(0, "X first half", 0f, -180f, 180f, "°"),
            ParamMetadata(1, "X second half", 0f, -180f, 180f, "°"),
            ParamMetadata(2, "Y first half", 0f, -180f, 180f, "°"),
            ParamMetadata(3, "Y second half", 0f, -180f, 180f, "°")
        )
    ),
    WAGGING(
        0x01, "Wagging", listOf(
            ParamMetadata(0, "Frequency", 1.0f, 0.1f, 10f, "Hz"),
            ParamMetadata(1, "X amplitude", 45f, 0f, 180f, "°"),
            ParamMetadata(2, "Y first half", 0f, -180f, 180f, "°"),
            ParamMetadata(3, "Y second half", 0f, -180f, 180f, "°")
        )
    ),
    LOOSE(
        0x02, "Loose", listOf(
            ParamMetadata(0, "Damping", 0.3f, 0f, 1f),
            ParamMetadata(1, "Reactivity", 3.0f, 0f, 10f)
        )
    );

    companion object {
        fun fromId(id: Byte): MotionPattern? = entries.find { it.id == id }
    }
}

data class MotionState(
    val activePatternId: Byte,
    val params: List<Float>,
    val encoderPositions: List<Float>,
    val gravityX: Float,
    val gravityY: Float,
    val gravityZ: Float,
    val xAxisMin: Float,
    val xAxisMax: Float,
    val yAxisMin: Float,
    val yAxisMax: Float
) {
    val activePattern: MotionPattern? get() = MotionPattern.fromId(activePatternId)
}
