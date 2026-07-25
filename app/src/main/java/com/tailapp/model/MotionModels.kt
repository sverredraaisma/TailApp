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
    ),

    // The MOT-5 catalogue. Defaults and ranges mirror each pattern's own
    // constructor in TailFirmware `main/motion/patterns/`; a mismatch here would
    // show the user a slider position the device is not actually at.
    IDLE_SWAY(
        0x03, "Idle Sway", listOf(
            ParamMetadata(0, "Amplitude", 8f, 0f, 45f, "°"),
            ParamMetadata(1, "Speed", 0.12f, 0.01f, 1f),
            ParamMetadata(2, "Pause chance", 0.2f, 0f, 1f),
            ParamMetadata(3, "Y posture", 0f, -90f, 90f, "°")
        )
    ),
    EXCITED_WAG(
        0x04, "Excited Wag", listOf(
            ParamMetadata(0, "Burst frequency", 6f, 0.5f, 12f, "Hz"),
            ParamMetadata(1, "Amplitude", 45f, 0f, 90f, "°"),
            ParamMetadata(2, "Burst duration", 2f, 0.2f, 8f, "s"),
            ParamMetadata(3, "Rest duration", 0.6f, 0f, 8f, "s")
        )
    ),
    CIRCLE(
        0x05, "Circle", listOf(
            ParamMetadata(0, "Frequency", 0.5f, 0.05f, 4f, "Hz"),
            ParamMetadata(1, "X radius", 30f, 0f, 90f, "°"),
            ParamMetadata(2, "Y radius", 30f, 0f, 90f, "°"),
            // Sign flips the direction of travel, so this is a toggle in
            // everything but type.
            ParamMetadata(3, "Direction", 1f, -1f, 1f)
        )
    ),
    FIGURE_EIGHT(
        0x06, "Figure Eight", listOf(
            ParamMetadata(0, "Frequency", 0.5f, 0.05f, 4f, "Hz"),
            ParamMetadata(1, "X amplitude", 30f, 0f, 90f, "°"),
            ParamMetadata(2, "Y amplitude", 20f, 0f, 90f, "°")
        )
    ),
    SHIVER(
        0x07, "Shiver", listOf(
            ParamMetadata(0, "Frequency", 22f, 5f, 40f, "Hz"),
            ParamMetadata(1, "Amplitude", 3f, 0f, 15f, "°"),
            // 0 means "shiver continuously"; anything else decays after a tap.
            ParamMetadata(2, "Decay time", 0.8f, 0f, 5f, "s")
        )
    ),

    /**
     * The pattern the FF05 beat trailer exists for. Wag rate and amplitude
     * follow loudness, and with beat lock on the wag snaps its phase to the
     * beat the app streams — which the device could not otherwise know.
     */
    AUDIO_WAG(
        0x08, "Audio Wag", listOf(
            ParamMetadata(0, "Base frequency", 1f, 0.1f, 6f, "Hz"),
            ParamMetadata(1, "Frequency gain", 4f, 0f, 10f, "Hz"),
            ParamMetadata(2, "Amplitude gain", 40f, 0f, 90f, "°"),
            ParamMetadata(3, "Beat lock", 1f, 0f, 1f)
        )
    ),
    HEARTBEAT(
        0x09, "Heartbeat", listOf(
            ParamMetadata(0, "BPM", 100f, 30f, 200f),
            ParamMetadata(1, "Thump amplitude", 20f, 0f, 60f, "°"),
            ParamMetadata(2, "Sharpness", 6f, 1f, 20f)
        )
    ),

    /**
     * Replays the uploaded sequence in the slot `MCMD_SELECT_SEQUENCE` chose
     * (MOT-8). Playback rate is its only parameter: the poses and whether the
     * sequence loops come from the blob, and a slider that could contradict the
     * authored animation would be a second source of truth for it.
     */
    KEYFRAME(
        0x0A, "Keyframe", listOf(
            ParamMetadata(0, "Speed", 1f, 0.05f, 8f, "x")
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
    val yAxisMax: Float,
    /**
     * The behavior engine's live state (MOT-6). Null on firmware that does not
     * append the block — which must not read as "the engine is off", since the
     * block says that with its own sentinel.
     *
     * [activePatternId] stays the *selected* pattern even while the engine is
     * driving; what is actually on the motors is
     * [BehaviorRuntime.drivingPatternId].
     */
    val behavior: BehaviorRuntime? = null,
    /**
     * Logical segment positions (MOT-0), in `[baseX, tipX, baseY, tipY]` order —
     * what the patterns emit, before the axis mix turns them into the two
     * physical axis targets carried by [encoderPositions]. Null on firmware
     * without the mixer; equal to the physical positions under an identity mix,
     * which is every device until the diagonal mechanism ships.
     */
    val logicalPositions: List<Float>? = null
) {
    val activePattern: MotionPattern? get() = MotionPattern.fromId(activePatternId)
}
