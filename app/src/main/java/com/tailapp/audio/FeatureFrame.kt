package com.tailapp.audio

/**
 * Front-end geometry shared by every analysis tier.
 *
 * The defaults match BeatNet's `log_spect.py` (22050 Hz, 2048-sample window,
 * 441-sample hop = 50 frames/sec, logarithmically spaced filterbank) so the
 * DSP beat tracker and a future ONNX CRNN can consume the exact same frames.
 *
 * @param sampleRate analysis rate; capture is resampled to this.
 * @param frameSize STFT window length in samples.
 * @param hopSize samples between consecutive frames.
 * @param bandsPerOctave filterbank resolution.
 * @param fMin lowest filterbank edge in Hz.
 * @param fMax highest filterbank edge in Hz.
 * @param logMultiplier `mul` in `log10(mul * magnitude + add)`.
 * @param logAdd `add` in `log10(mul * magnitude + add)`; keeps silence at 0.
 * @param bassCutoffHz upper edge of the band the drop detector treats as bass.
 * @param midCutoffHz boundary between the mid and high summary bands.
 */
data class FeatureConfig(
    val sampleRate: Int = 22050,
    val frameSize: Int = 2048,
    val hopSize: Int = 441,
    val bandsPerOctave: Int = 24,
    val fMin: Float = 30f,
    val fMax: Float = 17000f,
    val logMultiplier: Float = 1f,
    val logAdd: Float = 1f,
    val bassCutoffHz: Float = 160f,
    val midCutoffHz: Float = 2000f
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(frameSize >= 2 && Integer.bitCount(frameSize) == 1) { "frameSize must be a power of two >= 2" }
        require(hopSize in 1..frameSize) { "hopSize must be in 1..frameSize" }
        require(bandsPerOctave > 0) { "bandsPerOctave must be positive" }
        require(fMin > 0f && fMax > fMin) { "need 0 < fMin < fMax" }
    }

    /** Frames produced per second of audio. */
    val framesPerSecond: Float get() = sampleRate.toFloat() / hopSize

    /** Wall-clock duration of one hop. */
    val hopMillis: Float get() = 1000f * hopSize / sampleRate
}

/**
 * One analysis hop: the log-filtered spectrum plus the cheap scalar statistics
 * every downstream tier would otherwise recompute from it.
 *
 * Deliberately a plain class, not a `data class`: [bands] is a mutable array and
 * generated `equals`/`hashCode` over it would be wrong (identity) and misleading.
 * The array is owned by the frame and must not be mutated by consumers.
 *
 * @param index hop counter since capture started, monotonic.
 * @param timestampNanos [System.nanoTime] of the *end* of this frame's window,
 *   i.e. the moment the audio it describes had finished arriving.
 * @param bands log-compressed filterbank magnitudes, low to high.
 * @param flux half-wave-rectified spectral flux against the previous frame —
 *   the onset detection function.
 * @param rms broadband RMS of the windowed samples, in `[0, 1]`.
 * @param bassEnergy mean magnitude below [FeatureConfig.bassCutoffHz].
 * @param midEnergy mean magnitude between the bass and mid cutoffs.
 * @param highEnergy mean magnitude above [FeatureConfig.midCutoffHz].
 * @param spectralCentroidHz magnitude-weighted mean frequency.
 */
class FeatureFrame(
    val index: Long,
    val timestampNanos: Long,
    val bands: FloatArray,
    val flux: Float,
    val rms: Float,
    val bassEnergy: Float,
    val midEnergy: Float,
    val highEnergy: Float,
    val spectralCentroidHz: Float
) {
    override fun toString(): String =
        "FeatureFrame(index=$index, bands=${bands.size}, flux=%.4f, rms=%.4f)".format(flux, rms)
}
