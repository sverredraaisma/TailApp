package com.tailapp.audio

import com.tailapp.audio.dsp.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class FftResult(
    val loudness: Byte,
    val bins: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FftResult) return false
        return loudness == other.loudness && bins.contentEquals(other.bins)
    }

    override fun hashCode(): Int = 31 * loudness.toInt() + bins.contentHashCode()

    override fun toString(): String = "FftResult(loudness=$loudness, bins=${bins.size})"
}

/**
 * The four tunables, snapshotted together.
 *
 * The Audio Config sliders write from the main thread while [FftProcessor.process]
 * runs on the capture dispatcher. Publishing them as one immutable object read
 * once per frame means a drag can never be observed half-applied — the old
 * per-field `var`s could hand a single frame an inverted `start > end` range.
 */
private data class FftTunables(
    val numBins: Int,
    val normalizationSpeed: Float,
    val freqRangeStart: Float,
    val freqRangeEnd: Float
)

/**
 * Turns a PCM frame into the FF05 payload: one perceived-loudness byte plus
 * [numBins] magnitude bytes spread logarithmically across
 * [freqRangeStart]..[freqRangeEnd].
 *
 * Three things about the signal path are load-bearing:
 *
 * - **The frame is zero-padded, never truncated.** A 1470-sample frame (30 fps
 *   at 44.1 kHz) used to be cut to the largest power of two that fitted — 1024,
 *   throwing away 30% of every frame *and* halving the frequency resolution.
 *   Padding up to 2048 keeps every sample and doubles the resolution instead.
 * - **DC is removed and bin 0 is never read.** A microphone's DC offset lands
 *   entirely in bin 0 and leaks into its neighbours. Left in, it was folded into
 *   the loudness RMS and — because the lowest output bins all resolve to the
 *   same FFT bin at this resolution — it *was* the bottom bars, which therefore
 *   never moved with the bass. The frame mean is subtracted before windowing and
 *   the usable spectrum starts at bin 1.
 * - **The reference level is a real peak tracker.** Fast attack, slow release,
 *   floored at [MIN_LOUDNESS_REFERENCE] / [MIN_SPECTRUM_REFERENCE] so genuinely
 *   quiet input reads quiet rather than being normalised back up to full scale.
 *   The previous recurrence converged on three times the signal level, so a
 *   steady tone reported 85/255 whatever its actual loudness.
 *
 * Magnitudes are scaled into full-scale amplitude units (a full-scale sine peaks
 * at 1.0 in its own bin) so the floors above mean something absolute and are
 * independent of frame size.
 *
 * Not thread-safe — the reference levels and the scratch buffers carry state
 * between frames, so a single instance belongs to a single capture loop. The
 * tunables above are the exception: they may be written from any thread.
 */
class FftProcessor {

    @Volatile
    private var tunables = FftTunables(
        numBins = 64,
        normalizationSpeed = 0.1f,
        freqRangeStart = DEFAULT_FREQ_START,
        freqRangeEnd = DEFAULT_FREQ_END
    )

    var numBins: Int
        get() = tunables.numBins
        set(value) { tunables = tunables.copy(numBins = value) }

    var normalizationSpeed: Float
        get() = tunables.normalizationSpeed
        set(value) { tunables = tunables.copy(normalizationSpeed = value) }

    var freqRangeStart: Float
        get() = tunables.freqRangeStart
        set(value) { tunables = tunables.copy(freqRangeStart = value) }

    var freqRangeEnd: Float
        get() = tunables.freqRangeEnd
        set(value) { tunables = tunables.copy(freqRangeEnd = value) }

    /** Reference the loudness byte is measured against, in full-scale units. */
    private var loudnessReference: Float = MIN_LOUDNESS_REFERENCE

    /** Reference the bin bytes are measured against — the spectrum's own peak. */
    private var spectrumReference: Float = MIN_SPECTRUM_REFERENCE

    // Scratch, rebuilt only when the frame geometry changes (it does not, in a
    // session): one Fft with its precomputed twiddle tables, the Hann window for
    // the real sample count, the zero-padded input and the magnitude spectrum.
    private var fft: Fft? = null
    private var padded: FloatArray = FloatArray(0)
    private var magnitudes: FloatArray = FloatArray(0)
    private var window: FloatArray = FloatArray(0)
    private var windowSum: Float = 1f

