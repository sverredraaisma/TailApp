package com.tailapp.composer

/** How effects are grouped in the composer's "add layer" picker. */
enum class EffectCategory(val displayName: String) {
    /** Continuous looks meant as a base layer: solids, gradients, rainbows, fields. */
    BASE("Base"),

    /**
     * Driven by discrete musical events — tracked beats and detected drops:
     * flashes, chases, strobes, sweeps, drop bursts.
     */
    BEAT("Beat"),

    /** Driven by loudness or the FFT spectrum: meters, bars, pulses. */
    AUDIO("Audio"),

    /**
     * Driven by the tail itself rather than by sound — IMU taps, orientation and
     * how fast it is wagging. These are the only effects that cannot be
     * reproduced on any other lighting hardware, because their input is the
     * device's own body.
     */
    TAIL("Tail"),

    /**
     * Emit greyscale/masks rather than a look, meant to modulate a folder below
     * them (typically with [com.tailapp.model.BlendMode.MULTIPLY]).
     */
    MODULATOR("Modulator")
}

/**
 * The registry entry for one effect type: its identity, its editor metadata, its
 * parameter [schema], and a [factory] that builds a fresh live instance.
 *
 * The app-side analogue of the firmware's `LedEffect` id → subclass mapping
 * (`com.tailapp.led.FirmwareEffectFactory`) plus its parameter documentation,
 * combined into one declarative value. Adding an effect is one effect class and
 * one entry in [ReactiveEffects.ALL]; nothing in the compositor or the editor
 * changes.
 *
 * @property id stable key, persisted and matched when diffing a composition.
 * @property displayName label shown in the editor.
 * @property category grouping in the picker.
 * @property schema the effect's tunable parameters, in editor order.
 * @property factory builds an instance whose [ParamBag] is pre-filled with the
 *   schema defaults.
 */
class EffectSpec(
    val id: String,
    val displayName: String,
    val category: EffectCategory,
    val schema: List<EffectParam>,
    val factory: () -> ReactiveEffect
)
