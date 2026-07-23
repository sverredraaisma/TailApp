package com.tailapp.drop

/**
 * A detected drop: a joint spike in broadband and low-band energy after a
 * comparatively quiet stretch.
 *
 * Scores are z-scores against the detector's trailing window, so they are
 * self-adapting rather than absolute levels.
 *
 * @param timestampNanos [System.nanoTime] of the frame that triggered the drop.
 * @param intensity `0..1` — how far the spike exceeded the firing threshold.
 * @param broadbandZ z-score of the broadband RMS at the trigger frame.
 * @param bassZ z-score of the low-band energy at the trigger frame.
 * @param precededBy the section state the detector was in when it fired; a drop
 *   out of [SectionState.BUILDUP] is far more likely to be a real drop than one
 *   out of a steady groove.
 */
data class DropEvent(
    val timestampNanos: Long,
    val intensity: Float,
    val broadbandZ: Float,
    val bassZ: Float,
    val precededBy: SectionState
)
