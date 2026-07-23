package com.tailapp.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Discrete Fourier transform of **any** length, built on top of the radix-2
 * [Fft] via Bluestein's chirp-z algorithm.
 *
 * [Fft] is radix-2 and refuses anything that is not a power of two. BeatNet's
 * analysis window is 1411 samples (`int(64 * 0.001 * 22050)`, and 1411 = 17 x 83),
 * so producing the spectrum the CRNN was trained on needs a transform of a size
 * radix-2 cannot reach. A naive O(n^2) DFT would do it correctly and far too
 * slowly — 1411^2 complex multiply-adds fifty times a second is ~100 Mop/s of
 * pure overhead on a phone that is also rendering and streaming BLE.
 *
 * ### How it works
 * With `j*k = (j^2 + k^2 - (k - j)^2) / 2`, the DFT
 * ```
 * X[k] = sum_j x[j] * exp(-2i*pi*j*k/n)
 * ```
 * factors into a *convolution*:
 * ```
 * X[k] = w[k] * sum_j (x[j] * w[j]) * conj(w[k - j]),   w[m] = exp(-i*pi*m^2/n)
 * ```
 * and a convolution of two length-n sequences is a pointwise product of two
 * transforms of any length >= 2n-1. That length is free to be a power of two, so
 * the whole thing runs on [Fft]: for n = 1411 the convolution length is 4096.
 *
 * ### What is precomputed
 * The chirp `w`, the transform of the (zero-padded, symmetrically extended)
 * filter `conj(w)`, the [Fft] instance and every scratch buffer are all built in
 * the constructor. [transform] and [magnitudeSpectrum] allocate nothing, which is
 * the point: they run once per analysis hop, fifty times a second, for the whole
 * length of a lighting session.
 *
 * The chirp angles are reduced modulo `2n` in integer arithmetic
 * (`exp(-i*pi*m^2/n)` has period `2n` in `m^2`) before being handed to
 * [cos]/[sin]. Passing `pi * 1410^2 / 1411` to a trig function instead would
 * spend most of its mantissa on a phase that is about to be thrown away.
 *
 * ### Accuracy
 * Everything is `float`, like [Fft]. Bluestein does more arithmetic than a direct
 * transform — three length-4096 passes and two complex multiplies per sample
 * instead of one length-1411 pass — so it accumulates a little more rounding
 * error, but only a little: measured against an O(n^2) double-precision DFT,
 * `BluesteinFftTest` reports agreement to **2.5e-7** of the spectrum's peak
 * magnitude at every size it checks, **2.4e-7** at n = 1411. That is a couple of
 * float ulps, and it is what lets [com.tailapp.audio.BeatNetFeatureExtractor]
 * reach 1.1e-6 against madmom's own float32 output.
 *
 * Not thread-safe: [transform] writes through scratch owned by this instance, so
 * one instance belongs to one calling thread — the same rule [Fft] has.
 *
 * @param size transform length; any integer >= 2, prime or not.
 */
class BluesteinFft(val size: Int) {

    init {
        require(size >= 2) { "size must be >= 2, was $size" }
    }

    /**
     * Length of the power-of-two convolution the chirps are transformed at:
     * the smallest power of two >= `2 * size - 1`. 4096 for BeatNet's 1411.
     */
    val convolutionSize: Int = run {
        var m = 2
        while (m < 2 * size - 1) m = m shl 1
        m
    }

    /**
     * Unique bins [magnitudeSpectrum] returns for a real input: DC through
     * `floor(size/2)`. For an odd [size] there is no Nyquist bin, so the last
     * entry is an ordinary bin whose mirror is `size - floor(size/2)`.
     */
    val binCount: Int = size / 2 + 1

    private val fft = Fft(convolutionSize)

    // w[j] = exp(-i*pi*j^2/n), the chirp applied both before and after the
    // convolution.
    private val chirpRe = FloatArray(size)
    private val chirpIm = FloatArray(size)

    // The transform of conj(w), zero-padded to convolutionSize and extended
    // symmetrically (b[M - m] = b[m]) so the cyclic convolution the FFT computes
    // agrees with the linear one Bluestein needs over the range that matters.
    private val filterRe = FloatArray(convolutionSize)
    private val filterIm = FloatArray(convolutionSize)

