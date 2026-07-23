package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * BeatNet's two-stage cascade particle filter, ported to Kotlin.
 *
 * The MVP decoder ([BeatTracker]) holds exactly one hypothesis — one tempo, one
 * phase — and nudges it towards onsets. This one holds [DEFAULT_BEAT_PARTICLES]
 * of them at once and lets the activation curve decide which survive. That is the whole point:
 * when the evidence is ambiguous (a sparse pattern, a syncopated bar, a bar of
 * rubato) a single hypothesis has to commit and can only be wrong, whereas a
 * particle cloud can carry both readings until the music resolves them.
 *
 * Reference: Heydari, Cornelius & Duan, *BeatNet: CRNN and Particle Filtering for
 * Online Joint Beat, Downbeat and Meter Tracking* (ISMIR 2021), and its
 * `particle_filtering_cascade.py`. The observation and transition models are
 * madmom's bar-pointer models (Whiteley/Böck/Krebs), which BeatNet inherits.
 *
 * ## Stage one — beat particle filter
 *
 * **State.** Particle `i` carries `(phase_i, period_i)` and a weight.
 * `phase_i ∈ [0,1)` is the beat pointer's position within the current beat
 * interval, 0 being the beat instant; `period_i` is the beat interval in frames.
 *
 * *Deviation from the reference, deliberately:* BeatNet inherits madmom's
 * **discretised** state space — `num_tempi` (300) log-spaced tempo states, each
 * with an integer position grid, giving ~10⁵ states that must be enumerated and
 * tabulated up front. The continuous `(phase, period)` pair above is the same
 * model in the limit of infinite discretisation, and it costs two floats per
 * particle instead of a 10⁵-entry transition table. On a phone that matters; the
 * particle filter never needed the table because it only ever touches the states
 * its particles occupy.
 *
 * **Transition.** Per frame the pointer advances `1/period`. A tempo change is
 * only proposed when the pointer crosses a beat boundary — madmom's
 * `BeatTransitionModel` forbids mid-beat tempo changes and so does this — and
 * the proposal is a two-way mixture:
 *
 * | branch | probability | effect |
 * |---|---|---|
 * | local walk | the remainder | `period × exp(Laplace(0, 1/λ_t))` — madmom's `exponential_transition`, made symmetric in log-period (see [TEMPO_TRANSITION_LAMBDA]) |
 * | rejuvenation | [TEMPO_JUMP_PROBABILITY] | a fresh draw from the prior, phase included |
 *
 * Only the first is in the reference. The second exists because a *finite* cloud,
 * unlike madmom's exhaustively enumerated state space, can run out of particles at
 * the tempo the music just changed to — and the local walk crawls, so it cannot
 * cross a valley to get back.
 *
 * A third branch was tried and removed: a *metrical* jump, multiplying the period
 * by 1/2, 2/3, 3/2 or 2 while keeping the beat instant. It is the tempting
 * proposal, because a beat tracker's errors are almost all metrical rather than
 * uniformly spread, so a jump that lands exactly on the rival should be worth
 * thirty uniform draws. Measured, it made things three to eight times worse:
 * across 112 runs, failures went from 1-5 to 17-29 at a 1% jump rate and to 84 at
 * 8%. The reason is the property that made it attractive. A rejuvenated particle
 * is almost always implausible and dies within a beat, so it costs nothing; a
 * metrically jumped one *fits* — that is what a metrical ambiguity is — so it
 * survives, and the cloud settles with real mass at half and double time. In a
 * filter whose observation cannot separate two hypotheses, do not spend the
 * transition kernel moving mass between them.
 *
 * A small Gaussian *roughening* is added to the phase every frame. The reference
 * does not need it: its discrete state space is so large that resampled
 * duplicates land on distinct states. A continuous filter without it suffers
 * textbook particle impoverishment — resampled duplicates are bit-identical and
 * evolve identically, so the cloud collapses to a single hypothesis and can never
 * recover. The width is quoted in *frames*, not in phase units, so it means the
 * same amount of micro-timing at every tempo.
 *
 * **Observation.** madmom's `RNNBeatTrackingObservationModel`: the first
 * `1/λ_o` of each beat interval is the "beat window", and
 *
 * ```
 * p(obs | phase in window)  = activation
 * p(obs | phase outside)    = (1 − activation) / (λ_o − 1)
 * ```
 *
 * with one correction that turned out to be load-bearing. madmom evaluates that
 * test per discrete state, so a hypothesis's window covers `ceil(period/λ_o)`
 * whole frames — which **grows relative to the beat as the period shrinks**, and
 * therefore systematically rewards fast hypotheses. Measured on the synthetic
 * grids, the raw test makes double-tempo out-score the truth at 90 BPM by 3.4
 * nats per beat. Weighting each frame by the *fraction of its hop* that falls
 * inside the window restores the continuous-time model, where the in-window mass
 * per unit time is `1/λ_o` for every tempo; with that, the truth wins at 90, 125
 * and 174 BPM. It is the same likelihood, integrated properly.
 *
 * The likelihood is then raised to the power [OBSERVATION_TEMPERATURE]. That
 * leaves every hypothesis's *total* score scaled by the same factor, so the
 * model's ranking is untouched; what changes is how fast the filter commits to
 * it, and without it the cloud collapses long before the tempo dimension has been
 * resolved. The constant carries the measurement.
 *
 * BeatNet's `ig_threshold` (skip the update when the activation is below 0.4) is
 * *not* ported. With a CRNN activation, sub-threshold values are model noise and
 * skipping them is right. With this project's z-scored spectral flux the
 * sub-threshold values are the most informative part of the curve — "nothing
 * happened here" is precisely what pins the phase, and it is also what keeps the
 * in-window mass per unit time equal across tempi — so the update is skipped only
 * during genuine silence (see [SILENCE_HOLD_SECONDS]).
 *
 * **Resampling.** Systematic resampling when the effective sample size
 * `1/Σw²` falls below half the particle count.
 *
 * **Emission.** The winning hypothesis is the mode of the *joint* (phase,
 * log-period) posterior, found on a weighted 2-D histogram — a rectangular-kernel
 * KDE where BeatNet uses a Gaussian one — and refined by averaging the winning
 * cell's 3x3 neighbourhood. A beat is emitted when that hypothesis is within
 * [LOOKAHEAD_SECONDS] of crossing phase 1 → 0, stamped with the crossing instant,
 * exactly as [BeatTracker] does. See [summarisePosterior] for why the period axis
 * cannot be marginalised away.
 *
 * ## Stage two — bar particle filter
 *
 * Conditioned on stage one, as the cascade requires: it only steps when a
 * stage-one beat *matures* (its predicted frame arrives, so the low-band
 * activation at that instant is finally observable).
 *
 * **State.** Particle `j` carries a bar offset `o_j ∈ 0..B-1`; the beat with
 * index `k` is at bar position `(k + o_j) mod B`, and is a downbeat when that is
 * zero. **Transition:** a small per-beat slip probability of ±1, which is what
 * lets the bar phase be re-learnt after stage one drops or inserts a beat.
 * **Observation:** the discrete form of madmom's
 * `RNNDownBeatTrackingObservationModel` — `p = d` for a particle claiming this
 * beat is the downbeat, `(1 − d)/(B − 1)` for the others, `d` being the downbeat
 * activation sampled at the beat.
 *
 * For `B = 4` this state space has only four states, so the particle
 * approximation is converging on exact forward filtering; the particles earn
 * their keep only once the meter is unknown too (BeatNet infers 3 vs 4), which is
 * why the shape is kept rather than collapsed into a four-bin histogram.
 *
 * ## Cost
 *
 * Linear in the particle count and nothing worse. Per frame, per beat particle:
 * ~35 flops, three transcendentals (`exp` for the likelihood, `cos`/`sin` for the
 * circular mean) and half a Gaussian draw; resampling adds another `O(N)` pass on
 * the frames it fires, and the mode search is a fixed
 * `O(PHASE_BINS × PERIOD_BINS)`. Stage two is `O(M)` per *beat*, not per frame.
 *
 * `ParticleFilterBeatDecoderTest` measures and prints the wall time, and asserts
 * the linearity. Measured on a desktop JVM: **29 µs per frame at 512 particles,
 * 135 µs at the default 1536, 347 µs at 4096** — about 88 ns per particle per
 * frame, so the default is roughly 0.7% of the 20 ms frame budget here and a few
 * percent of it on a phone. That is two orders of magnitude more work than
 * [BeatTracker], which is the honest price of the comparison.
 *
 * Nothing in the per-frame path allocates: every array is preallocated,
 * resampling double-buffers, and the pending-beat queue is a fixed ring. The only
 * allocations are the [BeatEvent]s themselves, at ~2 per second, which the same
 * suite asserts.
 *
 * ## Determinism
 *
 * Every random draw comes from one seeded [Random]. The same seed and the same
 * frames give byte-identical output, which is what makes the comparison harness
 * and the regression tests meaningful.
 *
 * @param config front-end geometry; periods are in frames, so this converts.
 * @param activationSource used only by the single-argument [process]; the
 *   two-argument form bypasses it, which is how the comparison harness guarantees
 *   both decoders see the identical curve.
 * @param beatParticleCount stage-one particles.
 * @param barParticleCount stage-two particles.
 * @param beatsPerBar meter. Fixed rather than inferred; the state space
 *   generalises but nothing in this project asks for 3/4 yet.
 * @param minBpm slowest tempo the filter can represent.
 * @param maxBpm fastest tempo the filter can represent.
 * @param observationTemperature likelihood exponent; see [OBSERVATION_TEMPERATURE].
 * @param jumpProbability per-beat rejuvenation rate; see [TEMPO_JUMP_PROBABILITY].
 * @param seed seed for every random draw.
 */