    /** Resets the adaptive gain. Call when starting a new capture session. */
    fun reset() {
        loudnessReference = MIN_LOUDNESS_REFERENCE
        spectrumReference = MIN_SPECTRUM_REFERENCE
    }

    fun process(samples: ShortArray, sampleRate: Int): FftResult {
        val t = tunables
        val binCount = t.numBins.coerceIn(MIN_BINS, MAX_BINS)

        val n = samples.size
        if (n < 2 || sampleRate <= 0) return FftResult(0, ByteArray(binCount))

        // Smallest power of two that *holds* the frame: pad, do not truncate.
        val fftSize = nextPowerOfTwo(n)
        if (fftSize < 2) return FftResult(0, ByteArray(binCount))

        ensureScratch(n, fftSize)
        val transform = fft ?: return FftResult(0, ByteArray(binCount))
        val mags = magnitudes

        // Remove the frame mean before windowing: a mic's DC offset otherwise
        // dominates bin 0 and leaks into bins 1-2, where the bass lives.
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toInt()
        val mean = (sum / n).toFloat()

        for (i in 0 until n) padded[i] = (samples[i] - mean) * window[i]
        java.util.Arrays.fill(padded, n, fftSize, 0f)

        transform.magnitudeSpectrum(padded, mags)

        // Coherent gain of the window, one-sided, in units of digital full scale:
        // a full-scale sine reads 1.0 in its own bin whatever the frame size.
        val magScale = 2f / (windowSum * FULL_SCALE)

        // Resolve the selected frequency range to FFT bin indices. The range is
        // sanitised first: ln() below needs a strictly positive start, and the
        // bin arithmetic needs start strictly below end.
        val nyquist = sampleRate / 2f
        if (nyquist <= MIN_FREQ_HZ * MIN_RANGE_RATIO) return FftResult(0, ByteArray(binCount))
        val requestedStart = t.freqRangeStart.takeIf { it.isFinite() } ?: DEFAULT_FREQ_START
        val requestedEnd = t.freqRangeEnd.takeIf { it.isFinite() } ?: DEFAULT_FREQ_END
        val rangeStart = requestedStart.coerceIn(MIN_FREQ_HZ, nyquist / MIN_RANGE_RATIO)
        val rangeEnd = requestedEnd.coerceIn(rangeStart * MIN_RANGE_RATIO, nyquist)

        val freqPerBin = sampleRate.toFloat() / fftSize
        // mags has size fftSize/2 + 1 (DC through Nyquist). Bin 0 is DC and is
        // deliberately excluded, so the usable spectrum is [1, mags.size).
        val lastBin = mags.size
        if (lastBin < 3) return FftResult(0, ByteArray(binCount))
        val startBin = (rangeStart / freqPerBin).toInt().coerceIn(FIRST_USABLE_BIN, lastBin - 1)
        val endBin = (rangeEnd / freqPerBin).toInt().coerceIn(startBin + 1, lastBin)

        // Loudness: RMS over the selected range only.
        var sumSquares = 0.0
        var frameMax = 0f
        for (i in startBin until endBin) {
            val m = mags[i]
            sumSquares += m.toDouble() * m
            if (m > frameMax) frameMax = m
        }
        val rms = (sqrt(sumSquares / (endBin - startBin)).toFloat()) * magScale

        val speed = t.normalizationSpeed.takeIf { it.isFinite() } ?: DEFAULT_NORM_SPEED
        val attack = (speed * ATTACK_GAIN).coerceIn(MIN_COEFF, 1f)
        val release = (speed * RELEASE_GAIN).coerceIn(MIN_COEFF, 1f)

        loudnessReference =
            trackPeak(loudnessReference, rms, attack, release, MIN_LOUDNESS_REFERENCE)
        val normalizedLoudness =
            (rms / loudnessReference * 255f).toInt().coerceIn(0, 255)

        // Bins are referenced to the spectrum's own peak, not to the band RMS:
        // one shared reference keeps the shape of the spectrum, while dividing a
        // per-bin magnitude by a band *average* would push every bar to full.
        spectrumReference = trackPeak(
            spectrumReference, frameMax * magScale, attack, release, MIN_SPECTRUM_REFERENCE
        )

        // Map output bins to FFT bins on a logarithmic frequency scale
        val outputBins = ByteArray(binCount)
        val logStart = ln(rangeStart.toDouble())
        val logEnd = ln(rangeEnd.toDouble())

        for (i in 0 until binCount) {
            val freqFrom = exp(logStart + (logEnd - logStart) * i / binCount)
            val freqTo = exp(logStart + (logEnd - logStart) * (i + 1) / binCount)
            val from = (freqFrom / freqPerBin).toInt().coerceIn(startBin, endBin - 1)
            val to = (freqTo / freqPerBin).toInt().coerceIn(from + 1, endBin)
            var groupSum = 0f
            for (j in from until to) groupSum += mags[j]
            val avg = groupSum / (to - from) * magScale
            val normalized = (avg / spectrumReference).coerceIn(0f, 1f)
            outputBins[i] = (normalized * 255f).toInt().coerceIn(0, 255).toByte()
        }

        return FftResult(normalizedLoudness.toByte(), outputBins)
    }

