package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import kotlin.math.abs

/**
 * Beat and downbeat decoder: the MVP the project plan calls for, kept honest
 * about what it is.
 *
 * A phase-locked predictor rather than a search. It holds a beat period (from
 * [TempoEstimator]) and a phase, predicts where the next beat falls, and pulls
 * that prediction towards onsets that land near it. That is enough to track
 * steady dance music well and to recover from a tempo change within a few bars.
 * It is *not* the particle-filter cascade BeatNet uses, which reasons over many
 * competing hypotheses at once; that decoder now exists alongside it as
 * [ParticleFilterBeatDecoder], behind the shared [BeatDecoder] contract. This one
 * remains the default — see `BeatDecoderComparisonTest` for the measured
 * head-to-head that says why.
 *
 * **Beats are emitted slightly early, with their true timestamp.** Lighting has
 * to cross a BLE link and a 30 fps render loop, so a beat discovered at the
 * moment it happens is already late. The tracker emits up to
 * [LOOKAHEAD_SECONDS] ahead of the predicted instant and stamps the event with
 * that instant, not with "now" — so a renderer can hold the flash until the beat
 * actually lands, and a negative trigger offset can genuinely fire before it.
 *
 * Pure and synchronous: [process] is the whole API, and every test drives it
 * frame by frame with no dispatcher in sight. It comes in the two forms
 * [BeatDecoder] requires — the one-argument form runs the frame through the
 * [ActivationSource] this tracker owns, the two-argument form takes an activation
 * computed elsewhere so two decoders can be driven from one curve.
 */
