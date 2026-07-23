package com.tailapp.drop

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import kotlin.random.Random

/**
 * Builds synthetic [FeatureFrame] streams for the transient-tier tests.
 *
 * The detector works entirely in z-scores, so a perfectly constant signal has no
 * spread to measure against and nothing can ever look surprising. Every level
 * here therefore carries a little seeded jitter — that is both what real audio
 * looks like and what makes the tests exercise the guard against a zero-variance
 * window.
 */
class TransientTestSignals(
    val config: FeatureConfig = FeatureConfig(),
    seed: Int = 7
) {
    private val random = Random(seed)
    private var index = 0L

    /** Nanoseconds between feature frames. */
    val hopNanos: Long = (1_000_000_000.0 / config.framesPerSecond).toLong()

    /** Wall-clock nanos of the most recently produced frame. */
    var lastTimestampNanos: Long = 0L
        private set

    fun frame(
        rms: Float,
        bass: Float,
        flux: Float = 0.02f,
        centroidHz: Float = 1500f,
        jitter: Float = 0.06f
    ): FeatureFrame {
        val timestamp = index * hopNanos
        lastTimestampNanos = timestamp
        index++
        return FeatureFrame(
            index = index,
            timestampNanos = timestamp,
            bands = EMPTY_BANDS,
            flux = jittered(flux, jitter),
            rms = jittered(rms, jitter),
            bassEnergy = jittered(bass, jitter),
            midEnergy = jittered(rms * 0.6f, jitter),
            highEnergy = jittered(rms * 0.4f, jitter),
            spectralCentroidHz = jittered(centroidHz, jitter * 0.5f)
        )
    }

    /** Frames covering [seconds] at a steady level. */
    fun steady(
        seconds: Float,
        rms: Float,
        bass: Float,
        flux: Float = 0.02f,
        centroidHz: Float = 1500f
    ): List<FeatureFrame> = List(frameCount(seconds)) { frame(rms, bass, flux, centroidHz) }

    /** Frames covering [seconds] ramping linearly between two levels. */
    fun ramp(
        seconds: Float,
        rmsFrom: Float,
        rmsTo: Float,
        bassFrom: Float,
        bassTo: Float,
        fluxFrom: Float = 0.02f,
        fluxTo: Float = 0.02f,
        centroidHz: Float = 1500f
    ): List<FeatureFrame> {
        val count = frameCount(seconds)
        return List(count) { i ->
            val t = if (count <= 1) 1f else i.toFloat() / (count - 1)
            frame(
                rms = rmsFrom + (rmsTo - rmsFrom) * t,
                bass = bassFrom + (bassTo - bassFrom) * t,
                flux = fluxFrom + (fluxTo - fluxFrom) * t,
                centroidHz = centroidHz
            )
        }
    }

    fun frameCount(seconds: Float): Int = (seconds * config.framesPerSecond).toInt()

    private fun jittered(value: Float, jitter: Float): Float {
        if (value == 0f) return 0f
        return value * (1f + jitter * (random.nextFloat() * 2f - 1f))
    }

    private companion object {
        val EMPTY_BANDS = FloatArray(0)
    }
}
