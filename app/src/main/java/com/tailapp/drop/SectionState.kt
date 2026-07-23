package com.tailapp.drop

/**
 * Coarse song-section estimate, driving slow modulation (build-up ramps,
 * breakdown dimming) rather than one-shot triggers.
 *
 * The taxonomy follows the EDMFormer-style sections the project plan calls for.
 * Unlike the plan's trained head, this is derived heuristically from the
 * transient tier — see `SectionStateTracker`.
 */
enum class SectionState {
    /** Not enough audio seen yet to judge. */
    UNKNOWN,

    /** Sparse, low-energy opening. */
    INTRO,

    /** Energy and onset density climbing, bass often filtered out. */
    BUILDUP,

    /** Full-energy section following a drop. */
    DROP,

    /** Energy fell away mid-track; the groove is suspended. */
    BREAKDOWN,

    /** Energy decaying towards the end of a track. */
    OUTRO;

    /** Sections during which lighting should ramp rather than hold steady. */
    val isTransitional: Boolean get() = this == BUILDUP
}

/**
 * A debounced section-state observation.
 *
 * @param state the section now considered active.
 * @param confidence `0..1` — agreement of the underlying features.
 * @param timestampNanos [System.nanoTime] the state took effect.
 * @param ramp `0..1` progress through a transitional section, 0 elsewhere. For
 *   [SectionState.BUILDUP] this is the normalised climb used to ramp strobe
 *   intensity towards the expected drop.
 */
data class SectionStateUpdate(
    val state: SectionState,
    val confidence: Float,
    val timestampNanos: Long,
    val ramp: Float = 0f
)