    /**
     * Fast attack, slow release, floored. The floor is what makes the result
     * absolute at the quiet end: without it the reference converges on whatever
     * the input happens to be and every steady level reports the same byte.
     */
    private fun trackPeak(
        current: Float,
        value: Float,
        attack: Float,
        release: Float,
        floor: Float
    ): Float {
        val coefficient = if (value > current) attack else release
        val next = current + (value - current) * coefficient
        return if (!next.isFinite() || next < floor) floor else next
    }

    private fun ensureScratch(sampleCount: Int, fftSize: Int) {
        if (padded.size != fftSize) {
            fft = Fft(fftSize)
            padded = FloatArray(fftSize)
            magnitudes = FloatArray(fftSize / 2 + 1)
        }
        if (window.size != sampleCount) {
            // Symmetric Hann over the *real* samples; the padding stays zero.
            val w = FloatArray(sampleCount)
            var total = 0f
            for (i in 0 until sampleCount) {
                val v = 0.5f * (1f - cos(2.0 * PI * i / (sampleCount - 1)).toFloat())
                w[i] = v
                total += v
            }
            window = w
            windowSum = if (total > 0f) total else 1f
        }
    }

    companion object {
        const val MIN_BINS = 1

        /** The FF05 frame carries `num_bins` as a u8, and the device caps display at 32 bars. */
        const val MAX_BINS = 128

        const val DEFAULT_FREQ_START = 20f
        const val DEFAULT_FREQ_END = 20000f

        private const val DEFAULT_NORM_SPEED = 0.1f

        private const val MIN_FREQ_HZ = 1f

        /** Keeps `ln(end) > ln(start)` so the log-spaced bin edges stay strictly increasing. */
        private const val MIN_RANGE_RATIO = 1.01f

        /** Bin 0 is DC; it is never part of the loudness or of any output bin. */
        private const val FIRST_USABLE_BIN = 1

        /** 16-bit PCM full scale, so magnitudes come out in 0..1 units. */
        private const val FULL_SCALE = 32768f

        /**
         * The reference rises this much faster than [normalizationSpeed] and
         * falls this much slower — a peak follower has to catch a transient
         * within a frame or two but must hold its reference across a bar.
         */
        private const val ATTACK_GAIN = 5f
        private const val RELEASE_GAIN = 0.1f
        private const val MIN_COEFF = 5e-4f

        /**
         * Floors for the two references, in full-scale units. A full-scale sine
         * has a band RMS of roughly 0.03 and a bin peak of 1.0, so these sit
         * ~44 dB and ~60 dB below it: real material at any listening level is
         * still normalised to the full byte range, while a near-silent room
         * reports near-silent instead of being amplified to 255.
         */
        private const val MIN_LOUDNESS_REFERENCE = 2e-4f
        private const val MIN_SPECTRUM_REFERENCE = 1e-3f

        private fun nextPowerOfTwo(value: Int): Int {
            if (value <= 1) return 1
            return Integer.highestOneBit(value - 1) shl 1
        }
    }
}