class BeatTracker(
    private val config: FeatureConfig = FeatureConfig(),
    octaveBias: OctaveBias = OctaveBias(),
    private val activationSource: ActivationSource = SpectralFluxActivationSource(config),
    private val tempo: TempoEstimator = TempoEstimator(config, octaveBias = octaveBias)
) : BeatDecoder {
    private val framesPerSecond = config.framesPerSecond
    private val hopNanos: Long = (1_000_000_000.0 / framesPerSecond).toLong()
    private val lookaheadFrames = LOOKAHEAD_SECONDS * framesPerSecond
    private val silenceFrames = (SILENCE_SECONDS * framesPerSecond).toInt().coerceAtLeast(1)

    private var frameIndex = 0L
    private var lastTimestampNanos = 0L

    // Three-frame window for causal peak picking.
    private var activationTwoAgo = 0f
    private var activationOneAgo = 0f

    private var hasPhase = false
    private var nextBeatFrame = 0.0
    private var beatInBar = 0

    /** Per-bar-position downbeat evidence; the winner is the downbeat. */
    private val barScores = FloatArray(BEATS_PER_BAR)

    /** Beats emitted ahead of time, awaiting their moment so they can be scored. */
    private val pendingBeats = ArrayDeque<PendingBeat>()

    private var lastMatchedOnsetFrame = -1.0
    private var beatsSinceMatchedOnset = 0

    private var quietFrames = 0
    private var lastPhaseError = 0f

    /**
     * Recent activations, indexed by frame, so a matured beat can be compared
     * against what the signal was doing half a beat away from it.
     */
    private val activationHistory = FloatArray(ACTIVATION_HISTORY)

    /** The same, for the low-band channel that decides which beat starts the bar. */
    private val downbeatHistory = FloatArray(ACTIVATION_HISTORY)

    /** Bar position currently accepted as the downbeat, and its challenger. */
    private var acceptedDownbeat = 0
    private var challengerDownbeat = -1
    private var challengerBeats = 0

    /** Evidence for the current phase, and for the one half a beat off it. */
    private var onPhaseScore = 0f
    private var offPhaseScore = 0f
    private var beatsSinceFlip = 0

    private class PendingBeat(val frame: Double, val position: Int)

    /**
     * Current tempo estimate in BPM, or 0 when the tracker is not locked on.
     *
     * Gated on the phase lock, not merely on the tempo estimator having *ever*
     * produced a number: [BeatDecoder.bpm]'s contract is "0 when there is no
     * lock", which [ParticleFilterBeatDecoder] honours, and reporting the last
     * tempo of the last track forever through silence is how a monitor ends up
     * showing 128 BPM in an empty room.
     */
    override val bpm: Float get() = if (!hasPhase) 0f else tempo.bpm

    /**
     * The tempo estimator's own reading, whether or not a phase has been
     * acquired — a diagnostic, not part of [BeatDecoder].
     *
     * It exists because "the tempo was right, the tracker just never cleared the
     * confidence gate that lets it take a phase" is a real and distinguishable
     * failure, and [bpm]'s contract deliberately cannot express it.
     */
    internal val tempoBpm: Float get() = tempo.bpm

    /** `0..1`, combining tempo-peak sharpness with how well the phase is holding. */
    override val confidence: Float
        get() = if (!hasPhase) 0f else (tempo.confidence * phaseQuality()).coerceIn(0f, 1f)

    /** Wall-clock time of the next predicted beat, or null when not locked on. */
    override val nextBeatTimestampNanos: Long?
        get() = if (!hasPhase) null else frameToNanos(nextBeatFrame)

    /**
     * Feeds one feature frame, computing its activation with the tracker's own
     * [ActivationSource].
     */
    override fun process(frame: FeatureFrame): List<BeatEvent> =
        process(frame, activationSource.activation(frame))

    /**
     * Feeds one feature frame together with a pre-computed activation.
     *
     * @return the beats this frame produced — usually empty, occasionally one,
     *   and more than one only when the frame rate stumbled badly enough that a
     *   predicted beat was skipped over.
     */
    override fun process(frame: FeatureFrame, activation: BeatActivation): List<BeatEvent> {
        frameIndex++
        lastTimestampNanos = frame.timestampNanos

        tempo.update(activation.beat)
        val slot = (frameIndex % ACTIVATION_HISTORY).toInt()
        activationHistory[slot] = activation.beat
        downbeatHistory[slot] = activation.downbeat

        scorePendingBeats()

        // Causal peak picking: the frame *before* this one is a peak if it stands
        // above both neighbours. Costs one frame (20 ms) of latency, which the
        // lookahead on emission more than covers.
        val onsetFrame = if (
            activationOneAgo > activationTwoAgo &&
            activationOneAgo >= activation.beat &&
            activationOneAgo >= ONSET_MIN_ACTIVATION
        ) {
            (frameIndex - 1).toDouble()
        } else {
            null
        }
        activationTwoAgo = activationOneAgo
        activationOneAgo = activation.beat

        trackSilence(activation.beat)

        if (!hasPhase) {
            tryAcquirePhase(onsetFrame)
            return emptyList()
        }
        if (onsetFrame != null) correctPhase(onsetFrame)

        return emitDueBeats()
    }

    override fun reset() {
        activationSource.reset()
        tempo.reset()
        frameIndex = 0
        lastTimestampNanos = 0
        activationTwoAgo = 0f
        activationOneAgo = 0f
        hasPhase = false
        nextBeatFrame = 0.0
        beatInBar = 0
        barScores.fill(0f)
        pendingBeats.clear()
        activationHistory.fill(0f)
        downbeatHistory.fill(0f)
        acceptedDownbeat = 0
        challengerDownbeat = -1
        challengerBeats = 0
        lastMatchedOnsetFrame = -1.0
        beatsSinceMatchedOnset = 0
        quietFrames = 0
        lastPhaseError = 0f
        onPhaseScore = 0f
        offPhaseScore = 0f
        beatsSinceFlip = 0
    }

    // --- phase ---

    private fun tryAcquirePhase(onsetFrame: Double?) {
        if (onsetFrame == null) return
        if (!tempo.hasEstimate || tempo.confidence < MIN_ACQUIRE_CONFIDENCE) return

        // Lock on to the onset itself: it is the best evidence available of where
        // a beat is, and the correction loop refines from there.
        hasPhase = true
        nextBeatFrame = onsetFrame + tempo.periodFrames
        beatInBar = 0
        lastMatchedOnsetFrame = onsetFrame
        beatsSinceMatchedOnset = 0
        lastPhaseError = 0f
        onPhaseScore = 0f
        offPhaseScore = 0f
        beatsSinceFlip = 0
    }

    /**
     * Pulls the predicted beat towards an onset that landed near it, and feeds
     * the observed spacing back into the tempo estimate.
     *
     * Onsets far from the prediction are ignored rather than chased: at any real
     * tempo most onsets are off-beat notes, and following them would drag the
     * phase onto the syncopation.
     */
    private fun correctPhase(onsetFrame: Double) {
        val period = tempo.periodFrames
        if (period < MIN_PERIOD_FRAMES) return

        var error = onsetFrame - nextBeatFrame
        // Fold into ±half a period: an onset a beat and a half away is really
        // half a beat away from a *different* beat.
        while (error > period / 2) error -= period
        while (error < -period / 2) error += period

        if (abs(error) > period * CAPTURE_WINDOW) return

        nextBeatFrame += PHASE_ALPHA * error
        lastPhaseError = (abs(error) / period).toFloat()

        // Spacing between two onsets that both matched a beat is a direct
        // measurement of the period, averaged over however many beats separate
        // them — far more precise than the correlation's lag grid.
        if (lastMatchedOnsetFrame >= 0 && beatsSinceMatchedOnset > 0) {
            val observed = (onsetFrame - lastMatchedOnsetFrame) / beatsSinceMatchedOnset
            tempo.refinePeriod(observed.toFloat())
        }
        lastMatchedOnsetFrame = onsetFrame
        beatsSinceMatchedOnset = 0
    }

    /**
     * How well the phase is holding, `0..1`.
     *
     * Two terms, because [lastPhaseError] alone is a *stale* measurement: it is
     * only written when an onset lands inside the capture window, so through a
     * long pad section with no onsets at all it keeps reporting the quality of
     * the last hit before the pads started. Ageing it out by how many beats have
     * been emitted since the last correction is what makes the confidence fall
     * when the tracker is coasting rather than tracking.
     */
    private fun phaseQuality(): Float {
        val freshness = 1f - (beatsSinceMatchedOnset / PHASE_STALENESS_BEATS.toFloat()).coerceIn(0f, 1f)
        return ((1f - lastPhaseError * 2f) * freshness).coerceIn(0f, 1f)
    }

    // --- emission ---

    private fun emitDueBeats(): List<BeatEvent> {
        val period = tempo.periodFrames
        if (period < MIN_PERIOD_FRAMES) return emptyList()

        var events: MutableList<BeatEvent>? = null
        var emitted = 0
        while (frameIndex + lookaheadFrames >= nextBeatFrame && emitted < MAX_BEATS_PER_FRAME) {
            val downbeatPosition = downbeatPosition()
            val isDownbeat = beatInBar == downbeatPosition
            // Re-base to the bar so the event honours BeatEvent's contract
            // (beatInBar 0 is the downbeat) and matches ParticleFilterBeatDecoder.
            // `beatInBar` itself stays the raw phase cycle: barScores and
            // downbeatPosition() are both indexed in that un-rebased space.
            val barPosition = (beatInBar - downbeatPosition + BEATS_PER_BAR) % BEATS_PER_BAR
            val event = BeatEvent(
                type = if (isDownbeat) BeatType.DOWNBEAT else BeatType.BEAT,
                timestampNanos = frameToNanos(nextBeatFrame),
                bpm = tempo.bpm,
                beatInBar = barPosition,
                confidence = confidence
            )
            (events ?: mutableListOf<BeatEvent>().also { events = it }).add(event)

            pendingBeats.addLast(PendingBeat(nextBeatFrame, beatInBar))
            nextBeatFrame += period
            beatInBar = (beatInBar + 1) % BEATS_PER_BAR
            beatsSinceMatchedOnset++
            emitted++
        }
        return events ?: emptyList()
    }

    /**
     * Scores bar positions once their beat has actually arrived.
     *
     * Beats are emitted early, so the downbeat evidence for one does not exist
     * yet at emission time; each is parked here until the frame it predicted
     * comes around, then credited with the low-band activation observed there.
     */
    private fun scorePendingBeats() {
        while (pendingBeats.isNotEmpty() && pendingBeats.first().frame <= frameIndex) {
            val beat = pendingBeats.removeFirst()
            for (i in barScores.indices) barScores[i] *= BAR_SCORE_DECAY
            // Sample a frame either side: a kick whose peak lands a frame off the
            // predicted beat is still that beat's kick.
            barScores[beat.position] += sample(downbeatHistory, beat.frame)
            judgePhase(beat.frame)
        }
    }

    /**
     * Keeps the tracker from settling on the off-beat.
     *
     * Off-beat eighths are just as periodic as the beats themselves, so phase
     * correction alone is perfectly happy locked half a beat wrong — and on
     * music with a strong upbeat that is where a naive tracker lands. Each beat
     * that matures is compared against what the activation was doing half a
     * period away from it; when the alternative has been consistently stronger,
     * the phase flips.
     *
     * The comparison samples a one-frame neighbourhood, because a peak that is
     * a frame off is still that beat's peak.
     */
    private fun judgePhase(beatFrame: Double) {
        val period = tempo.periodFrames
        if (period < MIN_PERIOD_FRAMES) return

        onPhaseScore = onPhaseScore * PHASE_SCORE_DECAY + sample(activationHistory, beatFrame)
        offPhaseScore = offPhaseScore * PHASE_SCORE_DECAY + sample(activationHistory, beatFrame - period / 2.0)
        beatsSinceFlip++

        if (beatsSinceFlip < MIN_BEATS_BETWEEN_FLIPS) return
        if (offPhaseScore <= onPhaseScore * PHASE_FLIP_MARGIN) return

        nextBeatFrame -= period / 2.0
        // Half a period back can land the "next" beat at or before the current
        // frame, and emitDueBeats would then emit an event stamped in the past —
        // a flash the renderer can only show late. Step forward to the first
        // crossing that is still ahead of us.
        while (nextBeatFrame < frameIndex) {
            nextBeatFrame += period
            beatInBar = (beatInBar + 1) % BEATS_PER_BAR
        }
        // The alternative becomes the incumbent; starting both scores from zero
        // stops a marginal decision from oscillating every few beats.
        onPhaseScore = 0f
        offPhaseScore = 0f
        beatsSinceFlip = 0
    }

    /** Peak value within a frame either side of [frame], or 0 if it has aged out. */
    private fun sample(history: FloatArray, frame: Double): Float {
        val centre = Math.round(frame)
        if (centre < 0 || centre > frameIndex || frameIndex - centre >= ACTIVATION_HISTORY - 2) return 0f
        var best = 0f
        for (offset in -1..1) {
            val index = centre + offset
            if (index < 0 || index > frameIndex) continue
            val value = history[(index % ACTIVATION_HISTORY).toInt()]
            if (value > best) best = value
        }
        return best
    }

    /**
     * Bar position carrying the most low-band weight — the downbeat.
     *
     * A bare argmax wobbles: four positions accumulate similar evidence and the
     * leader changes on noise. But a *margin* would be worse — it cements
     * whichever position happened to lead first, and an early mistake would then
     * never be corrected. So a challenger has to lead for several consecutive
     * bars instead, which filters noise without making the decision permanent.
     */
    private fun downbeatPosition(): Int {
        var best = 0
        for (i in 1 until BEATS_PER_BAR) {
            if (barScores[i] > barScores[best]) best = i
        }

        when {
            best == acceptedDownbeat -> {
                challengerDownbeat = -1
                challengerBeats = 0
            }
            best == challengerDownbeat -> {
                if (++challengerBeats >= DOWNBEAT_SWITCH_BEATS) {
                    acceptedDownbeat = best
                    challengerDownbeat = -1
                    challengerBeats = 0
                }
            }
            else -> {
                challengerDownbeat = best
                challengerBeats = 1
            }
        }
        return acceptedDownbeat
    }

    // --- housekeeping ---

    /**
     * Drops the phase after a long silent stretch. Holding a lock through the
     * silence would have the tracker emit confident beats into a gap and then
     * resume a bar behind whatever comes next.
     */
    private fun trackSilence(activation: Float) {
        if (activation > SILENCE_ACTIVATION) {
            quietFrames = 0
            return
        }
        quietFrames++
        if (quietFrames < silenceFrames) return

        hasPhase = false
        pendingBeats.clear()
        barScores.fill(0f)
        // Drop the *tempo* too, exactly once as the threshold is crossed. A gap
        // this long is a new piece of music, not a bar's rest, and a retained
        // period both misreports the BPM through the silence and biases the next
        // track's acquisition towards the last one's tempo. Once, not every
        // frame: reset() refills the history and would otherwise never let the
        // estimator accumulate the 3 s it needs to speak again.
        if (quietFrames == silenceFrames) {
            tempo.reset()
            lastMatchedOnsetFrame = -1.0
            beatsSinceMatchedOnset = 0
            lastPhaseError = 0f
        }
    }

    private fun frameToNanos(frame: Double): Long =
        lastTimestampNanos + ((frame - frameIndex) * hopNanos).toLong()

    private companion object {
        /** How far ahead of a predicted beat its event is emitted. */
        const val LOOKAHEAD_SECONDS = 0.05f

        /** Activation a peak must reach to count as an onset. */
        const val ONSET_MIN_ACTIVATION = 0.35f

        /** Tempo confidence required before the tracker will lock on at all. */
        const val MIN_ACQUIRE_CONFIDENCE = 0.15f

        /** Onsets within this fraction of a period of the prediction correct it. */
        const val CAPTURE_WINDOW = 0.25f

        /** How hard a matching onset pulls the phase. Low enough to ignore one bad hit. */
        const val PHASE_ALPHA = 0.25

        const val BEATS_PER_BAR = 4

        /** Bar evidence fades so a changed bar phase can win within a few bars. */
        const val BAR_SCORE_DECAY = 0.97f

        /** Below this activation the input counts as silent. */
        const val SILENCE_ACTIVATION = 0.05f
        const val SILENCE_SECONDS = 2f

        /** Guards the emission loop against a degenerate period. */
        const val MIN_PERIOD_FRAMES = 4f

        /** A stalled frame stream must not produce a burst of catch-up beats. */
        const val MAX_BEATS_PER_FRAME = 2

        /** Frames of activation kept for phase comparison — over a second at 50 fps. */
        const val ACTIVATION_HISTORY = 128

        const val PHASE_SCORE_DECAY = 0.85f

        /** How much better the alternative phase must be before the tracker flips. */
        const val PHASE_FLIP_MARGIN = 1.25f

        /** Beats to wait after a flip, so a marginal call cannot oscillate. */
        const val MIN_BEATS_BETWEEN_FLIPS = 8

        /**
         * Beats without a matching onset after which the phase measurement is
         * considered worthless. Two bars: long enough for a sparse breakdown,
         * short enough that a pad section stops reporting a stale certainty.
         */
        const val PHASE_STALENESS_BEATS = 8

        /** Consecutive beats a challenger bar position must lead before it takes over. */
        const val DOWNBEAT_SWITCH_BEATS = 4
    }
}
