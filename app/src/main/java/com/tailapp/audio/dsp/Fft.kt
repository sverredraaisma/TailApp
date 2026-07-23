package com.tailapp.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place radix-2 Cooley-Tukey FFT, sized once at construction.
 *
 * Every table an iterative radix-2 FFT needs — the bit-reversal permutation
 * and the per-stage twiddle factors — depends only on [size], so all of it is
 * precomputed here instead of inside [transform]. That is what makes repeated
 * calls (one per analysis hop, at 50 Hz for [FeatureExtractor][com.tailapp.audio.FeatureExtractor])
 * cheap: no trig, no allocation, just array reads and multiply-adds.
 *
 * Not thread-safe: [magnitudeSpectrum] writes into scratch arrays owned by
 * this instance, so a single instance belongs to one calling thread — same
 * rule as [com.tailapp.audio.FftProcessor].
 *
 * @param size FFT length; must be a power of two.
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && Integer.bitCount(size) == 1) { "size must be a power of two >= 2, was $size" }
    }

    // table[i] = the index i's bits reversed (over log2(size) bits). Built once
    // with the standard Gray-code-like incremental trick, same as the inline
    // version in FftProcessor, just hoisted out of the hot path.
    private val bitReversedIndex = IntArray(size).also { table ->
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            table[i] = j
        }
    }

    // Every stage's twiddle factors are a strided subsample of the finest
    // table (the one a size-`size` stage would use): W_len^k == W_size^(k*size/len).
    // Precomputing only this one table of size/2 entries — instead of one
    // table per stage — keeps setup at O(size) instead of O(size log size).
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)

    init {
        for (k in 0 until size / 2) {
            val angle = -2.0 * PI * k / size
            cosTable[k] = cos(angle).toFloat()
            sinTable[k] = sin(angle).toFloat()
        }
    }

    // Scratch for the real-input helper only; transform() itself touches
    // whatever re/im arrays the caller passes and owns no state of its own.
    private val scratchRe = FloatArray(size)
    private val scratchIm = FloatArray(size)

    /**
     * In-place complex FFT: [re]/[im] (each length [size]) are overwritten with
     * the transform, in natural (non-bit-reversed) frequency order — index 0
     * is DC, index size/2 is Nyquist, and index `size - k` is the negative-
     * frequency mirror of index `k`.
     */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size) { "re/im must have length $size" }

        for (i in 0 until size) {
            val j = bitReversedIndex[i]
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= size) {
            val half = len / 2
            val twiddleStride = size / len
            var i = 0
            while (i < size) {
                var twiddleIndex = 0
                for (k in 0 until half) {
                    val wr = cosTable[twiddleIndex]
                    val wi = sinTable[twiddleIndex]
                    val idx1 = i + k
                    val idx2 = idx1 + half
                    val tr = re[idx2] * wr - im[idx2] * wi
                    val ti = re[idx2] * wi + im[idx2] * wr
                    re[idx2] = re[idx1] - tr
                    im[idx2] = im[idx1] - ti
                    re[idx1] += tr
                    im[idx1] += ti
                    twiddleIndex += twiddleStride
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Real-input helper: magnitude spectrum of a real-valued [input] (length
     * [size]) written into [magnitudeOut] (length `size/2 + 1`: DC at index 0
     * through Nyquist at the last index — the negative-frequency half is a
     * mirror of this for real input, so it is never computed or returned).
     *
     * Copies [input] into an owned scratch buffer, zeroes the owned imaginary
     * scratch, and runs [transform] on those — so this allocates nothing on
     * repeated calls, only the first construction does.
     */
    fun magnitudeSpectrum(input: FloatArray, magnitudeOut: FloatArray) {
        require(input.size == size) { "input must have length $size" }
        require(magnitudeOut.size == size / 2 + 1) { "magnitudeOut must have length ${size / 2 + 1}" }

        System.arraycopy(input, 0, scratchRe, 0, size)
        java.util.Arrays.fill(scratchIm, 0f)
        transform(scratchRe, scratchIm)

        for (i in magnitudeOut.indices) {
            magnitudeOut[i] = sqrt(scratchRe[i] * scratchRe[i] + scratchIm[i] * scratchIm[i])
        }
    }
}
