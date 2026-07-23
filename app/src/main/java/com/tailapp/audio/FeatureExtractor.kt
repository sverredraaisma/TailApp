package com.tailapp.audio

import com.tailapp.audio.dsp.Fft
import com.tailapp.audio.dsp.LogFilterbank
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns a stream of PCM samples at [FeatureConfig.sampleRate] into the
 * [FeatureFrame]s the beat tracker (and, eventually, an ONNX CRNN) consume.
 *
 * [push] accepts audio in arbitrary chunk sizes — a live mic callback and a
 * unit test handing over one giant array are both first-class — by keeping a
 * ring buffer of the trailing [FeatureConfig.frameSize] samples and emitting
 * a frame every time [FeatureConfig.hopSize] more samples have arrived,
 * regardless of how those samples were split across calls to [push]. A frame
 * boundary that falls mid-chunk (a large `push`) and one that only completes
 * after several tiny chunks are handled identically: the ring buffer and the
 * `total samples pushed` / `next frame end` counters are the only state, and
 * neither cares where a call to [push] happened to start or stop.
 *
 * **Timestamps.** [FeatureFrame.timestampNanos] is *not* "now" at the moment
 * the frame happens to be constructed — it is derived by walking back from
 * [push]'s `endTimestampNanos` (the wall-clock instant the chunk's last
 * sample arrived) by the exact number of samples between the frame's window
 * end and the chunk's end, converted through [FeatureConfig.sampleRate].
 * That derivation only ever depends on the *current* call's own
 * `endTimestampNanos` and offsets within it, never on history, so it is
 * exact and reproducible regardless of chunking.
 *
 * **Allocation.** Everything reusable across hops — the Hann window, the
 * [Fft]'s internal tables and scratch, the magnitude-spectrum buffer, the
 * linear (pre-log) filterbank output, the ring buffer, the windowed-sample
 * scratch — is allocated once, in the constructor. Each *emitted* frame
 * allocates exactly two objects: the [FeatureFrame] and its `bands`
 * `FloatArray` (both must be fresh — see [FeatureFrame]'s KDoc on why the
 * array is treated as owned by the frame). [push] additionally allocates the
 * `List` it returns, and only when at least one frame completed. Nothing
 * else touches the heap on the hot path.
 *
 * Not thread-safe: like [FftProcessor], a single instance belongs to one
 * capture pipeline.
 */
class FeatureExtractor(val config: FeatureConfig = FeatureConfig()) {

    private val fft = Fft(config.frameSize)
    private val filterbank = LogFilterbank(
        sampleRate = config.sampleRate,
        frameSize = config.frameSize,
        bandsPerOctave = config.bandsPerOctave,
        fMin = config.fMin,
        fMax = config.fMax
    )
    private val bandCount = filterbank.bandCount

    // Periodic Hann (denominator N, not N-1). That is the correct window for
    // an STFT sampled at a fixed hop — FftProcessor's symmetric window (N-1)
    // is deliberately different, tuned for one-shot visualiser frames rather
    // than a train of overlapping analysis windows.
    private val hann = FloatArray(config.frameSize) { n ->
        (0.5 - 0.5 * cos(2.0 * PI * n / config.frameSize)).toFloat()
    }

    // Ring buffer of exactly frameSize samples: at any instant it holds the
    // most recently pushed frameSize samples, oldest at [ringPos]. frameSize
    // is required (by FeatureConfig) to be a power of two, so wraparound is a
    // mask rather than a modulo.
    private val ring = FloatArray(config.frameSize)
    private val ringMask = config.frameSize - 1
    private var ringPos = 0

    // A single monotonic timeline that outlives any one push() call: the
    // absolute count of samples ever pushed, and the absolute sample index at
    // which the *next* frame's window ends. This — not anything per-call —
    // is what makes frame boundaries land in the same place regardless of
    // how the caller chops the audio into chunks.
    private var totalSamples = 0L
    private var nextFrameEnd = config.frameSize.toLong()
    private var frameIndex = 0L

    // Scratch reused every hop.
    private val windowed = FloatArray(config.frameSize)
    private val magnitudes = FloatArray(config.frameSize / 2 + 1)
    private val linearBands = FloatArray(bandCount)

    // Reference to the previous *emitted* frame's own bands array — read
    // only, never copied or mutated (see FeatureFrame's KDoc: consumers, and
    // that includes us, must not mutate an array once handed out in a
    // frame). Null until the first frame after construction/reset, so that
    // frame's flux is defined as 0 rather than an artificial spike against a
    // phantom silent predecessor.
    private var previousBands: FloatArray? = null

    // Band-index split points for the bass/mid/high summary energies,
    // resolved once against the filterbank's fixed centre frequencies
    // instead of comparing every band to the cutoffs on every frame.
    private val bassBandEnd: Int
    private val midBandEnd: Int

    init {
        var bassEnd = 0
        var midEnd = 0
        val centers = filterbank.centerFrequenciesHz
        for (i in centers.indices) {
            if (centers[i] < config.bassCutoffHz) bassEnd = i + 1
            if (centers[i] < config.midCutoffHz) midEnd = i + 1
        }
        bassBandEnd = bassEnd
        midBandEnd = midEnd.coerceAtLeast(bassEnd)
    }

