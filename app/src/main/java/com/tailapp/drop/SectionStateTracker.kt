package com.tailapp.drop

import kotlin.math.abs

/**
 * Heuristic song-section estimator.
 *
 * The project plan called for a small trained head on top of a frozen embedding
 * model here. That head needed hand-marked build-up/drop/breakdown timestamps to
 * train on, and personally-trained models were cut from the scope — so this
 * derives the same taxonomy from the transient tier instead:
 *
 * - **DROP** — a drop just fired, or energy is holding high since one did.
 * - **BUILDUP** — energy and onset density climbing together while the low end
 *   stays suppressed; the classic filtered riser.
 * - **BREAKDOWN** — energy well below its trailing mean after having been high.
 * - **OUTRO** — the same, but sustained long past a breakdown's usual length.
 * - **INTRO** — quiet, sparse, and near the start of the session.
 *
 * State changes are debounced the same way genre switching is: a candidate has
 * to hold for [TransientConfig.sectionDebounceSeconds] before it is committed,
 * so a single quiet bar does not read as a breakdown. A drop is the exception —
 * it commits immediately, because a drop *is* the evidence, and lighting that
 * waits a second and a half for it has already missed it.
 *
 * Pure and synchronous; one instance per capture session.
 */
class SectionStateTracker(
    private val config: TransientConfig = TransientConfig()
) {
    private var committed = SectionState.UNKNOWN
    private var committedAtNanos = 0L

    private var candidate = SectionState.UNKNOWN
    private var candidateSinceNanos = 0L

    private var sessionStartNanos = Long.MIN_VALUE
    private var lastDropNanos = Long.MIN_VALUE
    private var lastHighEnergyNanos = Long.MIN_VALUE
    private var everHighEnergy = false

    /** Agreement over the debounce window, for [SectionStateUpdate.confidence]. */
    private var agreeingSamples = 0
    private var totalSamples = 0

    val state: SectionState get() = committed

    /**
     * Feeds one stats-rate snapshot.
     *
     * @param drop the drop that fired on this same sample, if any.
     * @return an update when the committed state changed, otherwise null. The
     *   ramp value moves continuously during a build-up, so callers that animate
     *   it should read [currentUpdate] every sample rather than only on changes.
     */
    fun update(snapshot: TransientSnapshot, drop: DropEvent? = null): SectionStateUpdate? {
        if (sessionStartNanos == Long.MIN_VALUE) sessionStartNanos = snapshot.timestampNanos
        if (snapshot.rmsZ > HIGH_ENERGY_Z) {
            lastHighEnergyNanos = snapshot.timestampNanos
            everHighEnergy = true
        }

        if (drop != null) {
            lastDropNanos = drop.timestampNanos
            return commit(SectionState.DROP, snapshot.timestampNanos, confidence = 1f)
        }

        val proposed = classify(snapshot)
        totalSamples++
        if (proposed == committed) agreeingSamples++

        if (proposed != candidate) {
            candidate = proposed
            candidateSinceNanos = snapshot.timestampNanos
        }

        if (proposed == committed) return null

        val heldSeconds = (snapshot.timestampNanos - candidateSinceNanos) / NANOS_PER_SECOND
        if (heldSeconds < config.sectionDebounceSeconds) return null

        // Confidence is how cleanly the new state won: a candidate that had to
        // fight the incumbent for the whole window is reported as less certain.
        val disagreement = if (totalSamples == 0) 0f else agreeingSamples.toFloat() / totalSamples
        val confidence = (1f - disagreement).coerceIn(0.25f, 1f)
        agreeingSamples = 0
        totalSamples = 0
        return commit(proposed, snapshot.timestampNanos, confidence)
    }

    /**
     * The current state with a live [SectionStateUpdate.ramp] — call this every
     * sample when driving a continuous modulation such as a build-up strobe.
     */
    fun currentUpdate(nowNanos: Long): SectionStateUpdate =
        SectionStateUpdate(
            state = committed,
            confidence = 1f,
            timestampNanos = nowNanos,
            ramp = rampAt(nowNanos)
        )

    fun reset() {
        committed = SectionState.UNKNOWN
        committedAtNanos = 0L
        candidate = SectionState.UNKNOWN
        candidateSinceNanos = 0L
        sessionStartNanos = Long.MIN_VALUE
        lastDropNanos = Long.MIN_VALUE
        lastHighEnergyNanos = Long.MIN_VALUE
        everHighEnergy = false
        agreeingSamples = 0
        totalSamples = 0
    }

    private fun classify(snapshot: TransientSnapshot): SectionState {
        if (!snapshot.warm) return SectionState.UNKNOWN

        val now = snapshot.timestampNanos
        val sinceDrop = secondsSince(lastDropNanos, now)
        if (sinceDrop < config.dropHoldSeconds && snapshot.rmsZ > -0.2f) {
            return SectionState.DROP
        }

        val quiet = snapshot.rmsZ <= config.breakdownRmsZ
        if (quiet) {
            val sinceHigh = secondsSince(lastHighEnergyNanos, now)
            return when {
                !everHighEnergy && secondsSince(sessionStartNanos, now) < config.introSeconds ->
                    SectionState.INTRO
                sinceHigh >= config.outroSeconds -> SectionState.OUTRO
                else -> SectionState.BREAKDOWN
            }
        }

        // A build-up climbs on two axes at once — more events per second and more
        // energy — while the low end stays out of the way. Any one of those alone
        // is just a busy bar or a bass-heavy groove.
        val busier = snapshot.onsetDensityZ > 0.3f
        val louder = snapshot.rmsZ > 0.1f
        val bassHeldBack = snapshot.bassZ < snapshot.rmsZ - BASS_SUPPRESSION_MARGIN
        if (busier && louder && bassHeldBack) return SectionState.BUILDUP

        // Steady full-energy playing reads as the drop section; the taxonomy has
        // no separate "groove" state and this is what the lighting profiles treat
        // as the default full-intensity mode.
        if (snapshot.rmsZ > -0.2f && everHighEnergy) return SectionState.DROP

        return if (committed == SectionState.UNKNOWN) SectionState.INTRO else committed
    }

    private fun commit(
        state: SectionState,
        nowNanos: Long,
        confidence: Float
    ): SectionStateUpdate? {
        if (state == committed) return null
        committed = state
        committedAtNanos = nowNanos
        candidate = state
        candidateSinceNanos = nowNanos
        return SectionStateUpdate(state, confidence, nowNanos, rampAt(nowNanos))
    }

    /** Build-up progress, 0 at the start of the section and 1 at its expected top. */
    private fun rampAt(nowNanos: Long): Float {
        if (committed != SectionState.BUILDUP) return 0f
        val elapsed = secondsSince(committedAtNanos, nowNanos)
        return (elapsed / config.buildupRampSeconds).coerceIn(0f, 1f)
    }

    private fun secondsSince(thenNanos: Long, nowNanos: Long): Float {
        if (thenNanos == Long.MIN_VALUE) return Float.MAX_VALUE
        return abs(nowNanos - thenNanos) / NANOS_PER_SECOND
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** RMS z-score that counts as "the track is properly playing". */
        const val HIGH_ENERGY_Z = 0.5f

        /** How far below the broadband level the bass must sit to read as filtered. */
        const val BASS_SUPPRESSION_MARGIN = 0.3f
    }
}
