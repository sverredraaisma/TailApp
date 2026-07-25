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
    ),

    // The LED-3 catalogue. Defaults mirror each effect's own constructor in
    // TailFirmware `main/led/effects/`; a mismatch here would show the user a
    // slider position the device is not actually at. Ranges follow each
    // effect's own clamp in `render()` where it has one.
    //
    // A "Palette" slot tops out at PALETTE_BUILTIN_COUNT-1: the four user
    // slots exist in `palette.h`, but no BLE command writes them, so an id
    // above 5 samples black on the device and in the preview alike.
    FIRE(
        0x07, "Fire", listOf(
            ParamMetadata(0, "Intensity", 1.0f, 0f, 1f),
            ParamMetadata(1, "Speed", 0.6f, 0f, 3f),
            ParamMetadata(2, "Cooling", 1.2f, 0f, 4f),
            ParamMetadata(3, "Palette", 0f, 0f, 5f)
        )
    ),
    BREATHING_GLOW(
        0x08, "Breathing Glow", listOf(
            ParamMetadata(0, "Red", 255f, 0f, 255f),
            ParamMetadata(1, "Green", 220f, 0f, 255f),
            ParamMetadata(2, "Blue", 180f, 0f, 255f),
            ParamMetadata(3, "Period", 4.0f, 0.1f, 30f, "s"),
            ParamMetadata(4, "Min brightness", 0.08f, 0f, 1f)
        )
    ),
    COMET(
        0x09, "Comet", listOf(
            ParamMetadata(0, "Red", 0f, 0f, 255f),
            ParamMetadata(1, "Green", 160f, 0f, 255f),
            ParamMetadata(2, "Blue", 255f, 0f, 255f),
            // Sign sets the direction of travel, so the range straddles zero.
            ParamMetadata(3, "Speed", 0.4f, -3f, 3f, "/s"),
            ParamMetadata(4, "Tail length", 0.25f, 0.01f, 1f),
            ParamMetadata(5, "Bounce", 0f, 0f, 1f)
        )
    ),
    TWINKLE(
        0x0A, "Twinkle", listOf(
            ParamMetadata(0, "Density", 0.12f, 0f, 1f),
            ParamMetadata(1, "Fade speed", 1.0f, 0.05f, 5f, "/s"),
            ParamMetadata(2, "Palette", 2f, 0f, 5f)
        )
    ),
    GRADIENT_SCROLL(
        0x0B, "Gradient Scroll", listOf(
            ParamMetadata(0, "Palette", 2f, 0f, 5f),
            ParamMetadata(1, "Speed", 0.15f, 0f, 2f, "/s"),
            ParamMetadata(2, "Scale", 1.0f, 0.1f, 10f),
            ParamMetadata(3, "Axis", 0f, 0f, 1f)
        )
    ),
    PLASMA(
        0x0C, "Plasma", listOf(
            ParamMetadata(0, "Palette", 3f, 0f, 5f),
            ParamMetadata(1, "Scale", 2.0f, 0.1f, 10f),
            ParamMetadata(2, "Speed", 0.3f, 0f, 2f, "/s")
        )
    ),
    CANDLE_FLICKER(
        0x0D, "Candle Flicker", listOf(
            ParamMetadata(0, "Colour temp", 0.5f, 0f, 1f),
            ParamMetadata(1, "Intensity", 0.85f, 0f, 1f),
            ParamMetadata(2, "Wind", 0.2f, 0f, 1f)
        )
    ),

    /**
     * The three effects below read the tail's own motion sensors — the one
     * category no other lighting hardware can do. They are dark or at rest
     * (never wrong) when the app has told the preview nothing about the body;
     * on the device they read `MotionBus` directly.
     */
    MOTION_GLOW(
        0x0E, "Motion Glow", listOf(
            ParamMetadata(0, "Palette", 2f, 0f, 5f),
            ParamMetadata(1, "Speed gain", 1.0f, 0f, 5f),
            ParamMetadata(2, "Floor", 0.15f, 0f, 1f)
        )
    ),
    TAP_RIPPLE(
        0x0F, "Tap Ripple", listOf(
            ParamMetadata(0, "Red", 0f, 0f, 255f),
            ParamMetadata(1, "Green", 180f, 0f, 255f),
            ParamMetadata(2, "Blue", 255f, 0f, 255f),
            ParamMetadata(3, "Speed", 1.2f, 0.1f, 5f, "/s"),
            ParamMetadata(4, "Width", 0.3f, 0.01f, 1f)
        )
    ),
    GRAVITY_LEVEL(
        0x10, "Gravity Level", listOf(
            ParamMetadata(0, "Red", 0f, 0f, 255f),
            ParamMetadata(1, "Green", 140f, 0f, 255f),
            ParamMetadata(2, "Blue", 255f, 0f, 255f),
            ParamMetadata(3, "Contrast", 1.2f, 0f, 3f)
        )
    ),

    /**
     * LED-5: plays a multi-frame animation uploaded to a device flash slot.
     * The frames live on the device, not in a parameter, so the app can select
     * and configure the effect but cannot preview the pixels — the composer's
     * live preview has no access to the tail's flash. Ranges mirror
     * `AnimationEffect::describe_params()` in TailFirmware.
     */
    ANIMATION(
        0x11, "Animation", listOf(
            ParamMetadata(0, "Slot", 0f, 0f, 3f),
            ParamMetadata(1, "Speed", 1f, 0f, 16f, "x"),
            ParamMetadata(2, "Loop", 1f, 0f, 1f),
            ParamMetadata(3, "Beat lock", 0f, 0f, 1f),
            ParamMetadata(4, "Beats", 4f, 1f, 16f),
            ParamMetadata(5, "Orientation", 0f, 0f, 3f)
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
