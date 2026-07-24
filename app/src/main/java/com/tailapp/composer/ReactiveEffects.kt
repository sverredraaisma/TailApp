package com.tailapp.composer

import com.tailapp.composer.effects.BarSweepEffect
import com.tailapp.composer.effects.BassPulseEffect
import com.tailapp.composer.effects.BeatFlashEffect
import com.tailapp.composer.effects.BeatMaskEffect
import com.tailapp.composer.effects.BeatRippleEffect
import com.tailapp.composer.effects.BreatheEffect
import com.tailapp.composer.effects.DropFlashEffect
import com.tailapp.composer.effects.EnergyScrollEffect
import com.tailapp.composer.effects.FireEffect
import com.tailapp.composer.effects.GradientEffect
import com.tailapp.composer.effects.GravityLevelEffect
import com.tailapp.composer.effects.MotionGlowEffect
import com.tailapp.composer.effects.PlasmaEffect
import com.tailapp.composer.effects.RainbowEffect
import com.tailapp.composer.effects.RingChaseEffect
import com.tailapp.composer.effects.SectionDimmerEffect
import com.tailapp.composer.effects.SolidColorEffect
import com.tailapp.composer.effects.SparkleEffect
import com.tailapp.composer.effects.SpectrumBarsEffect
import com.tailapp.composer.effects.StrobeEffect
import com.tailapp.composer.effects.TapGateEffect
import com.tailapp.composer.effects.TapRippleEffect
import com.tailapp.composer.effects.VolumeDimmerEffect
import com.tailapp.composer.effects.VuMeterEffect
import com.tailapp.composer.effects.WagTrailEffect

/**
 * Every effect the composer can place in a layer.
 *
 * The app-side equivalent of `FirmwareEffectFactory`'s id → subclass dispatch,
 * except the table is the registry itself. Adding an effect is one class with a
 * `SPEC` and one line in [ALL]; the compositor, the serializer, the editor and
 * persistence all work off [EffectSpec] and need no change.
 *
 * Ids are persisted inside saved compositions, so an entry's [EffectSpec.id]
 * must never be renamed — a rename orphans every saved layer that used it (they
 * render nothing, by design, rather than failing the composition).
 */
object ReactiveEffects {

    /** Registration order, which is also the order the editor's picker shows. */
    val ALL: List<EffectSpec> = listOf(
        // Base — continuous looks, usually the bottom of a stack.
        SolidColorEffect.SPEC,
        GradientEffect.SPEC,
        RainbowEffect.SPEC,
        PlasmaEffect.SPEC,
        FireEffect.SPEC,
        BreatheEffect.SPEC,

        // Beat — discrete musical events.
        BeatFlashEffect.SPEC,
        BeatRippleEffect.SPEC,
        RingChaseEffect.SPEC,
        StrobeEffect.SPEC,
        SparkleEffect.SPEC,
        BarSweepEffect.SPEC,
        DropFlashEffect.SPEC,

        // Audio — loudness and spectrum.
        VuMeterEffect.SPEC,
        SpectrumBarsEffect.SPEC,
        BassPulseEffect.SPEC,
        EnergyScrollEffect.SPEC,

        // Tail — the device's own body: taps, orientation, how hard it is
        // wagging. Nothing here needs a microphone.
        TapRippleEffect.SPEC,
        MotionGlowEffect.SPEC,
        WagTrailEffect.SPEC,
        GravityLevelEffect.SPEC,

        // Modulator — greyscale, meant to multiply over a folder.
        BeatMaskEffect.SPEC,
        VolumeDimmerEffect.SPEC,
        SectionDimmerEffect.SPEC,
        TapGateEffect.SPEC
    )

    private val byId: Map<String, EffectSpec> = ALL.associateBy { it.id }

    init {
        // A duplicate id would make one of the two effects unreachable through
        // `byId` while still showing in the picker — silent and baffling.
        require(byId.size == ALL.size) { "duplicate effect id in ReactiveEffects.ALL" }
    }

    /** The spec for [id], or null when no build registers it. */
    fun spec(id: String): EffectSpec? = byId[id]

    /** A fresh instance with schema defaults, or null for an unknown [id]. */
    fun create(id: String): ReactiveEffect? = byId[id]?.factory?.invoke()

    /** A fresh instance with [params] applied over the schema defaults. */
    fun create(id: String, params: Map<String, Float>): ReactiveEffect? =
        create(id)?.also { it.applyParams(params) }

    /** Registered effects grouped for the editor's picker, in [ALL] order. */
    fun byCategory(): Map<EffectCategory, List<EffectSpec>> = ALL.groupBy { it.category }

    /** The schema defaults for [id] — the params a freshly added layer carries. */
    fun defaultParams(id: String): Map<String, Float> =
        byId[id]?.schema?.associate { it.key to it.default } ?: emptyMap()
}
