package com.tailapp.audio

import com.tailapp.audio.dsp.LogFilterbank
import com.tailapp.beat.AdaptivePeakNormalizer

/**
 * Turns a BeatLight analysis frame into the FF05 payload the device expects.
 *
 * This is what lets one microphone serve both consumers. The FF05 visualiser
 * stream and the BeatLight session used to open separate captures and were
 * mutually exclusive because a second capture generally returns silence rather
 * than an error — so starting a session silently killed the device's own audio
 * effects. Deriving the frame from analysis the session already does removes
 * the conflict entirely: no second capture exists.
 *
 * Two mappings are needed, because the two front-ends disagree by design:
 *
 * - **Bands to bins.** The analysis filterbank is log-spaced at 24 bands per
 *   octave over 30 Hz–17 kHz, far finer than the ~64 bins the device wants.
 *   Bands are grouped into the configured bin count over the configured
 *   frequency window, taking each group's peak rather than its mean — a mean
 *   washes a narrow peak out against its quiet neighbours, and the device's
 *   bar effects are drawing peaks.
 * - **Level to a byte.** Analysis energies are small, level-dependent floats.
 *   The same [AdaptivePeakNormalizer] the composer uses maps them onto `0..255`,
 *   so a bar reaches full height at a conversational volume rather than only
 *   when clipping.
 *
 * Not thread-safe: one instance belongs to one analysis pipeline.
 */
class FeatureFrameFftEncoder(
    private val config: FeatureConfig = FeatureConfig(),
    settings: FftSettings = FftSettings()
) {
    /**
     * Centre frequency of each analysis band, used to find which bands fall in
     * each output bin. Rebuilt only when [config] changes, which it does not.
     */
    private val centersHz: FloatArray = LogFilterbank(
        sampleRate = config.sampleRate,
        frameSize = config.frameSize,
        bandsPerOctave = config.bandsPerOctave,
        fMin = config.fMin,
        fMax = config.fMax
    ).centerFrequenciesHz

    private val loudnessNormalizer = AdaptivePeakNormalizer(framesPerSecond = config.framesPerSecond)
    private val spectrumNormalizer = AdaptivePeakNormalizer(framesPerSecond = config.framesPerSecond)

    @Volatile
    var settings: FftSettings = settings
        set(value) {
            field = value
            binRanges = null // recomputed lazily against the new window
        }

    /** `[startBand, endBandExclusive]` per output bin; null until first use. */
    private var binRanges: Array<IntRange>? = null

    fun reset() {
        loudnessNormalizer.reset()
        spectrumNormalizer.reset()
    }

    /**
     * @return loudness in `0..255` and one byte per configured bin, ready for
     *   [com.tailapp.ble.protocol.FftFrameBuilder].
     */
    fun encode(frame: FeatureFrame): FftResult {
        val s = settings
        val ranges = binRanges ?: buildBinRanges(s).also { binRanges = it }

        val loudness = (loudnessNormalizer.normalize(frame.rms) * 255f)
            .toInt().coerceIn(0, 255)

        // One shared reference for the whole spectrum, not one per bin.
        // Per-bin normalisation would flatten the spectrum into a wall: every
        // bin, however quiet, eventually reaches full scale.
        var frameMax = 0f
        for (v in frame.bands) if (v > frameMax) frameMax = v
        spectrumNormalizer.normalize(frameMax)
        val peak = spectrumNormalizer.currentPeak

        val bins = ByteArray(ranges.size)
        if (peak > 0f) {
            for (i in ranges.indices) {
                var groupPeak = 0f
                for (band in ranges[i]) {
                    val v = frame.bands[band]
                    if (v > groupPeak) groupPeak = v
                }
                bins[i] = ((groupPeak / peak).coerceIn(0f, 1f) * 255f).toInt()
                    .coerceIn(0, 255).toByte()
            }
        }

        return FftResult(loudness.toByte(), bins)
    }

    /**
     * Splits the analysis bands into [FftSettings.binCount] groups spanning the
     * configured frequency window, logarithmically — matching how the bands
     * themselves are spaced, so each bin covers a similar musical interval
     * rather than the top bin swallowing half the spectrum.
     */
    private fun buildBinRanges(s: FftSettings): Array<IntRange> {
        val low = s.frequencyStartHz.coerceAtLeast(config.fMin)
        val high = s.frequencyEndHz.coerceAtMost(config.fMax).coerceAtLeast(low * 1.01f)

        val firstBand = centersHz.indexOfFirst { it >= low }.takeIf { it >= 0 } ?: 0
        val lastBand = centersHz.indexOfLast { it <= high }.takeIf { it >= firstBand }
            ?: centersHz.lastIndex

        val usable = lastBand - firstBand + 1
        val count = s.binCount.coerceIn(1, usable)

        return Array(count) { i ->
            val start = firstBand + (i * usable) / count
            val end = firstBand + ((i + 1) * usable) / count
            // Every bin gets at least one band, so none is silently always zero.
            start until end.coerceAtLeast(start + 1)
        }
    }
}

/**
 * How the FF05 frame is constructed, mirroring the Audio Config screen. Held
 * separately from [FeatureConfig] because these are presentation choices for
 * the device's own effects, not properties of the analysis front-end.
 */
data class FftSettings(
    val binCount: Int = 64,
    val frequencyStartHz: Float = 40f,
    val frequencyEndHz: Float = 16000f
)
