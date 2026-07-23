package com.tailapp.drop

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import kotlin.math.ln

/**
 * A stats-rate view of what the audio is doing, in units the section tracker and
 * the monitoring UI can both use.
 *
 * @param timestampNanos end of the interval this summarises.
 * @param rms mean broadband RMS over the interval.
 * @param bass mean low-band energy.
 * @param centroidHz mean spectral centroid.
 * @param rmsZ / [bassZ] / [centroidZ] z-scores against the trailing window.
 * @param onsetDensity onsets per second over the recent window.
 * @param onsetDensityZ z-score of that rate.
 * @param bassRise short-term bass level over its preceding reference level; 1
 *   means flat, above 1 means rising.
 * @param warm whether the trailing window holds enough history to be trusted.
 */
data class TransientSnapshot(
    val timestampNanos: Long,
    val rms: Float,
    val bass: Float,
    val centroidHz: Float,
    val rmsZ: Float,
    val bassZ: Float,
    val centroidZ: Float,
    val onsetDensity: Float,
    val onsetDensityZ: Float,
    val bassRise: Float,
    val warm: Boolean
)

/**
 * One completed stats-rate evaluation.
 *
 * @param snapshot what the audio was doing over the interval.
 * @param drop the drop that fired on it, if any. [DropEvent.precededBy] is left
 *   as [SectionState.UNKNOWN] here — [TransientAnalyzer] fills it in, since it
 *   is the piece that owns the section state.
 */
data class TransientSample(
    val snapshot: TransientSnapshot,
    val drop: DropEvent?
)

/**
 * Fast transient statistics and drop detection, running at
 * [TransientConfig.statsRateHz] on the same feature frames the beat tier uses.
 *
 * A drop is a *joint* spike: broadband energy and low-band energy both well
 * above their trailing means, with the bass making a genuine step rather than a
 * slow swell. Requiring both is what separates a drop from a cymbal crash or a
 * vocal shout, and the rise ratio is what separates it from a long filter sweep
 * that was already loud.
 *
 * Everything is measured against the detector's own trailing window, so it
 * adapts to the room and the source level with no calibration.
 *
 * This class is pure and synchronous — one instance per capture session, driven
 * frame by frame. It knows nothing about sections; [TransientAnalyzer] stitches
 * the two together.
 */