    // Scratch for one call, sized for the convolution.
    private val workRe = FloatArray(convolutionSize)
    private val workIm = FloatArray(convolutionSize)

    // Scratch for the real-input helper, sized for the transform itself.
    private val realRe = FloatArray(size)
    private val realIm = FloatArray(size)

    init {
        val period = 2L * size
        for (j in 0 until size) {
            // (j*j) mod 2n first: exp(-i*pi*m^2/n) is periodic in m^2 with
            // period 2n, and the reduction is exact in Long.
            val angle = -PI * ((j.toLong() * j) % period).toDouble() / size
            chirpRe[j] = cos(angle).toFloat()
            chirpIm[j] = sin(angle).toFloat()
        }

        filterRe[0] = chirpRe[0]
        filterIm[0] = -chirpIm[0]
        for (m in 1 until size) {
            val re = chirpRe[m]
            val im = -chirpIm[m]
            filterRe[m] = re
            filterIm[m] = im
            // convolutionSize >= 2*size - 1 guarantees convolutionSize - m > m,
            // so the two halves never overwrite each other.
            filterRe[convolutionSize - m] = re
            filterIm[convolutionSize - m] = im
        }
        fft.transform(filterRe, filterIm)
    }

    /**
     * In-place complex DFT: [re]/[im] (each of length [size]) are overwritten
     * with the transform, in natural frequency order — index 0 is DC and index
     * `size - k` is the negative-frequency mirror of index `k`.
     */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size) { "re/im must have length $size" }

        // a[j] = x[j] * w[j], zero-padded to the convolution length.
        for (j in 0 until size) {
            val xr = re[j]
            val xi = im[j]
            val wr = chirpRe[j]
            val wi = chirpIm[j]
            workRe[j] = xr * wr - xi * wi
            workIm[j] = xr * wi + xi * wr
        }
        java.util.Arrays.fill(workRe, size, convolutionSize, 0f)
        java.util.Arrays.fill(workIm, size, convolutionSize, 0f)

        fft.transform(workRe, workIm)

        // Pointwise product with the precomputed filter transform, then back.
        for (k in 0 until convolutionSize) {
            val ar = workRe[k]
            val ai = workIm[k]
            val br = filterRe[k]
            val bi = filterIm[k]
            workRe[k] = ar * br - ai * bi
            workIm[k] = ar * bi + ai * br
        }
        inverseInPlace()

        // X[k] = w[k] * conv[k]; the tail of the convolution is discarded.
        for (k in 0 until size) {
            val cr = workRe[k]
            val ci = workIm[k]
            val wr = chirpRe[k]
            val wi = chirpIm[k]
            re[k] = cr * wr - ci * wi
            im[k] = cr * wi + ci * wr
        }
    }

    /**
     * Real-input helper: magnitude spectrum of a real-valued [input] (length
     * [size]) written into [magnitudeOut] (length [binCount]).
     *
     * Copies into owned scratch, so repeated calls allocate nothing.
     */
    fun magnitudeSpectrum(input: FloatArray, magnitudeOut: FloatArray) {
        require(input.size == size) { "input must have length $size" }
        require(magnitudeOut.size == binCount) { "magnitudeOut must have length $binCount" }

        System.arraycopy(input, 0, realRe, 0, size)
        java.util.Arrays.fill(realIm, 0f)
        transform(realRe, realIm)

        for (i in 0 until binCount) {
            magnitudeOut[i] = sqrt(realRe[i] * realRe[i] + realIm[i] * realIm[i])
        }
    }

    /**
     * Inverse transform of the scratch buffers, by conjugation:
     * `ifft(y) = conj(fft(conj(y))) / M`. Reusing the forward [Fft] this way
     * costs one sign flip per sample and saves a second set of tables.
     */
    private fun inverseInPlace() {
        for (k in 0 until convolutionSize) workIm[k] = -workIm[k]
        fft.transform(workRe, workIm)
        val scale = 1f / convolutionSize
        for (k in 0 until convolutionSize) {
            workRe[k] *= scale
            workIm[k] *= -scale
        }
    }
}
