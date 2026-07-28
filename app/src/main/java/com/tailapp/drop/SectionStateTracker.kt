package com.tailapp.drop

import kotlin.math.abs
import kotlin.math.exp

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
 * ## Why there is an absolute-ish presence test here at all
 *
 * Every *level* threshold in this tier is a z-score against a 45 s trailing
 * window, which is what lets one set of constants work in a quiet living room
 * and in a loud club. It is also, on its own, unable to tell "the music stopped"
 * from "the music is steady", because a z-score has no zero: leave a phone
 * running after a set and the window turns over to the room's noise floor, the
 * z-scores climb back to ~0, and the steady-energy branch below commits to
 * [SectionState.DROP] permanently — full-intensity lighting in a silent room,
 * indefinitely. So presence is judged separately, on the raw RMS against a
 * *slow* peak of what this session has actually heard, the same primitive
 * `ParticleFilterBeatDecoder` uses for its silence gate. The peak decays over
 * minutes rather than seconds precisely so that it does not re-normalise onto
 * the noise floor and undo the test.
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
    private var lastAudibleNanos = Long.MIN_VALUE
    private var everHighEnergy = false

    /** Envelope of the raw broadband level, and the slow peak it is judged against. */
    private var audioLevel = 0f
    private var audioPeak = 0f
    private val audioRelease = exp(-1f / (AUDIO_RELEASE_SECONDS * config.statsRateHz))
    private val audioPeakDecay = exp(-1f / (AUDIO_PEAK_SECONDS * config.statsRateHz))

    /** Whether the last snapshot carried actual audio, as opposed to a scale-free 0. */
    var isAudible: Boolean = false
        private set

    /**
     * Broadband level over roughly a build-up's length, so the build-up test can
     * ask whether energy has genuinely *risen* rather than whether it currently
     * sits above a mean that rose with it.
     */
    private val energyWindow = RollingStats(
        (config.buildupRampSeconds * config.statsRateHz).toInt().coerceAtLeast(2)
    )
    private val energyRecentSamples =
        (ENERGY_RECENT_SECONDS * config.statsRateHz).toInt().coerceAtLeast(1)

    /**
     * Proposals over the last couple of debounce windows, for
     * [SectionStateUpdate.confidence].
     *
     * A ring rather than a running count, because the counters it replaces were
     * measuring the wrong thing twice over: they counted agreement with the
     * *outgoing* state, so a transition after a long stable stretch reported
     * minimum confidence precisely because the old state had been solid, and they
     * were only ever cleared on a commit, so the window they averaged over was
     * the whole session.
     */
    private val proposalRing = arrayOfNulls<SectionState>(
        (CONFIDENCE_WINDOW_FACTOR * config.sectionDebounceSeconds * config.statsRateHz)
            .toInt().coerceAtLeast(2)
    )
    private var proposalIndex = 0
    private var proposalCount = 0

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

        trackPresence(snapshot)
        energyWindow.add(snapshot.rms)

        if (isAudible && snapshot.rmsZ > HIGH_ENERGY_Z) {
            lastHighEnergyNanos = snapshot.timestampNanos
            everHighEnergy = true
        }
        // "The set is over" has to be forgettable, or the first loud minute of a
        // session keeps the tracker eligible for DROP for as long as the process
        // lives. Sustained *inaudibility* is the right clock for that: a quiet
        // breakdown is still audio, an empty room is not.
        if (secondsSince(lastAudibleNanos, snapshot.timestampNanos) >= config.outroSeconds) {
            everHighEnergy = false
        }

        if (drop != null) {
            lastDropNanos = drop.timestampNanos
            recordProposal(SectionState.DROP)
            return commit(SectionState.DROP, snapshot.timestampNanos, confidence = 1f)
        }

        val proposed = classify(snapshot)
        recordProposal(proposed)

        if (proposed != candidate) {
            candidate = proposed
            candidateSinceNanos = snapshot.timestampNanos
        }

        if (proposed == committed) return null

        val heldSeconds = (snapshot.timestampNanos - candidateSinceNanos) / NANOS_PER_SECOND
        if (heldSeconds < config.sectionDebounceSeconds) return null

        return commit(proposed, snapshot.timestampNanos, confidenceFor(proposed))
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
        lastAudibleNanos = Long.MIN_VALUE
        everHighEnergy = false
        audioLevel = 0f
        audioPeak = 0f
        isAudible = false
        energyWindow.reset()
        proposalRing.fill(null)
        proposalIndex = 0
        proposalCount = 0
    }

    // --- presence -------------------------------------------------------------

    private fun trackPresence(snapshot: TransientSnapshot) {
        audioLevel = maxOf(snapshot.rms, audioLevel * audioRelease)
        audioPeak = maxOf(audioLevel, audioPeak * audioPeakDecay)
        isAudible = audioLevel > ABSOLUTE_SILENCE && audioLevel > audioPeak * PRESENCE_FRACTION
        if (isAudible) lastAudibleNanos = snapshot.timestampNanos
    }

    // --- classification -------------------------------------------------------

    private fun classify(snapshot: TransientSnapshot): SectionState {
        if (!snapshot.warm) return SectionState.UNKNOWN

        val now = snapshot.timestampNanos
        val sinceDrop = secondsSince(lastDropNanos, now)
        if (sinceDrop < config.dropHoldSeconds && isAudible && snapshot.rmsZ > STEADY_ENERGY_Z) {
            return SectionState.DROP
        }

        // Inaudible counts as quiet whatever the z-score says: after the music
        // stops the trailing window turns over to the noise floor and rmsZ climbs
        // back to zero, which is the whole reason this branch cannot be z-only.
        val quiet = !isAudible || snapshot.rmsZ <= config.breakdownRmsZ
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
        // [TransientConfig.buildupRiseRatio] is the *entry* criterion; a riser
        // that has reached its plateau has stopped rising by definition, and
        // demanding the rise continue would drop the state a bar before the drop
        // it is announcing. Once in, busy-and-filtered is enough to stay in.
        val busier = snapshot.onsetDensityZ > BUSIER_ONSET_Z
        val rising = energyRise() >= config.buildupRiseRatio
        val bassHeldBack = snapshot.bassZ < snapshot.rmsZ - BASS_SUPPRESSION_MARGIN
        if (busier && bassHeldBack && (rising || committed == SectionState.BUILDUP)) {
            return SectionState.BUILDUP
        }

        // Steady full-energy playing reads as the drop section; the taxonomy has
        // no separate "groove" state and this is what the section modulator
        // treats as the default full-intensity mode.
        if (snapshot.rmsZ > STEADY_ENERGY_Z && everHighEnergy) return SectionState.DROP

        return if (committed == SectionState.UNKNOWN) SectionState.INTRO else committed
    }

    /**
     * How far broadband energy has climbed over the build-up window: the recent
     * second or so against everything before it. 1 is flat.
     */
    private fun energyRise(): Float {
        val before = energyWindow.meanBefore(
            skip = energyRecentSamples,
            n = energyWindow.windowSize
        )
        if (before <= EPSILON) return 1f
        return energyWindow.recentMean(energyRecentSamples) / before
    }

    // --- commit ---------------------------------------------------------------

    private fun recordProposal(state: SectionState) {
        proposalRing[proposalIndex] = state
        proposalIndex = (proposalIndex + 1) % proposalRing.size
        if (proposalCount < proposalRing.size) proposalCount++
    }

    /**
     * How cleanly the *candidate* won its window — the fraction of recent
     * proposals that agreed with it. A candidate that had to fight for the whole
     * window is reported as less certain than one that swept it.
     */
    private fun confidenceFor(state: SectionState): Float {
        if (proposalCount == 0) return 1f
        var agreeing = 0
        for (i in 0 until proposalCount) {
            if (proposalRing[i] == state) agreeing++
        }
        return (agreeing.toFloat() / proposalCount).coerceIn(MIN_CONFIDENCE, 1f)
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
        // The window that produced this decision has been spent; the next one
        // starts from the evidence that arrives after the change.
        proposalRing.fill(null)
        proposalIndex = 0
        proposalCount = 0
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
        const val EPSILON = 1e-6f

        /** RMS z-score that counts as "the track is properly playing". */
        const val HIGH_ENERGY_Z = 0.5f

        /** RMS z-score at or above which energy counts as still being up. */
        const val STEADY_ENERGY_Z = -0.2f

        /** Onset-density z-score above which a passage counts as busy. */
        const val BUSIER_ONSET_Z = 0.3f

        /** How far below the broadband level the bass must sit to read as filtered. */
        const val BASS_SUPPRESSION_MARGIN = 0.3f

        /** The "now" side of the build-up rise ratio. */
        const val ENERGY_RECENT_SECONDS = 1.5f

        /**
         * A sample is audible when its level is at least this fraction of the
         * session's peak. Relative, so it holds at any mic gain.
         */
        const val PRESENCE_FRACTION = 0.1f

        /** Release of the presence envelope; bridges the dip between hits. */
        const val AUDIO_RELEASE_SECONDS = 1f

        /**
         * Decay of the peak the presence test measures against — minutes, not
         * seconds. A fast-decaying peak re-normalises onto the room's noise floor
         * within a few seconds of the music stopping and then calls it audible
         * again, which is exactly the failure this test exists to prevent. The
         * cost is that a genuine 20 dB drop held for minutes reads as silence,
         * which is a trade worth making.
         */
        const val AUDIO_PEAK_SECONDS = 90f

        /** Below this the input is silence in any room, whatever the peak says. */
        const val ABSOLUTE_SILENCE = 1e-5f

        /** Debounce windows of proposals the confidence is averaged over. */
        const val CONFIDENCE_WINDOW_FACTOR = 2f

        /** Floor on the reported confidence; a committed state is never "no idea". */
        const val MIN_CONFIDENCE = 0.25f
    }
}