    /**
     * Feeds [count] samples from [samples] (default: the whole array), all at
     * [FeatureConfig.sampleRate]. Returns one [FeatureFrame] per hop boundary
     * crossed by this call — usually zero or one, but a large enough chunk
     * (or a small [FeatureConfig.hopSize]) can complete several at once.
     *
     * @param endTimestampNanos [System.nanoTime] of the instant the *last*
     *   sample of this chunk arrived. See the class doc for how per-frame
     *   timestamps are derived from it.
     */
    fun push(samples: FloatArray, count: Int = samples.size, endTimestampNanos: Long): List<FeatureFrame> {
        require(count in 0..samples.size) { "count ($count) out of range for samples of size ${samples.size}" }
        if (count == 0) return emptyList()

        val chunkEndTotal = totalSamples + count
        var frames: MutableList<FeatureFrame>? = null

        var offset = 0
        var remaining = count
        while (remaining > 0) {
            val untilBoundary = (nextFrameEnd - totalSamples).toInt()
            val take = minOf(remaining, untilBoundary)

            writeToRing(samples, offset, take)
            totalSamples += take
            offset += take
            remaining -= take

            if (totalSamples == nextFrameEnd) {
                // Samples still to arrive, within this chunk, after this
                // frame's window closed — the walk-back distance from
                // endTimestampNanos.
                val samplesAfter = chunkEndTotal - totalSamples
                val timestamp = endTimestampNanos - (samplesAfter * NANOS_PER_SECOND) / config.sampleRate
                val list = frames ?: ArrayList<FeatureFrame>(4).also { frames = it }
                list.add(emitFrame(timestamp))
                nextFrameEnd += config.hopSize
            }
        }
        return frames ?: emptyList()
    }

    /** Drops all buffered audio and history; the next [push] starts as if from silence. */
    fun reset() {
        ring.fill(0f)
        ringPos = 0
        totalSamples = 0L
        nextFrameEnd = config.frameSize.toLong()
        frameIndex = 0L
        previousBands = null
    }

    private fun writeToRing(src: FloatArray, offset: Int, count: Int) {
        // At most one wrap: `count` is bounded by `untilBoundary`, which is at
        // most frameSize (the very first boundary) or hopSize (every one
        // after) — both <= ring.size, so a single write can never lap the
        // buffer twice.
        val firstRun = minOf(count, ring.size - ringPos)
        System.arraycopy(src, offset, ring, ringPos, firstRun)
        val remainder = count - firstRun
        if (remainder > 0) {
            System.arraycopy(src, offset + firstRun, ring, 0, remainder)
        }
        ringPos = (ringPos + count) and ringMask
    }

    private fun emitFrame(timestampNanos: Long): FeatureFrame {
        // Ring buffer invariant: once it has been filled once (guaranteed
        // here, since a frame is never emitted before frameSize samples have
        // been pushed), [ringPos] is both "where the next write will land"
        // and "the oldest sample currently held" — the standard full-buffer
        // circular-buffer property. Reading forward frameSize slots from
        // there therefore yields the window's samples oldest-to-newest.
        var sumSquares = 0.0
        for (n in 0 until config.frameSize) {
            val s = ring[(ringPos + n) and ringMask] * hann[n]
            windowed[n] = s
            sumSquares += s.toDouble() * s
        }
        val rms = sqrt(sumSquares / config.frameSize).toFloat()

        fft.magnitudeSpectrum(windowed, magnitudes)
        filterbank.apply(magnitudes, linearBands)

        val bands = FloatArray(bandCount)
        for (i in 0 until bandCount) {
            bands[i] = log10(config.logMultiplier * linearBands[i] + config.logAdd)
        }

        // Spectral flux: half-wave-rectified difference against the previous
        // *emitted* frame's own log-compressed bands (not the linear
        // magnitudes) — the standard "logarithmic flux" onset function, and
        // consistent with bands being the representation this class exposes.
        val prev = previousBands
        var flux = 0f
        if (prev != null) {
            for (i in 0 until bandCount) {
                val d = bands[i] - prev[i]
                if (d > 0f) flux += d
            }
        }
        previousBands = bands

        var bassSum = 0f
        for (i in 0 until bassBandEnd) bassSum += linearBands[i]
        val bassEnergy = if (bassBandEnd > 0) bassSum / bassBandEnd else 0f

        var midSum = 0f
        for (i in bassBandEnd until midBandEnd) midSum += linearBands[i]
        val midCount = midBandEnd - bassBandEnd
        val midEnergy = if (midCount > 0) midSum / midCount else 0f

        var highSum = 0f
        for (i in midBandEnd until bandCount) highSum += linearBands[i]
        val highCount = bandCount - midBandEnd
        val highEnergy = if (highCount > 0) highSum / highCount else 0f

        // Magnitude-weighted mean frequency, over the linear (pre-log)
        // filterbank output — "magnitude" would stop meaning much once
        // log-compressed, and the filterbank's own centre frequencies are
        // already sitting there with no extra lookup needed.
        var weightedFreq = 0f
        var magSum = 0f
        val centers = filterbank.centerFrequenciesHz
        for (i in 0 until bandCount) {
            weightedFreq += linearBands[i] * centers[i]
            magSum += linearBands[i]
        }
        val centroid = if (magSum > EPSILON) weightedFreq / magSum else 0f

        val frame = FeatureFrame(
            index = frameIndex,
            timestampNanos = timestampNanos,
            bands = bands,
            flux = flux,
            rms = rms,
            bassEnergy = bassEnergy,
            midEnergy = midEnergy,
            highEnergy = highEnergy,
            spectralCentroidHz = centroid
        )
        frameIndex++
        return frame
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val EPSILON = 1e-9f
    }
}