class TransientDetector(
    private val config: TransientConfig = TransientConfig(),
    featureConfig: FeatureConfig = FeatureConfig()
) {
    /** Feature frames averaged into one stats sample. */
    private val framesPerSample: Int =
        (featureConfig.framesPerSecond / config.statsRateHz).toInt().coerceAtLeast(1)

    private val rmsStats = RollingStats(config.historySamples)
    private val bassStats = RollingStats(config.historySamples)
    private val centroidStats = RollingStats(config.historySamples)
    private val onsetRateStats = RollingStats(config.historySamples)

    /** Flux is judged at frame rate, so onsets keep their timing resolution. */
    private val fluxStats = RollingStats((featureConfig.framesPerSecond * FLUX_WINDOW_SECONDS).toInt())

    private var framesInSample = 0
    private var rmsAccumulator = 0.0
    private var bassAccumulator = 0.0
    private var centroidAccumulator = 0.0

    /** Onset counts per stats sample, for the sliding onset-density window. */
    private val onsetCounts = IntArray(config.onsetWindowSamples)
    private var onsetCountIndex = 0
    private var onsetsInSample = 0

    private var lastDropNanos = Long.MIN_VALUE

    var snapshot: TransientSnapshot = EMPTY_SNAPSHOT
        private set

    /**
     * Feeds one feature frame.
     *
     * @return the completed [TransientSample] when this frame ended a stats-rate
     *   interval, otherwise null. Most calls return null: the detector only
     *   evaluates once per [TransientConfig.statsRateHz] interval.
     */
    fun process(frame: FeatureFrame): TransientSample? {
        // Onsets are counted at frame rate: a flux spike well above its recent
        // mean is a note attack, and their rate is what rises through a build-up.
        if (fluxStats.isWarm(MIN_FLUX_SAMPLES) && fluxStats.zScore(frame.flux) >= config.onsetFluxZ) {
            onsetsInSample++
        }
        fluxStats.add(frame.flux)

        rmsAccumulator += frame.rms
        bassAccumulator += frame.bassEnergy
        centroidAccumulator += frame.spectralCentroidHz
        framesInSample++

        if (framesInSample < framesPerSample) return null
        return completeSample(frame.timestampNanos)
    }

    fun reset() {
        rmsStats.reset()
        bassStats.reset()
        centroidStats.reset()
        onsetRateStats.reset()
        fluxStats.reset()
        onsetCounts.fill(0)
        onsetCountIndex = 0
        onsetsInSample = 0
        framesInSample = 0
        rmsAccumulator = 0.0
        bassAccumulator = 0.0
        centroidAccumulator = 0.0
        lastDropNanos = Long.MIN_VALUE
        snapshot = EMPTY_SNAPSHOT
    }

    private fun completeSample(timestampNanos: Long): TransientSample {
        val rms = (rmsAccumulator / framesInSample).toFloat()
        val bass = (bassAccumulator / framesInSample).toFloat()
        val centroid = (centroidAccumulator / framesInSample).toFloat()

        onsetCounts[onsetCountIndex] = onsetsInSample
        onsetCountIndex = (onsetCountIndex + 1) % onsetCounts.size
        val onsetsInWindow = onsetCounts.sum()
        val onsetDensity = onsetsInWindow / config.onsetDensityWindowSeconds

        framesInSample = 0
        onsetsInSample = 0
        rmsAccumulator = 0.0
        bassAccumulator = 0.0
        centroidAccumulator = 0.0

        // Score against history *before* folding this sample in, so a sample is
        // never compared against a window that already contains it.
        val warm = rmsStats.isWarm(config.warmupSamples)
        val rmsZ = rmsStats.zScore(rms)
        val bassZ = bassStats.zScore(bass)
        val centroidZ = centroidStats.zScore(centroid)
        val onsetDensityZ = onsetRateStats.zScore(onsetDensity)

        // A step is a change that happens *fast*. Comparing against the last few
        // hundred milliseconds — not against the last few seconds — is what tells
        // a drop apart from a swell that reaches the same level over half a minute.
        val justBefore = bassStats.recentMean(SHORT_SAMPLES)
        val bassRise = if (justBefore > EPSILON) bass / justBefore else 1f
        val rmsBaseline = rmsStats.mean

        rmsStats.add(rms)
        bassStats.add(bass)
        centroidStats.add(centroid)
        onsetRateStats.add(onsetDensity)

        snapshot = TransientSnapshot(
            timestampNanos = timestampNanos,
            rms = rms,
            bass = bass,
            centroidHz = centroid,
            rmsZ = rmsZ,
            bassZ = bassZ,
            centroidZ = centroidZ,
            onsetDensity = onsetDensity,
            onsetDensityZ = onsetDensityZ,
            bassRise = bassRise,
            warm = warm
        )

        return TransientSample(
            snapshot,
            detectDrop(timestampNanos, rms, rmsZ, bassZ, bassRise, justBefore, rmsBaseline, warm)
        )
    }

    private fun detectDrop(
        timestampNanos: Long,
        rms: Float,
        rmsZ: Float,
        bassZ: Float,
        bassRise: Float,
        bassJustBefore: Float,
        rmsBaseline: Float,
        warm: Boolean
    ): DropEvent? {
        if (!warm) return null

        val refractoryNanos = (config.dropRefractorySeconds * NANOS_PER_SECOND).toLong()
        if (lastDropNanos != Long.MIN_VALUE && timestampNanos - lastDropNanos < refractoryNanos) {
            return null
        }

        val spikes = rmsZ >= config.dropBroadbandZ && bassZ >= config.dropBassZ
        val stepped = bassJustBefore <= EPSILON || bassRise >= config.dropBassRiseRatio
        if (!spikes || !stepped) return null

        lastDropNanos = timestampNanos
        // Intensity is how far above the *typical* level this landed, on a log
        // scale so a doubling reads the same whether the track is quiet or loud.
        val levelRise = if (rmsBaseline > EPSILON) rms / rmsBaseline else config.dropIntensityRatio
        val intensity = if (levelRise <= 1f) {
            0f
        } else {
            (ln(levelRise) / ln(config.dropIntensityRatio)).coerceIn(0f, 1f)
        }

        return DropEvent(
            timestampNanos = timestampNanos,
            intensity = intensity,
            broadbandZ = rmsZ,
            bassZ = bassZ,
            precededBy = SectionState.UNKNOWN
        )
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val EPSILON = 1e-6f

        /** Flux history for the onset threshold. Long enough to span a bar or two. */
        const val FLUX_WINDOW_SECONDS = 3f
        const val MIN_FLUX_SAMPLES = 25

        /** ~0.3 s of stats samples: the "just before" side of the bass step test. */
        const val SHORT_SAMPLES = 3

        val EMPTY_SNAPSHOT = TransientSnapshot(
            timestampNanos = 0L,
            rms = 0f, bass = 0f, centroidHz = 0f,
            rmsZ = 0f, bassZ = 0f, centroidZ = 0f,
            onsetDensity = 0f, onsetDensityZ = 0f, bassRise = 1f,
            warm = false
        )
    }
}
