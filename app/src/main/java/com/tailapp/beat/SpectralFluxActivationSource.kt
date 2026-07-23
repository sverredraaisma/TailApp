package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import kotlin.math.sqrt

/**
 * Beat activation from spectral flux, normalised against its own recent history.
 *
 * Raw flux is useless as a probability: its scale depends on the input level, the
 * room, and how bright the material is. What is stable is *how unusual* this
 * frame's flux is compared with the last second or two, so the activation is a
 * z-score squashed into `0..1`.
 *
 * The statistics are exponential moving averages rather than a windowed buffer —
 * they adapt smoothly, cost two multiplies a frame, and have no array to walk at
 * 50 frames a second.
 *
 * The downbeat channel does the same to the *rise in low-band energy*, because
 * what distinguishes bar one in most dance music is the kick landing, not the
 * broadband event density.
 *
 * @param config the front-end geometry, used to convert the adaptation time
 *   constants from seconds to frames.
 */
class SpectralFluxActivationSource(
    private val config: FeatureConfig = FeatureConfig()
) : ActivationSource {

    private val alpha: Float = 1f / (ADAPTATION_SECONDS * config.framesPerSecond)

    private var fluxMean = 0f
    private var fluxVariance = 0f
    private var bassRiseMean = 0f
    private var bassRiseVariance = 0f

    private var previousBass = Float.NaN
    private var framesSeen = 0

    /** True once the moving statistics describe enough history to be trusted. */
    val isWarm: Boolean get() = framesSeen >= (WARMUP_SECONDS * config.framesPerSecond).toInt()

    override fun activation(frame: FeatureFrame): BeatActivation {
        val bassRise = if (previousBass.isNaN()) 0f else (frame.bassEnergy - previousBass).coerceAtLeast(0f)
        previousBass = frame.bassEnergy

        val beat = squash(frame.flux, fluxMean, fluxVariance, ACTIVATION_Z_SPAN)
        // A wider span for the low band on purpose: every beat has a kick, so
        // what picks out bar one is *how much* bigger its kick is. Saturating
        // both at 1 the way the beat channel does would throw that away.
        val downbeat = squash(bassRise, bassRiseMean, bassRiseVariance, DOWNBEAT_Z_SPAN)

        // Update *after* scoring, so a frame is never judged against statistics
        // that already contain it.
        fluxMean = updateMean(fluxMean, frame.flux)
        fluxVariance = updateVariance(fluxVariance, frame.flux, fluxMean)
        bassRiseMean = updateMean(bassRiseMean, bassRise)
        bassRiseVariance = updateVariance(bassRiseVariance, bassRise, bassRiseMean)
        framesSeen++

        if (!isWarm) return BeatActivation(0f, 0f)
        return BeatActivation(beat, downbeat)
    }

    override fun reset() {
        fluxMean = 0f
        fluxVariance = 0f
        bassRiseMean = 0f
        bassRiseVariance = 0f
        previousBass = Float.NaN
        framesSeen = 0
    }

    private fun updateMean(mean: Float, value: Float): Float = mean + alpha * (value - mean)

    private fun updateVariance(variance: Float, value: Float, mean: Float): Float {
        val deviation = value - mean
        return variance + alpha * (deviation * deviation - variance)
    }

    /**
     * Maps a value to `0..1` by how many standard deviations above the mean it
     * sits. A window with no spread — digital silence, or a perfectly steady tone
     * — scores zero rather than infinity, which is what stops the tracker from
     * hallucinating a beat out of the first breath of noise.
     */
    private fun squash(value: Float, mean: Float, variance: Float, zSpan: Float): Float {
        val sd = sqrt(variance)
        if (sd <= EPSILON) return 0f
        val z = (value - mean) / sd
        return (z / zSpan).coerceIn(0f, 1f)
    }

    private companion object {
        /** Time constant of the adaptive statistics: about two bars at 120 BPM. */
        const val ADAPTATION_SECONDS = 2f

        /** Nothing is reported until the statistics have this much history. */
        const val WARMUP_SECONDS = 1f

        /** Z-score that maps to a full-strength beat activation. */
        const val ACTIVATION_Z_SPAN = 2.5f

        /** The same for the downbeat channel, wider so accents stay distinguishable. */
        const val DOWNBEAT_Z_SPAN = 5f

        const val EPSILON = 1e-7f
    }
}
