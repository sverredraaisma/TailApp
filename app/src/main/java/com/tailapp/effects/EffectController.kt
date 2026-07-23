package com.tailapp.effects

import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionStateUpdate
import com.tailapp.genre.GenreState
import com.tailapp.lighting.LightingOutput

/**
 * Tunables for [EffectController].
 *
 * @param genreWindowSeconds rolling window a genre change must win a majority in.
 * @param minGenreAgreement predictions that must agree before a switch is allowed.
 * @param genreMajorityFraction share of the window the winner must hold.
 * @param minGenreConfidence predictions below this are ignored entirely.
 * @param triggerOffsetMillis user calibration, applied to every event before it
 *   reaches the renderer. Negative fires earlier, to compensate for the latency
 *   between deciding to light a beat and the LEDs actually changing.
 */
data class EffectControllerConfig(
    val genreWindowSeconds: Float = 12f,
    val minGenreAgreement: Int = 3,
    val genreMajorityFraction: Float = 0.6f,
    val minGenreConfidence: Float = 0.35f,
    val triggerOffsetMillis: Float = 0f
) {
    init {
        require(genreWindowSeconds > 0f) { "genreWindowSeconds must be positive" }
        require(minGenreAgreement >= 1) { "minGenreAgreement must be at least 1" }
        require(genreMajorityFraction in 0f..1f) { "genreMajorityFraction must be a fraction" }
    }
}

/**
 * The piece that decides *what* the lighting does, between the analysis tiers
 * and the [LightingOutput] that shows it.
 *
 * Three jobs:
 * - **Profile selection.** Genre predictions pick an [EffectProfile], debounced
 *   by [GenreDebouncer] so an ambiguous stretch cannot make the look flicker. A
 *   manual override wins over the classifier until it is cleared.
 * - **Trigger offset.** Every event's timestamp is shifted by the user's
 *   calibration before the renderer sees it, so lighting latency can be dialled
 *   out without touching the analysis.
 * - **Dispatch.** Events go to the renderer *and* to the output, because an
 *   output that reacts to events rather than pixels (a future WLED backend, a
 *   haptic, a debug logger) needs them too.
 *
 * Deliberately synchronous and free of coroutines: the flow plumbing and the
 * frame timer live in `LightingEngine`, so this class can be driven directly
 * from tests with a fake clock and a recording output.
 */
class EffectController(
    private val renderer: ReactiveRenderer,
    private val output: LightingOutput,
    config: EffectControllerConfig = EffectControllerConfig()
) {
    private val debouncer = GenreDebouncer(config)

    /** User calibration in milliseconds; negative fires earlier. */
    var triggerOffsetMillis: Float = config.triggerOffsetMillis

    private var automaticProfile: EffectProfile = EffectProfiles.DEFAULT

    /** Set to pin a profile regardless of genre; null returns to automatic. */
    var manualProfile: EffectProfile? = null
        set(value) {
            field = value
            applyProfile(value ?: automaticProfile)
        }

    /** The profile currently driving the renderer. */
    val activeProfile: EffectProfile get() = renderer.profile

    /** True while [manualProfile] is overriding the classifier. */
    val isOverridden: Boolean get() = manualProfile != null

    init {
        applyProfile(EffectProfiles.DEFAULT)
    }

    /** Re-reads the strip layout, e.g. after the device reports a new ring config. */
    fun setLayout(ledsPerRing: List<Int>) = renderer.setLayout(ledsPerRing)

    fun onBeat(event: BeatEvent) {
        val shifted = event.copy(timestampNanos = event.timestampNanos + offsetNanos())
        renderer.onBeat(shifted)
        output.onBeat(shifted)
    }

    fun onDrop(event: DropEvent) {
        val shifted = event.copy(timestampNanos = event.timestampNanos + offsetNanos())
        renderer.onDrop(shifted)
        output.onDrop(shifted)
    }

    /**
     * Section state drives slow modulation, so it is deliberately *not* offset —
     * shifting a build-up ramp by a few tens of milliseconds would be invisible,
     * and applying the calibration twice (here and to the beats inside the
     * build-up) would not be.
     */
    fun onSection(update: SectionStateUpdate) = renderer.onSection(update)

    /** Feeds a genre prediction; switches profile only if the debouncer agrees. */
    fun onGenre(state: GenreState) {
        val winner = debouncer.offer(state) ?: return
        automaticProfile = EffectProfiles.forLabel(winner)
        if (manualProfile == null) applyProfile(automaticProfile)
    }

    /** Renders and dispatches one frame. */
    fun renderFrame(nowNanos: Long) {
        val frame = renderer.render(nowNanos)
        output.onFrame(frame, nowNanos)
    }

    fun reset() {
        debouncer.reset()
        renderer.reset()
        automaticProfile = EffectProfiles.DEFAULT
        applyProfile(manualProfile ?: EffectProfiles.DEFAULT)
    }

    private fun applyProfile(profile: EffectProfile) {
        if (renderer.profile.id == profile.id) return
        renderer.profile = profile
        output.onProfileChange(profile)
    }

    private fun offsetNanos(): Long = (triggerOffsetMillis * NANOS_PER_MILLI).toLong()

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000f
    }
}
