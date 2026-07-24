package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Running tempo estimate from the beat-activation envelope.
 *
 * Autocorrelation over a few seconds of activation, scored with a small comb of
 * harmonics and a prior over plausible tempi, re-evaluated a few times a second.
 *
 * Three details do most of the work:
 *
 * **Parabolic interpolation.** At 50 frames a second the lag grid is coarse
 * where it matters — around 128 BPM one frame of lag is worth about 5 BPM, so
 * the raw peak could never meet a ±2 BPM target. Interpolating the peak against
 * its neighbours recovers sub-frame precision.
 *
 * **A prior, not a rule, against octave errors.** Half and double tempo are
 * genuinely periodic in the signal, so no amount of correlation distinguishes
 * them. A log-normal prior centred where people actually tap breaks the tie
 * without hard-coding a range that would then be wrong for drum and bass.
 *
 * **Hysteresis.** A new candidate has to beat the incumbent by a margin before
 * the estimate jumps, so a moment of ambiguity does not make the reported tempo
 * flicker between a value and its double.
 *
 * @param config front-end geometry — lags are in frames, so this converts.
 * @param minBpm slowest tempo considered.
 * @param maxBpm fastest tempo considered.
 * @param historySeconds activation history the autocorrelation runs over.
 */
class TempoEstimator(
    private val config: FeatureConfig = FeatureConfig(),
    private val minBpm: Float = 60f,
    private val maxBpm: Float = 200f,
    historySeconds: Float = 8f,
    private val octaveBias: OctaveBias = OctaveBias()
) {
    private val framesPerSecond = config.framesPerSecond
    private val historySize = (historySeconds * framesPerSecond).toInt().coerceAtLeast(64)
    private val history = FloatArray(historySize)

    /**
     * The history unwrapped oldest-first. Filled once per estimate so the
     * correlation's inner loop reads a plain array instead of paying two modulo
     * operations per sample — at ~40k reads an estimate, that is the difference
     * between free and noticeable.
     */
    private val linearHistory = FloatArray(historySize)
    private var writeIndex = 0
    private var framesSeen = 0L

    private val minLag = (framesPerSecond * 60f / maxBpm).toInt().coerceAtLeast(2)
    private val maxLag = (framesPerSecond * 60f / minBpm).roundToInt().coerceAtMost(historySize / 2)

    private var framesUntilUpdate = 0

    /** Beat period in frames, or 0 before the first estimate. */
    var periodFrames: Float = 0f
        private set

    /** Peak sharpness relative to the rest of the correlation, `0..1`. */
    var confidence: Float = 0f
        private set

    /** Current tempo estimate in BPM, or 0 before the first estimate. */
    val bpm: Float get() = if (periodFrames <= 0f) 0f else framesPerSecond * 60f / periodFrames

    val hasEstimate: Boolean get() = periodFrames > 0f

    private var currentScore = 0f

    /** Per-lag scores from the latest estimate; reused so an estimate allocates nothing. */
    private val scores = FloatArray(maxLag + 2)

    /**
     * Feeds one activation value.
     *
     * @return true when this call produced a fresh estimate. Most calls do not:
     *   the correlation is re-run a few times a second, not every frame.
     */
    fun update(activation: Float): Boolean {
        history[writeIndex] = activation
        writeIndex = (writeIndex + 1) % historySize
        framesSeen++

        if (framesUntilUpdate > 0) {
            framesUntilUpdate--
            return false
        }
        if (framesSeen < minimumFramesToEstimate) return false

        framesUntilUpdate = updateIntervalFrames
        estimate()
        return true
    }

    /**
     * Lets the beat tracker refine the period from the intervals it actually
     * observed. Correlation gets the period roughly right; the spacing of real
     * beats over many bars gets it precisely right, and a small pull is enough to
     * keep long sessions from drifting.
     */
    fun refinePeriod(observedPeriodFrames: Float) {
        if (!hasEstimate || observedPeriodFrames <= 0f) return
        val ratio = observedPeriodFrames / periodFrames
        // Only trust an observation that agrees with the current estimate; a wild
        // one means the tracker lost the beat, not that the tempo changed.
        if (ratio < 1f - REFINE_TOLERANCE || ratio > 1f + REFINE_TOLERANCE) return
        periodFrames += REFINE_ALPHA * (observedPeriodFrames - periodFrames)
    }

    fun reset() {
        history.fill(0f)
        writeIndex = 0
        framesSeen = 0
        framesUntilUpdate = 0
        periodFrames = 0f
        confidence = 0f
        currentScore = 0f
    }

    private val updateIntervalFrames: Int
        get() = (framesPerSecond * UPDATE_INTERVAL_SECONDS).toInt().coerceAtLeast(1)

    private val minimumFramesToEstimate: Int
        get() = (framesPerSecond * MINIMUM_HISTORY_SECONDS).toInt()

    private fun estimate() {
        val available = minOf(framesSeen, historySize.toLong()).toInt()
        if (available <= maxLag + 2) return

        // Mean-remove: a constant offset correlates with everything and would
        // flatten the peak we are looking for.
        val start = (writeIndex - available + historySize) % historySize
        var sum = 0.0
        for (i in 0 until available) {
            val value = history[(start + i) % historySize]
            linearHistory[i] = value
            sum += value
        }
        val mean = (sum / available).toFloat()

        var bestLag = -1
        var bestScore = Float.NEGATIVE_INFINITY
        var scoreSum = 0.0
        var scoreCount = 0

        scores.fill(0f)
        for (lag in minLag..maxLag) {
            var score = 0f
            var weightSum = 0f
            for ((harmonic, weight) in HARMONIC_WEIGHTS.withIndex()) {
                val harmonicLag = lag * (harmonic + 1)
                if (harmonicLag > available - 2) break
                score += weight * correlation(harmonicLag, available, mean)
                weightSum += weight
            }
            if (weightSum > 0f) score /= weightSum
            score *= tempoPrior(framesPerSecond * 60f / lag)

            scores[lag] = score
            scoreSum += score
            scoreCount++
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        if (bestLag < 0 || scoreCount == 0) return

        val averageScore = (scoreSum / scoreCount).toFloat()
        val candidatePeriod = interpolatePeak(scores, bestLag)
        val candidateConfidence = if (averageScore <= 0f) {
            0f
        } else {
            ((bestScore / averageScore - 1f) / CONFIDENCE_SPAN).coerceIn(0f, 1f)
        }

        if (!hasEstimate) {
            periodFrames = candidatePeriod
            confidence = candidateConfidence
            currentScore = bestScore
            return
        }

        confidence = candidateConfidence
        val drift = abs(candidatePeriod - periodFrames) / periodFrames
        when {
            // Same tempo, slightly refined: follow it smoothly.
            drift < CONTINUITY_TOLERANCE -> {
                periodFrames += CONTINUITY_ALPHA * (candidatePeriod - periodFrames)
                currentScore = bestScore
            }
            // A different tempo has to clearly out-argue the incumbent.
            bestScore > currentScore * SWITCH_MARGIN -> {
                periodFrames = candidatePeriod
                currentScore = bestScore
            }
            else -> currentScore = currentScore * SCORE_DECAY + bestScore * (1f - SCORE_DECAY)
        }
    }

    /** Normalised autocorrelation of the history at [lag]. */
    private fun correlation(lag: Int, available: Int, mean: Float): Float {
        var acc = 0.0
        val pairs = available - lag
        if (pairs <= 0) return 0f
        for (i in 0 until pairs) {
            acc += (linearHistory[i] - mean).toDouble() * (linearHistory[i + lag] - mean)
        }
        return (acc / pairs).toFloat()
    }

    /**
     * Log-normal prior over tempo. Gentle on purpose: it should break a tie
     * between a tempo and its double, not veto an unusual but genuine one. The
     * built-in centre keeps the estimate in a plausible range; when the user has
     * set an [OctaveBias], its preference is layered on top, so a set of fast
     * music can be steered to stay fast without touching this default.
     */
    private fun tempoPrior(candidateBpm: Float): Float {
        val octaves = ln(candidateBpm / PRIOR_CENTRE_BPM) / LN_2
        val base = exp(-0.5f * (octaves / PRIOR_WIDTH_OCTAVES) * (octaves / PRIOR_WIDTH_OCTAVES))
        return base * octaveBias.weight(candidateBpm)
    }

    /** Sub-frame peak position by fitting a parabola through the peak's neighbours. */
    private fun interpolatePeak(scores: FloatArray, peak: Int): Float {
        if (peak <= minLag || peak >= maxLag) return peak.toFloat()
        val left = scores[peak - 1]
        val centre = scores[peak]
        val right = scores[peak + 1]
        val denominator = left - 2f * centre + right
        if (abs(denominator) < EPSILON) return peak.toFloat()
        val offset = 0.5f * (left - right) / denominator
        return peak + offset.coerceIn(-0.5f, 0.5f)
    }

    private companion object {
        const val UPDATE_INTERVAL_SECONDS = 0.2f
        const val MINIMUM_HISTORY_SECONDS = 3f

        /** Weights for the beat period and its first two multiples. */
        val HARMONIC_WEIGHTS = floatArrayOf(1f, 0.5f, 0.25f)

        const val PRIOR_CENTRE_BPM = 120f
        const val PRIOR_WIDTH_OCTAVES = 0.8f
        val LN_2 = ln(2f)

        /** Peak-to-average ratio that maps to full confidence. */
        const val CONFIDENCE_SPAN = 3f

        /** Within this relative distance, a candidate is "the same tempo". */
        const val CONTINUITY_TOLERANCE = 0.06f
        const val CONTINUITY_ALPHA = 0.3f

        /** How much better a different tempo must score before the estimate jumps. */
        const val SWITCH_MARGIN = 1.15f
        const val SCORE_DECAY = 0.8f

        /** Bounds on the tracker-observed period the estimator will accept. */
        const val REFINE_TOLERANCE = 0.08f
        const val REFINE_ALPHA = 0.1f

        const val EPSILON = 1e-9f
    }
}
