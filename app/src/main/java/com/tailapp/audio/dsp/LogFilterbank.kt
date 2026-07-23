package com.tailapp.audio.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Logarithmically spaced triangular filterbank, built once for a fixed
 * `(sampleRate, frameSize)` pair and reused for every subsequent
 * [magnitude spectrum][Fft.magnitudeSpectrum] via [apply].
 *
 * ### Frequency → band mapping
 * Band *centres* sit at [bandsPerOctave] equal steps per octave starting at
 * [fMin]:
 * ```
 * center(i) = fMin * 2^(i / bandsPerOctave)
 * ```
 * [bandCount] is the largest `i + 1` whose centre does not exceed
 * `min(fMax, sampleRate / 2)` — see the Nyquist note below. Each band `i` is
 * a triangle in frequency: 0 at `center(i-1)`, 1 at `center(i)`, 0 at
 * `center(i+1)` (the formula above is evaluated at `i-1` and `i+1` too, one
 * step outside `0 until bandCount`, so the first and last bands are full
 * triangles rather than half-triangles clipped at the edge).
 *
 * **fMax vs Nyquist.** [com.tailapp.audio.FeatureConfig]'s default `fMax`
 * (17000 Hz) exceeds the Nyquist frequency at its default sample rate
 * (11025 Hz) — that mirrors BeatNet's own `log_spect.py`, which allows the
 * same and simply never populates bands above Nyquist. This class clamps the
 * *band layout* to `min(fMax, sampleRate / 2)` so [bandCount] only counts
 * bands that can ever carry energy; it never manufactures a permanently
 * silent band at the top of the range.
 *
 * **Sub-bin triangles.** In the lowest octave, `bandsPerOctave` centres can
 * sit closer together than one FFT bin (at the defaults: 2048-point analysis
 * at 22050 Hz gives ~10.8 Hz/bin, but neighbouring 24-per-octave centres
 * starting at 30 Hz are well under 1 Hz apart). A triangle narrower than a
 * bin has no bin to assign weight to; rather than leave that band a
 * permanent zero, it collapses onto the single nearest bin at weight 1. This
 * duplicates that bin's magnitude across several adjacent low bands — a
 * blunt outcome, but the alternative (going silent) is worse for a beat
 * tracker that leans on bass energy.
 *
 * ### Sparse storage
 * A dense `bandCount x (frameSize/2+1)` matrix would be almost entirely
 * zero at these settings — each triangle only ever touches a handful of FFT
 * bins — so each band stores just its first touched bin ([startBin]) and a
 * weight per bin from there ([weights]), and [apply] is a per-band dot
 * product over that short run instead of a full matrix multiply.
 *
 * @param sampleRate sample rate the magnitude spectra passed to [apply] were
 *   computed at.
 * @param frameSize FFT length those spectra came from; `apply`'s input must
 *   have length `frameSize / 2 + 1` ([binCount]).
 * @param bandsPerOctave filterbank resolution.
 * @param fMin lowest band centre, in Hz.
 * @param fMax highest requested band centre, in Hz (see the Nyquist note).
 */
class LogFilterbank(
    val sampleRate: Int,
    val frameSize: Int,
    val bandsPerOctave: Int,
    val fMin: Float,
    val fMax: Float
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(frameSize >= 2 && Integer.bitCount(frameSize) == 1) { "frameSize must be a power of two >= 2" }
        require(bandsPerOctave > 0) { "bandsPerOctave must be positive" }
        require(fMin > 0f && fMax > fMin) { "need 0 < fMin < fMax" }
    }

    /** Bins expected in [apply]'s `magnitudes` argument — matches [Fft.magnitudeSpectrum]'s output length. */
    val binCount: Int = frameSize / 2 + 1

    private val hzPerBin: Float = sampleRate.toFloat() / frameSize
    private val effectiveFMax: Float = minOf(fMax, sampleRate / 2f)

    /** Number of bands; see the class doc for how this is derived. */
    val bandCount: Int = run {
        val octaves = ln((effectiveFMax / fMin).toDouble()) / ln(2.0)
        (floor(octaves * bandsPerOctave).toInt() + 1).coerceAtLeast(1)
    }

    /** Band centre frequencies, low to high, strictly increasing; `centerFrequenciesHz[0] == fMin`. */
    val centerFrequenciesHz: FloatArray = FloatArray(bandCount) { i -> centerHz(i) }

    // Sparse storage: band i touches magnitudes[startBin[i] until startBin[i] + weights[i].size].
    private val startBin = IntArray(bandCount)
    private val weights = Array(bandCount) { FloatArray(0) }

    init {
        for (i in 0 until bandCount) {
            val left = centerHz(i - 1)
            val center = centerHz(i)
            val right = centerHz(i + 1)

            val leftBin = left / hzPerBin
            val centerBin = center / hzPerBin
            val rightBin = right / hzPerBin

            var lo = ceil(leftBin).toInt().coerceAtLeast(0)
            var hi = floor(rightBin).toInt().coerceAtMost(binCount - 1)

            if (hi < lo) {
                // Sub-bin triangle (see class doc): collapse onto the nearest bin.
                val nearest = Math.round(centerBin).coerceIn(0, binCount - 1)
                lo = nearest
                hi = nearest
            }

            val w = FloatArray(hi - lo + 1)
            for (b in lo..hi) {
                val f = b * hzPerBin
                w[b - lo] = when {
                    f <= center -> if (center - left > EPSILON) ((f - left) / (center - left)).coerceIn(0f, 1f) else 1f
                    else -> if (right - center > EPSILON) ((right - f) / (right - center)).coerceIn(0f, 1f) else 1f
                }
            }
            startBin[i] = lo
            weights[i] = w
        }
    }

    /** `fMin * 2^(i / bandsPerOctave)`; deliberately accepts `i` one step outside `0 until bandCount` for edge triangles. */
    private fun centerHz(i: Int): Float =
        (fMin * 2.0.pow(i.toDouble() / bandsPerOctave)).toFloat()

    /**
     * Applies the filterbank: `bandsOut[i] = sum(magnitudes[startBin[i]..] * weights[i])`.
     *
     * @param magnitudes linear magnitude spectrum, length [binCount] (e.g. from [Fft.magnitudeSpectrum]).
     * @param bandsOut destination, length [bandCount]; overwritten, not accumulated into.
     */
    fun apply(magnitudes: FloatArray, bandsOut: FloatArray) {
        require(magnitudes.size == binCount) { "magnitudes must have length $binCount" }
        require(bandsOut.size == bandCount) { "bandsOut must have length $bandCount" }
        for (i in 0 until bandCount) {
            val w = weights[i]
            val base = startBin[i]
            var sum = 0f
            for (j in w.indices) sum += magnitudes[base + j] * w[j]
            bandsOut[i] = sum
        }
    }

    private companion object {
        const val EPSILON = 1e-6f
    }
}
