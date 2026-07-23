package com.tailapp.drop

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame

/**
 * One stats-rate result from the transient tier.
 *
 * @param snapshot the statistics for the interval that just completed.
 * @param drop a drop that fired on it, with [DropEvent.precededBy] filled in.
 * @param sectionChange non-null only when the committed section state changed.
 * @param section the current section with a live [SectionStateUpdate.ramp];
 *   always present, so continuous modulation can follow it every sample.
 */
data class TransientResult(
    val snapshot: TransientSnapshot,
    val drop: DropEvent?,
    val sectionChange: SectionStateUpdate?,
    val section: SectionStateUpdate
)

/**
 * The transient tier: [TransientDetector] for the fast statistics and drops,
 * [SectionStateTracker] for the slow section state, wired together so that a
 * drop is stamped with the section it interrupted.
 *
 * That stamp matters downstream — a drop out of a build-up is the real thing,
 * while a drop out of a steady groove is more often a false positive, and the
 * effect profiles are free to weigh them differently.
 */
class TransientAnalyzer(
    private val config: TransientConfig = TransientConfig(),
    featureConfig: FeatureConfig = FeatureConfig()
) {
    private val detector = TransientDetector(config, featureConfig)
    private val sections = SectionStateTracker(config)

    /** Latest statistics, for the monitoring UI. */
    val snapshot: TransientSnapshot get() = detector.snapshot

    val sectionState: SectionState get() = sections.state

    /**
     * Feeds one feature frame.
     *
     * @return null on the frames between stats-rate evaluations — that is most of
     *   them, since features arrive at 50 fps and this tier runs at
     *   [TransientConfig.statsRateHz].
     */
    fun process(frame: FeatureFrame): TransientResult? {
        val sample = detector.process(frame) ?: return null

        // Read the section *before* the tracker sees this sample: a drop belongs
        // to the section it came out of, not the one it creates.
        val precedingSection = sections.state
        val drop = sample.drop?.copy(precededBy = precedingSection)

        val change = sections.update(sample.snapshot, drop)
        return TransientResult(
            snapshot = sample.snapshot,
            drop = drop,
            sectionChange = change,
            section = sections.currentUpdate(sample.snapshot.timestampNanos)
        )
    }

    fun reset() {
        detector.reset()
        sections.reset()
    }
}