class ParticleFilterBeatDecoder(
    private val config: FeatureConfig = FeatureConfig(),
    private val activationSource: ActivationSource = SpectralFluxActivationSource(config),
    private val beatParticleCount: Int = DEFAULT_BEAT_PARTICLES,
    private val barParticleCount: Int = DEFAULT_BAR_PARTICLES,
    private val beatsPerBar: Int = DEFAULT_BEATS_PER_BAR,
    minBpm: Float = MIN_BPM,
    maxBpm: Float = MAX_BPM,
    private val observationTemperature: Float = OBSERVATION_TEMPERATURE,
    private val jumpProbability: Float = TEMPO_JUMP_PROBABILITY,
    private val seed: Long = DEFAULT_SEED
) : BeatDecoder {
    init {
        require(beatParticleCount >= 16) { "need at least 16 beat particles" }
        require(barParticleCount >= beatsPerBar) { "need at least one bar particle per bar position" }
        require(beatsPerBar >= 2) { "beatsPerBar must be at least 2" }
        require(minBpm > 0f && maxBpm > minBpm) { "need 0 < minBpm < maxBpm" }
    }

    private val framesPerSecond = config.framesPerSecond
    private val hopNanos: Long = (1_000_000_000.0 / framesPerSecond).toLong()
    private val lookaheadFrames = LOOKAHEAD_SECONDS * framesPerSecond

    private val minPeriodFrames = framesPerSecond * 60f / maxBpm
    private val maxPeriodFrames = framesPerSecond * 60f / minBpm
    private val lnMinPeriod = ln(minPeriodFrames)
    private val lnPeriodSpan = ln(maxPeriodFrames) - lnMinPeriod

    private val silenceHoldFrames = (SILENCE_HOLD_SECONDS * framesPerSecond).toInt()
    private val silenceDropFrames = (SILENCE_SECONDS * framesPerSecond).toInt()
    private val warmupFrames = (WARMUP_SECONDS * framesPerSecond).toInt()

    private var random = Random(seed)

    // --- stage one: beat particles -------------------------------------------

    private var phase = FloatArray(beatParticleCount)
    private var period = FloatArray(beatParticleCount)

    /**
     * `ln(period)`, carried alongside it rather than recomputed. The mode finder
     * bins on log-period every frame; the period only *changes* at a beat
     * boundary, so caching turns one `ln` per particle per frame into one per
     * particle per beat — measured, about a fifth of the whole per-frame cost.
     */
    private var logPeriod = FloatArray(beatParticleCount)

    private var phaseScratch = FloatArray(beatParticleCount)
    private var periodScratch = FloatArray(beatParticleCount)
    private var logPeriodScratch = FloatArray(beatParticleCount)
    private val weight = FloatArray(beatParticleCount)

    /** Per-particle `cos`/`sin` of the phase angle, so the cluster pass is free of them. */
    private val phaseCos = FloatArray(beatParticleCount)
    private val phaseSin = FloatArray(beatParticleCount)

    /**
     * Joint (phase, log-period) histogram — the mode finder's kernel. Indexed
     * `periodBin * PHASE_BINS + phaseBin`; the phase axis is circular.
     */
    private val cellWeight = FloatArray(PHASE_BINS * PERIOD_BINS)

    // --- stage two: bar particles --------------------------------------------

    private val barOffset = IntArray(barParticleCount)
    private val barOffsetScratch = IntArray(barParticleCount)
    private val barWeight = FloatArray(barParticleCount)
    private val barPositionWeight = FloatArray(beatsPerBar)
    private var barOffsetMode = 0

    // --- timeline ------------------------------------------------------------

    private var frameIndex = 0L
    private var lastTimestampNanos = 0L
    private var beatIndex = 0L

    /** Low-band activation, kept so a matured beat can be scored at its instant. */
    private val downbeatHistory = FloatArray(ACTIVATION_HISTORY)

    /** Beats emitted ahead of time, waiting for their frame so stage two can score them. */
    private val pendingFrame = DoubleArray(PENDING_CAPACITY)
    private val pendingBeatIndex = LongArray(PENDING_CAPACITY)
    private var pendingHead = 0
    private var pendingCount = 0

    private var lastEmittedBeatFrame = Double.NEGATIVE_INFINITY

    // --- posterior summary ---------------------------------------------------

    private var modePhase = 0f
    private var modePeriod = 0f

    /** Resultant length of the phase posterior, `0..1`: the filter's own certainty. */
    private var concentration = 0f

    private var hasLock = false
    private var quietFrames = 0
    private var activeFrames = 0

    /** Spare normal deviate from the last Marsaglia polar draw. */
    private var spareGaussian = Float.NaN

    init {
        initialiseParticles()
        initialiseBarParticles()
    }

    override val bpm: Float
        get() = if (!hasLock || modePeriod < MIN_PERIOD_FRAMES) 0f else framesPerSecond * 60f / modePeriod

    override val confidence: Float
        get() = if (!hasLock) 0f else concentration.coerceIn(0f, 1f)

    override val nextBeatTimestampNanos: Long?
        get() {
            if (!hasLock || modePeriod < MIN_PERIOD_FRAMES) return null
            var next = frameIndex + (1.0 - modePhase) * modePeriod
            // The one just emitted is no longer "next"; report the one after it.
            if (next - lastEmittedBeatFrame < modePeriod * MIN_BEAT_SPACING) next += modePeriod
            return frameToNanos(next)
        }

    override fun process(frame: FeatureFrame): List<BeatEvent> =
        process(frame, activationSource.activation(frame))

    override fun process(frame: FeatureFrame, activation: BeatActivation): List<BeatEvent> {
        frameIndex++
        lastTimestampNanos = frame.timestampNanos
        downbeatHistory[(frameIndex % ACTIVATION_HISTORY).toInt()] = activation.downbeat

        // Stage two first: a beat whose instant has arrived can finally be scored,
        // and the bar phase it produces is what the next emission will use.
        matureBeats()

        predict()

        trackSilence(activation.beat)
        if (quietFrames <= silenceHoldFrames) {
            correct(activation.beat)
            resampleIfDepleted()
        }

        summarisePosterior()
        updateLock()

        return if (hasLock) emitDueBeat() else emptyList()
    }

    override fun reset() {
        activationSource.reset()
        random = Random(seed)
        spareGaussian = Float.NaN
        initialiseParticles()
        initialiseBarParticles()
        frameIndex = 0
        lastTimestampNanos = 0
        beatIndex = 0
        downbeatHistory.fill(0f)
        pendingHead = 0
        pendingCount = 0
        lastEmittedBeatFrame = Double.NEGATIVE_INFINITY
        modePhase = 0f
        modePeriod = 0f
        concentration = 0f
        hasLock = false
        quietFrames = 0
        activeFrames = 0
    }

    // --- initialisation ------------------------------------------------------

    /**
     * Spreads the cloud over the prior: uniform in phase, log-uniform in tempo.
     *
     * **Stratified**, not independently sampled. Independent draws over a 2-D
     * prior leave visible gaps — and a gap costs seconds of convergence, because
     * the filter cannot resample its way to a hypothesis no particle holds.
     * Stratifying guarantees an even grid (at the default cloud size, 39 tempo
     * strata across 55-215 BPM — 3.5% steps, which the per-beat tempo diffusion
     * closes within a couple of bars — and 40 phase strata, a little over half a
     * frame of a 128 BPM beat each), and the per-cell jitter keeps it from being
     * a lattice.
     */
    private fun initialiseParticles() {
        val n = beatParticleCount
        val phaseStrata = ceil(sqrt(n.toFloat())).toInt()
        val tempoStrata = ceil(n.toFloat() / phaseStrata).toInt()

        for (i in 0 until n) {
            val phaseCell = i % phaseStrata
            val tempoCell = i / phaseStrata
            phase[i] = (phaseCell + random.nextFloat()) / phaseStrata
            val u = (tempoCell + random.nextFloat()) / tempoStrata
            logPeriod[i] = lnMinPeriod + u * lnPeriodSpan
            period[i] = exp(logPeriod[i])
            weight[i] = 1f / n
        }
        modePhase = 0f
        modePeriod = 0f
        concentration = 0f
    }

    private fun initialiseBarParticles() {
        for (j in 0 until barParticleCount) {
            barOffset[j] = j % beatsPerBar
            barWeight[j] = 1f / barParticleCount
        }
        barOffsetMode = 0
    }

    // --- stage one -----------------------------------------------------------

    /**
     * Advances every pointer by one frame, redrawing the tempo of any particle
     * that crossed a beat boundary and roughening the phase of all of them.
     */
    private fun predict() {
        val n = beatParticleCount
        for (i in 0 until n) {
            var p = period[i]
            var f = phase[i] + 1f / p
            if (f >= 1f) {
                f -= 1f
                var lp: Float
                if (random.nextFloat() < jumpProbability) {
                    // Rejuvenation: a fresh draw from the prior, phase included.
                    lp = lnMinPeriod + random.nextFloat() * lnPeriodSpan
                    f = random.nextFloat()
                } else {
                    lp = (logPeriod[i] + laplace(TEMPO_TRANSITION_LAMBDA))
                        .coerceIn(lnMinPeriod, lnMinPeriod + lnPeriodSpan)
                }
                p = exp(lp)
                period[i] = p
                logPeriod[i] = lp
            }
            f += gaussian() * PHASE_DIFFUSION_FRAMES / p
            if (f >= 1f) f -= 1f
            if (f < 0f) f += 1f
            phase[i] = f
        }
    }

    /**
     * Reweights the cloud against this frame's beat activation and renormalises.
     *
     * The likelihood is the geometric blend of the in-window and out-of-window
     * densities, mixed by how much of the frame's hop the beat window covers —
     * i.e. the log-density integrated over the hop, which is the continuous-time
     * form of madmom's per-state test. See the class KDoc for why the discrete
     * form biases towards fast tempi.
     */
    private fun correct(activationValue: Float) {
        val n = beatParticleCount
        val a = activationValue.coerceIn(ACTIVATION_FLOOR, ACTIVATION_CEIL)
        // The out-of-window density is the same for every particle, so it cancels
        // in the normalisation below; only the in/out log-ratio discriminates.
        // Dropping it keeps the arithmetic away from the bottom of the float range.
        val logRatio = observationTemperature *
            (ln(a) - ln((1f - a) / (OBSERVATION_LAMBDA - 1f)))

        var sum = 0.0
        for (i in 0 until n) {
            val cover = windowCoverage(phase[i], period[i])
            val w = if (cover <= 0f) weight[i] else weight[i] * exp(cover * logRatio)
            weight[i] = w
            sum += w
        }

        if (sum <= 0.0 || !sum.isFinite()) {
            // Every hypothesis underflowed at once: the posterior carries no
            // information, so fall back to the prior rather than to NaN.
            weight.fill(1f / n)
            return
        }
        val scale = (1.0 / sum).toFloat()
        for (i in 0 until n) weight[i] *= scale
    }

    /**
     * Fraction of this frame's hop that falls inside the beat window `[0, 1/λ_o)`.
     *
     * The frame is treated as covering half a hop either side of its pointer
     * position — centred rather than leading, so the estimate carries no
     * half-frame bias in either direction.
     */
    private fun windowCoverage(particlePhase: Float, particlePeriod: Float): Float {
        val hop = 1f / particlePeriod
        val half = 0.5f * hop
        val low = particlePhase - half
        val high = particlePhase + half

        var covered = overlap(if (low < 0f) 0f else low, if (high > 1f) 1f else high)
        // A hop that runs past phase 1 continues into the next beat's window.
        if (high > 1f) covered += overlap(0f, high - 1f)
        val fraction = covered / hop
        return if (fraction < 0f) 0f else if (fraction > 1f) 1f else fraction
    }

    /** Length of `[low, high) ∩ [0, BEAT_WINDOW)`. */
    private fun overlap(low: Float, high: Float): Float {
        val end = if (high < BEAT_WINDOW) high else BEAT_WINDOW
        return if (end > low) end - low else 0f
    }

    private fun resampleIfDepleted() {
        val n = beatParticleCount
        var sumSquares = 0.0
        for (i in 0 until n) {
            val w = weight[i].toDouble()
            sumSquares += w * w
        }
        if (sumSquares <= 0.0) {
            weight.fill(1f / n)
            return
        }
        val effectiveSampleSize = 1.0 / sumSquares
        if (effectiveSampleSize >= n * RESAMPLE_ESS_FRACTION) return

        // Systematic resampling: one stratified draw walks the cumulative
        // distribution once, which is O(n), lower-variance than multinomial, and
        // the standard choice for exactly this reason.
        val step = 1f / n
        var cursor = random.nextFloat() * step
        var source = 0
        var cumulative = weight[0]
        for (target in 0 until n) {
            while (cursor > cumulative && source < n - 1) {
                source++
                cumulative += weight[source]
            }
            phaseScratch[target] = phase[source]
            periodScratch[target] = period[source]
            logPeriodScratch[target] = logPeriod[source]
            cursor += step
        }

        val swappedPhase = phase
        phase = phaseScratch
        phaseScratch = swappedPhase
        val swappedPeriod = period
        period = periodScratch
        periodScratch = swappedPeriod
        val swappedLogPeriod = logPeriod
        logPeriod = logPeriodScratch
        logPeriodScratch = swappedLogPeriod
        weight.fill(1f / n)
    }

    /**
     * Collapses the cloud to the hypothesis it is actually betting on.
     *
     * A plain weighted mean would be wrong whenever the posterior is multimodal —
     * and it is multimodal exactly when it matters, while the filter is deciding
     * between a tempo and its double or halfway through a tempo change. So the
     * readout is a *mode*, found on a joint histogram over (phase, log-period) —
     * a rectangular-kernel KDE where BeatNet uses a Gaussian one — smoothed over
     * a 3x3 neighbourhood, with the winning cell's neighbourhood then averaged to
     * recover sub-bin resolution.
     *
     * The period axis is not optional. Marginalising it and conditioning the
     * period on the winning *phase* bin looks equivalent and is not: every
     * metrical level agrees on where the beat is, so half, true and double tempo
     * all pile into the same phase bin and the conditional mean lands between
     * them. Measured, that readout reported tempi 5-20 BPM off whenever the cloud
     * carried any metrical ambiguity at all, with no lock ever having been lost.
     */
    private fun summarisePosterior() {
        val n = beatParticleCount
        cellWeight.fill(0f)

        var globalCos = 0f
        var globalSin = 0f
        val periodScale = PERIOD_BINS / lnPeriodSpan
        for (i in 0 until n) {
            val w = weight[i]
            val p = phase[i]
            val angle = TAU * p
            val c = cos(angle)
            val s = sin(angle)
            phaseCos[i] = c
            phaseSin[i] = s
            globalCos += w * c
            globalSin += w * s

            var phaseBin = (p * PHASE_BINS).toInt()
            if (phaseBin < 0) phaseBin = 0 else if (phaseBin >= PHASE_BINS) phaseBin = PHASE_BINS - 1
            var periodBin = ((logPeriod[i] - lnMinPeriod) * periodScale).toInt()
            if (periodBin < 0) periodBin = 0 else if (periodBin >= PERIOD_BINS) periodBin = PERIOD_BINS - 1
            cellWeight[periodBin * PHASE_BINS + phaseBin] += w
        }

        // Phase agreement across the whole cloud, which is what beat timing needs
        // and what the lock gate reads. Deliberately not conditioned on the mode:
        // a cloud split between two phases should report low confidence even when
        // one of them is winning.
        concentration = sqrt(globalCos * globalCos + globalSin * globalSin)

        var bestCell = -1
        var bestMass = -1f
        for (periodBin in 0 until PERIOD_BINS) {
            for (phaseBin in 0 until PHASE_BINS) {
                var mass = 0f
                for (dPeriod in -1..1) {
                    val q = periodBin + dPeriod
                    if (q < 0 || q >= PERIOD_BINS) continue
                    val row = q * PHASE_BINS
                    for (dPhase in -1..1) {
                        mass += cellWeight[row + (phaseBin + dPhase + PHASE_BINS) % PHASE_BINS]
                    }
                }
                if (mass > bestMass) {
                    bestMass = mass
                    bestCell = periodBin * PHASE_BINS + phaseBin
                }
            }
        }
        if (bestCell < 0) return

        // Second pass: average the cluster by *distance* from the winning cell's
        // centre rather than by bin membership. Summing whole bins would truncate
        // the cloud wherever it straddles an edge, and a truncated mean is a
        // biased one — worth up to a percent of tempo, which at 174 BPM is most of
        // the accuracy budget.
        val centrePhase = (bestCell % PHASE_BINS + 0.5f) / PHASE_BINS
        val centreLogPeriod = lnMinPeriod + (bestCell / PHASE_BINS + 0.5f) / periodScale
        var clusterCos = 0f
        var clusterSin = 0f
        var clusterPeriod = 0f
        var clusterWeight = 0f
        for (i in 0 until n) {
            var phaseDistance = phase[i] - centrePhase
            if (phaseDistance > 0.5f) phaseDistance -= 1f
            if (phaseDistance < -0.5f) phaseDistance += 1f
            if (phaseDistance > CLUSTER_PHASE_RADIUS || phaseDistance < -CLUSTER_PHASE_RADIUS) continue
            val logDistance = logPeriod[i] - centreLogPeriod
            if (logDistance > CLUSTER_LOG_RADIUS || logDistance < -CLUSTER_LOG_RADIUS) continue

            val w = weight[i]
            clusterCos += w * phaseCos[i]
            clusterSin += w * phaseSin[i]
            clusterPeriod += w * period[i]
            clusterWeight += w
        }
        if (clusterWeight <= 0f) return

        var meanPhase = atan2(clusterSin, clusterCos) / TAU
        if (meanPhase < 0f) meanPhase += 1f
        if (meanPhase >= 1f) meanPhase -= 1f
        modePhase = meanPhase
        modePeriod = clusterPeriod / clusterWeight
    }

    // --- lock ----------------------------------------------------------------

    /**
     * Tracks how long the input has been quiet.
     *
     * Two thresholds, because they answer different questions. After
     * [SILENCE_HOLD_SECONDS] the observation update is suspended — there is
     * nothing to learn from a dead frame, and letting the cloud reweight against
     * one drives it to an arbitrary hypothesis. After [SILENCE_SECONDS] the lock
     * is dropped and the cloud is re-spread, because a gap that long is a new
     * piece of music, not a bar's rest.
     */
    private fun trackSilence(activationValue: Float) {
        if (activationValue > SILENCE_ACTIVATION) {
            quietFrames = 0
            activeFrames++
            return
        }
        quietFrames++
        if (quietFrames == silenceDropFrames) {
            hasLock = false
            activeFrames = 0
            pendingHead = 0
            pendingCount = 0
            lastEmittedBeatFrame = Double.NEGATIVE_INFINITY
            initialiseParticles()
            initialiseBarParticles()
        }
    }

    /**
     * Locks on once the cloud has both had time to converge and actually
     * converged.
     *
     * Three gates, each answering something different. [ACTIVE_FRAMES_TO_LOCK]
     * counts *audible* frames, so a session that opens with a silent lead-in does
     * not spend its warm-up on it — and silence, which never produces an audible
     * frame, can therefore never reach the lock at all. [WARMUP_SECONDS] is
     * absolute and only bites once, covering the activation source's own warm-up
     * transient. The concentration gate is the filter's own posterior: while the
     * phase posterior is still spread its resultant length stays near zero, which
     * is exactly the "I do not know yet" the MVP has to approximate with a
     * tempo-confidence threshold.
     */
    private fun updateLock() {
        if (hasLock) return
        if (activeFrames < ACTIVE_FRAMES_TO_LOCK) return
        if (frameIndex < warmupFrames) return
        if (concentration < LOCK_CONCENTRATION) return
        if (modePeriod < MIN_PERIOD_FRAMES) return
        hasLock = true
    }

    // --- emission ------------------------------------------------------------

    /**
     * Emits the next beat once the winning hypothesis is within the lookahead of
     * crossing a beat boundary.
     *
     * At most one per frame: the boundary is recomputed from the posterior every
     * frame rather than counted off an internal clock, so a burst can only mean
     * the posterior jumped — and replaying a jump as a flurry of beats is worse
     * than dropping one.
     */
    private fun emitDueBeat(): List<BeatEvent> {
        if (modePeriod < MIN_PERIOD_FRAMES) return emptyList()

        val boundary = frameIndex + (1.0 - modePhase) * modePeriod
        if (frameIndex + lookaheadFrames < boundary) return emptyList()
        if (boundary - lastEmittedBeatFrame < modePeriod * MIN_BEAT_SPACING) return emptyList()

        val position = ((beatIndex + barOffsetMode) % beatsPerBar).toInt()
        val event = BeatEvent(
            type = if (position == 0) BeatType.DOWNBEAT else BeatType.BEAT,
            timestampNanos = frameToNanos(boundary),
            bpm = framesPerSecond * 60f / modePeriod,
            beatInBar = position,
            confidence = confidence
        )

        pushPending(boundary, beatIndex)
        lastEmittedBeatFrame = boundary
        beatIndex++
        return listOf(event)
    }

    private fun pushPending(frame: Double, index: Long) {
        if (pendingCount == PENDING_CAPACITY) {
            // Cannot happen with a sane frame stream (the lookahead is a handful
            // of frames); dropping the oldest beats a corrupted ring.
            pendingHead = (pendingHead + 1) % PENDING_CAPACITY
            pendingCount--
        }
        val slot = (pendingHead + pendingCount) % PENDING_CAPACITY
        pendingFrame[slot] = frame
        pendingBeatIndex[slot] = index
        pendingCount++
    }

    // --- stage two -----------------------------------------------------------

    /**
     * Feeds stage two every emitted beat whose instant has now passed.
     *
     * Beats are emitted early, so the low-band activation at the beat does not
     * exist yet at emission time. Each is parked until its frame — plus one, so
     * the whole neighbourhood the sampler looks at is available — has arrived.
     */
    private fun matureBeats() {
        while (pendingCount > 0 && pendingFrame[pendingHead] + 1.0 <= frameIndex) {
            val frame = pendingFrame[pendingHead]
            val index = pendingBeatIndex[pendingHead]
            pendingHead = (pendingHead + 1) % PENDING_CAPACITY
            pendingCount--
            observeDownbeat(index, sampleDownbeat(frame))
        }
    }

    private fun observeDownbeat(index: Long, downbeatActivation: Float) {
        val m = barParticleCount

        // Transition: the bar phase slips when stage one drops or inserts a beat.
        // Without it the cloud can collapse onto one offset and never leave it.
        for (j in 0 until m) {
            if (random.nextFloat() < BAR_SLIP_PROBABILITY) {
                val direction = if (random.nextFloat() < 0.5f) 1 else beatsPerBar - 1
                barOffset[j] = (barOffset[j] + direction) % beatsPerBar
            }
        }

        val d = downbeatActivation.coerceIn(ACTIVATION_FLOOR, ACTIVATION_CEIL)
        val other = (1f - d) / (beatsPerBar - 1)
        var sum = 0.0
        for (j in 0 until m) {
            val position = ((index + barOffset[j]) % beatsPerBar).toInt()
            val w = barWeight[j] * (if (position == 0) d else other)
            barWeight[j] = w
            sum += w
        }
        if (sum <= 0.0 || !sum.isFinite()) {
            barWeight.fill(1f / m)
        } else {
            val scale = (1.0 / sum).toFloat()
            for (j in 0 until m) barWeight[j] *= scale
        }

        resampleBarParticles()

        barPositionWeight.fill(0f)
        for (j in 0 until m) barPositionWeight[barOffset[j]] += barWeight[j]
        var best = 0
        for (o in 1 until beatsPerBar) if (barPositionWeight[o] > barPositionWeight[best]) best = o
        barOffsetMode = best
    }

    private fun resampleBarParticles() {
        val m = barParticleCount
        var sumSquares = 0.0
        for (j in 0 until m) {
            val w = barWeight[j].toDouble()
            sumSquares += w * w
        }
        if (sumSquares <= 0.0) {
            barWeight.fill(1f / m)
            return
        }
        if (1.0 / sumSquares >= m * RESAMPLE_ESS_FRACTION) return

        val step = 1f / m
        var cursor = random.nextFloat() * step
        var source = 0
        var cumulative = barWeight[0]
        for (target in 0 until m) {
            while (cursor > cumulative && source < m - 1) {
                source++
                cumulative += barWeight[source]
            }
            barOffsetScratch[target] = barOffset[source]
            cursor += step
        }
        barOffsetScratch.copyInto(barOffset)
        barWeight.fill(1f / m)
    }

    /** Peak low-band activation within a frame either side of [frame]. */
    private fun sampleDownbeat(frame: Double): Float {
        val centre = Math.round(frame)
        if (centre < 0 || centre > frameIndex || frameIndex - centre >= ACTIVATION_HISTORY - 2) return 0f
        var best = 0f
        for (offset in -1..1) {
            val index = centre + offset
            if (index < 0 || index > frameIndex) continue
            val value = downbeatHistory[(index % ACTIVATION_HISTORY).toInt()]
            if (value > best) best = value
        }
        return best
    }

    // --- random --------------------------------------------------------------

    /**
     * Marsaglia polar normal deviate. Allocates nothing, and unlike Box-Muller
     * needs no `sin`/`cos` — one `ln` and one `sqrt` per *pair*, with the spare
     * cached in a field.
     */
    private fun gaussian(): Float {
        val spare = spareGaussian
        if (!spare.isNaN()) {
            spareGaussian = Float.NaN
            return spare
        }
        var u: Float
        var v: Float
        var s: Float
        do {
            u = random.nextFloat() * 2f - 1f
            v = random.nextFloat() * 2f - 1f
            s = u * u + v * v
        } while (s >= 1f || s <= 0f)
        val factor = sqrt(-2f * ln(s) / s)
        spareGaussian = v * factor
        return u * factor
    }

    /** Laplace(0, 1/lambda): the continuous form of madmom's exponential tempo transition. */
    private fun laplace(lambda: Float): Float {
        val u = random.nextFloat().coerceIn(EPSILON, 1f - EPSILON)
        return if (u < 0.5f) ln(2f * u) / lambda else -ln(2f * (1f - u)) / lambda
    }

    private fun frameToNanos(frame: Double): Long =
        lastTimestampNanos + ((frame - frameIndex) * hopNanos).toLong()

    internal companion object {
        /**
         * Stage-one particles. BeatNet runs ~1500; this needs about the same,
         * which was a surprise and is worth being straight about.
         *
         * The hope with a continuous state space was that a few hundred would do,
         * since the particles are not obliged to tile a 10⁵-state grid. They do
         * not. Measured across 112 runs per configuration, the failure rate falls
         * steadily to about 1536 and flattens after: 512 fails 3-6% of runs, 1024
         * about 1-3%, 1536 and 2048 under 1%. The reason is not phase — 512 is
         * ample for phase — but tempo, which this front-end's activation barely
         * constrains. BeatNet's CRNN emits near-zero between beats, so a wrong
         * tempo is punished hard every frame; a z-scored spectral flux gives every
         * eighth note something, so tempo has to be inferred from agreement
         * accumulated over many beats, and that needs support spread across the
         * range for the whole time it takes.
         *
         * The cost is linear and small — see the class KDoc — so this is a cheap
         * problem to have. Halve it for a device with no headroom and expect the
         * occasional half-time lock on a tempo change.
         */
        const val DEFAULT_BEAT_PARTICLES = 1536

        /**
         * Stage-two particles. Four bar positions and a slip probability of
         * [BAR_SLIP_PROBABILITY] mean ~5 particles explore each neighbouring
         * offset per beat, which is ample to re-learn a bar phase; more only buys
         * a smoother posterior over a four-state space.
         */
        const val DEFAULT_BAR_PARTICLES = 128

        const val DEFAULT_BEATS_PER_BAR = 4

        /** Arbitrary but fixed, so the default construction is reproducible. */
        const val DEFAULT_SEED = 0x5EEDL

        /**
         * Tempo range. BeatNet's own bounds, and they do real work: they are what
         * makes 128 BPM's double (256) unrepresentable. The dangerous octave
         * errors are the ones that stay inside the range, which is why the
         * observation model has to be right rather than merely bounded.
         */
        const val MIN_BPM = 55f
        const val MAX_BPM = 215f

        /**
         * `λ_o` — the beat window is the first `1/λ_o` of each beat interval.
         * madmom's default. At 50 fps that is 1.5 frames (29 ms) at 128 BPM, which
         * matches the width of a spectral-flux onset: wide enough to catch the
         * peak and its first decay frame, narrow enough that the off-beat is
         * clearly outside.
         */
        const val OBSERVATION_LAMBDA = 16f
        const val BEAT_WINDOW = 1f / OBSERVATION_LAMBDA

        /**
         * `λ_t` — tempo transition sharpness. madmom's default, applied here as
         * `period × exp(Laplace(0, 1/λ_t))`: the tempo random-walks with a
         * standard deviation of `√2/λ_t` = 1.4% per beat. Slow enough that a
         * steady tempo does not wander, fast enough that a 25% tempo change is
         * reachable in ~15 beats — and the exponential tail means a handful of
         * particles propose a much larger jump every beat, which is what actually
         * catches an abrupt change.
         *
         * Exponentiating matters. madmom's `exponential_transition` is a density
         * over the *ratio*, which as a proposal would be `period × (1 + L)` — and
         * `E[ln(1 + L)] = −1/λ_t²` by Jensen, not zero, so the period ratchets
         * downwards by 0.01% a beat whatever the evidence says. Measured, that is
         * a +0.7% tempo bias after a minute, which at 174 BPM is 1.2 BPM of pure
         * drift. madmom does not suffer it because its transition normalises over
         * a fixed discrete set of tempo states; the continuous form has to be made
         * symmetric in log-period explicitly.
         */
        const val TEMPO_TRANSITION_LAMBDA = 100f

        /**
         * Per-beat probability that a particle abandons its hypothesis and takes
         * a fresh draw from the prior instead.
         *
         * The local walk above can only crawl; it cannot cross a valley. Once the
         * cloud has committed to a tempo there are no particles anywhere else, so
         * an abrupt change — a new track, a DJ cut, the 120-to-150 step in the
         * test suite — is unreachable no matter how much evidence accumulates.
         * That is textbook particle depletion, and rejuvenation is the textbook
         * answer: make the transition a mixture of "walk" and "restart".
         *
         * At 2%, and ~2 beat crossings per particle per second, roughly 60 of
         * 1536 particles restart each second — spread across the whole tempo
         * range, so a new tempo is proposed within a second or two and the
         * tempered observation gives it long enough to prove itself. Measured, the
         * failure rate on the tempo-change signal falls monotonically from 0.5%
         * to 2%: 36, 22 and 8 failures per 1008 runs. The cost is a few percent of
         * the cloud permanently exploring, which shows up as a small reduction in
         * confidence and nothing else.
         */
        const val TEMPO_JUMP_PROBABILITY = 0.02f

        /** Phase bins in the joint posterior histogram; circular. */
        const val PHASE_BINS = 32

        /** Log-period bins in the joint posterior histogram, spanning the tempo range. */
        const val PERIOD_BINS = 24

        /**
         * Half-width of the cluster the mode is averaged over, in phase units.
         * Matches the 3-bin search window that selected the cell.
         */
        const val CLUSTER_PHASE_RADIUS = 1.5f / PHASE_BINS

        /**
         * The same on the log-period axis: ±10%. Wide enough to hold a converged
         * cloud (which sits within about 2%) whatever bin edge it straddles,
         * narrow enough that half and double time are nowhere near it.
         */
        val CLUSTER_LOG_RADIUS = ln(1.10f)

        /**
         * Phase roughening, in frames of pointer position per frame of audio.
         * Over one 128 BPM beat this random-walks to 0.7 frames (14 ms) — the
         * order of real micro-timing, and small next to the ±70 ms accuracy
         * window. Its job is to keep resampled duplicates from being identical;
         * set it to zero and the cloud collapses within a few seconds.
         */
        const val PHASE_DIFFUSION_FRAMES = 0.15f

        /**
         * Activation clamps. An activation of exactly 1 makes the out-of-window
         * density zero, i.e. a single frame vetoes every hypothesis that
         * disagrees with it; an activation of exactly 0 does the same in reverse.
         * Clamping says the front-end is never certain, which caps the per-frame
         * log-likelihood ratio at ln(0.95 / (0.05/15)) = 5.6 nats — decisive
         * within a beat or two, but survivable by a hypothesis that is right about
         * everything else.
         */
        const val ACTIVATION_FLOOR = 0.05f
        const val ACTIVATION_CEIL = 0.95f

        /**
         * Likelihood exponent — the standard tempered particle filter.
         *
         * The untempered observation is brutally sharp: a peak frame's in-window
         * density is ~285x the out-of-window one, so a single frame drops the
         * effective sample size to a sixteenth of the cloud and resampling then
         * discards everything that was not phase-aligned *at that instant*. Phase
         * survives that, because phase is what the frame measures. Tempo does not:
         * it is only observable through phase agreement across several onsets, and
         * the cloud has already collapsed by the second one. Measured, untempered,
         * the filter commits to a tempo within four seconds and locks to 2/3 or
         * 1/2 of the truth on three of the four synthetic grids.
         *
         * Raising the likelihood to a power scales every hypothesis's total log
         * score by the same factor, so the model's *preferences are unchanged* —
         * only the rate at which the filter commits to them. 0.25 spreads the
         * evidence of one peak over roughly three beats, which is what the tempo
         * dimension needs. Swept over 112 runs per setting: below 0.15 the filter
         * stops converging inside the warm-up, above 0.4 the octave errors return,
         * and 0.25-0.35 is a flat optimum.
         */
        const val OBSERVATION_TEMPERATURE = 0.30f

        /**
         * Resample when the effective sample size drops below half the cloud.
         * The textbook threshold (Doucet & Johansen): resampling too eagerly
         * throws away diversity, too late leaves the weight on a few particles.
         */
        const val RESAMPLE_ESS_FRACTION = 0.5

        /** Per-beat probability that a bar particle slips one position. */
        const val BAR_SLIP_PROBABILITY = 0.04f

        /** How far ahead of a predicted beat its event is emitted. Matches [BeatTracker]. */
        const val LOOKAHEAD_SECONDS = 0.05f

        /** Fraction of a period two emitted beats must be apart. */
        const val MIN_BEAT_SPACING = 0.5

        /** Below this activation a frame counts as silent. Matches [BeatTracker]. */
        const val SILENCE_ACTIVATION = 0.05f

        /**
         * Quiet this long and the observation update is suspended. Longer than
         * the longest gap between onsets the filter can be tracking — at its
         * slowest representable tempo (55 BPM) a bare beat grid leaves 1.09 s —
         * so a real rest suspends the filter but ordinary music never does.
         */
        const val SILENCE_HOLD_SECONDS = 1.5f

        /** Quiet this long and the lock is dropped entirely. Matches [BeatTracker]. */
        const val SILENCE_SECONDS = 2f

        /**
         * Frames the filter runs before it is willing to emit. The cloud needs
         * roughly this long to resolve tempo from an ambiguous start, and the
         * comparison harness treats the first eight seconds as transient anyway.
         */
        const val WARMUP_SECONDS = 2.5f

        /**
         * Audible frames required before the lock can be taken. Over a beat grid
         * roughly a quarter of frames carry activation, so this is about four
         * seconds of music — and, crucially, it is never reached by silence.
         */
        const val ACTIVE_FRAMES_TO_LOCK = 50

        /**
         * Phase-posterior resultant length required to lock. 0.5 corresponds to a
         * phase spread of about a sixth of a beat; below that the filter is still
         * carrying incompatible hypotheses and any beat it emitted would be a coin
         * toss.
         */
        const val LOCK_CONCENTRATION = 0.5f

        /** Guards against a degenerate period. Matches [BeatTracker]. */
        const val MIN_PERIOD_FRAMES = 4f

        /** Frames of low-band activation kept for downbeat scoring. */
        const val ACTIVATION_HISTORY = 128

        /** Beats that can be in flight between emission and maturity. */
        const val PENDING_CAPACITY = 16

        const val TAU = (2.0 * PI).toFloat()

        const val EPSILON = 1e-7f
    }
}
