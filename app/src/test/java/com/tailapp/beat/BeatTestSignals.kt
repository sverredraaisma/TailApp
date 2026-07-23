package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import kotlin.math.exp
import kotlin.random.Random

/**
 * Synthetic feature frames with a known beat grid.
 *
 * These drive the beat tier without going through the audio front-end, so a
 * failure here is unambiguously the tracker's fault rather than the extractor's.
 * The integration path — real synthetic audio through the real extractor — is
 * covered separately.
 *
 * The flux profile is a decaying spike on each beat plus a weaker one on each
 * off-beat eighth, because a grid of clean impulses is easier to track than
 * anything real: the off-beats are what would drag a naive phase-locker onto the
 * syncopation.
 */
class BeatTestSignals(
    val config: FeatureConfig = FeatureConfig(),
    seed: Int = 11
) {
    private val random = Random(seed)

    val hopNanos: Long = (1_000_000_000.0 / config.framesPerSecond).toLong()

    /**
     * @param frames every feature frame, in order.
     * @param beatNanos timestamps of the true beats.
     * @param downbeatNanos timestamps of the true downbeats (a subset of [beatNanos]).
     */
    class Signal(
        val frames: List<FeatureFrame>,
        val beatNanos: List<Long>,
        val downbeatNanos: List<Long>
    )

    /**
     * A steady grid at [bpm].
     *
     * @param accentEvery beats per bar; the first of each bar carries the bass hit.
     * @param offbeatLevel relative flux of the off-beat eighths.
     * @param noise flux noise floor.
     */
    fun grid(
        bpm: Float,
        seconds: Float,
        accentEvery: Int = 4,
        offbeatLevel: Float = 0.45f,
        noise: Float = 0.06f,
        startFrame: Long = 0L
    ): Signal {
        val periodFrames = config.framesPerSecond * 60f / bpm
        val frameCount = (seconds * config.framesPerSecond).toInt()

        val frames = ArrayList<FeatureFrame>(frameCount)
        val beatNanos = mutableListOf<Long>()
        val downbeatNanos = mutableListOf<Long>()

        for (i in 0 until frameCount) {
            val absolute = startFrame + i
            val beatPosition = absolute / periodFrames
            val nearestBeat = Math.round(beatPosition).toInt()
            val distanceToBeat = kotlin.math.abs(absolute - nearestBeat * periodFrames)
            val distanceToOffbeat =
                kotlin.math.abs(absolute - (Math.round(beatPosition - 0.5f) + 0.5f) * periodFrames)

            val beatFlux = exp(-distanceToBeat / DECAY_FRAMES)
            val offbeatFlux = offbeatLevel * exp(-distanceToOffbeat / DECAY_FRAMES)
            val flux = maxOf(beatFlux, offbeatFlux) + noise * random.nextFloat()

            val isDownbeat = nearestBeat % accentEvery == 0
            val bassSpike = if (isDownbeat) 1f else 0.35f
            val bass = bassSpike * exp(-distanceToBeat / DECAY_FRAMES) + noise * random.nextFloat()

            val timestamp = (absolute + 1) * hopNanos
            // A frame is "on" a beat when the beat falls inside its hop.
            if (distanceToBeat < 0.5f) {
                val beatNano = (nearestBeat * periodFrames + 1) * hopNanos
                beatNanos.add(beatNano.toLong())
                if (isDownbeat) downbeatNanos.add(beatNano.toLong())
            }

            frames.add(
                FeatureFrame(
                    index = absolute,
                    timestampNanos = timestamp,
                    bands = EMPTY_BANDS,
                    flux = flux,
                    rms = 0.3f + 0.2f * beatFlux,
                    bassEnergy = bass,
                    midEnergy = 0.2f + 0.1f * flux,
                    highEnergy = 0.15f + 0.1f * flux,
                    spectralCentroidHz = 1800f
                )
            )
        }
        return Signal(frames, beatNanos, downbeatNanos)
    }

    /** Frames with no events at all. */
    fun silence(seconds: Float, startFrame: Long = 0L): List<FeatureFrame> {
        val frameCount = (seconds * config.framesPerSecond).toInt()
        return List(frameCount) { i ->
            val absolute = startFrame + i
            FeatureFrame(
                index = absolute,
                timestampNanos = (absolute + 1) * hopNanos,
                bands = EMPTY_BANDS,
                flux = 0f,
                rms = 0f,
                bassEnergy = 0f,
                midEnergy = 0f,
                highEnergy = 0f,
                spectralCentroidHz = 0f
            )
        }
    }

    /** An activation impulse train at [bpm], for driving [TempoEstimator] directly. */
    fun activationTrain(bpm: Float, seconds: Float, noise: Float = 0.05f): FloatArray {
        val periodFrames = config.framesPerSecond * 60f / bpm
        val frameCount = (seconds * config.framesPerSecond).toInt()
        return FloatArray(frameCount) { i ->
            val nearestBeat = Math.round(i / periodFrames)
            val distance = kotlin.math.abs(i - nearestBeat * periodFrames)
            (exp(-distance / DECAY_FRAMES) + noise * random.nextFloat()).coerceIn(0f, 1f)
        }
    }

    private companion object {
        /** Frames an event's flux takes to decay by 1/e — about 30 ms at 50 fps. */
        const val DECAY_FRAMES = 1.5f

        val EMPTY_BANDS = FloatArray(0)
    }
}
