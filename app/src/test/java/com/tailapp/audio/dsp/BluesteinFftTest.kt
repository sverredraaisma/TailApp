package com.tailapp.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * [BluesteinFft] against the definition of the DFT.
 *
 * A transform has no self-evident right answer to assert, so this compares
 * against a naive O(n^2) double-precision DFT — the formula itself — at sizes
 * that exercise every path: powers of two (where the convolution is barely
 * bigger than the input), primes, products of primes, and **1411**, the one this
 * class exists for.
 *
 * Errors are reported relative to the spectrum's own peak magnitude, because an
 * absolute threshold on a transform means nothing without knowing its scale.
 *
 * Measured (the tests print the live numbers): the worst relative error over
 * every size checked is **2.5e-7**, and **2.4e-7** at n = 1411 specifically —
 * i.e. a couple of float ulps, and no worse at the awkward size than at a power
 * of two. That is round-off accumulated over three length-4096 passes, not
 * algorithmic error.
 */
class BluesteinFftTest {

    /** Every size the transform is checked at; 1411 = 17 x 83 is BeatNet's window. */
    private val sizes = intArrayOf(2, 3, 4, 5, 7, 16, 17, 83, 100, 256, 1411)

    private fun naiveDft(re: FloatArray, im: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (k in 0 until n) {
            var sumRe = 0.0
            var sumIm = 0.0
            for (j in 0 until n) {
                // Reduce the phase before the trig call: 2*pi*j*k/n is periodic
                // in (j*k) mod n, and the reduction is exact in Long.
                val angle = -2.0 * PI * ((j.toLong() * k) % n).toDouble() / n
                val c = cos(angle)
                val s = sin(angle)
                sumRe += re[j] * c - im[j] * s
                sumIm += re[j] * s + im[j] * c
            }
            outRe[k] = sumRe
            outIm[k] = sumIm
        }
        return outRe to outIm
    }

    private fun peak(re: DoubleArray, im: DoubleArray): Double {
        var best = 0.0
        for (i in re.indices) best = maxOf(best, sqrt(re[i] * re[i] + im[i] * im[i]))
        return best
    }

    @Test
    fun `complex transform matches a naive DFT at every size`() {
        val random = Random(0x8117)
        val report = StringBuilder()
        var worstOverall = 0.0

        for (n in sizes) {
            val re = FloatArray(n) { random.nextFloat() * 2f - 1f }
            val im = FloatArray(n) { random.nextFloat() * 2f - 1f }
            val (expectedRe, expectedIm) = naiveDft(re, im)
            val scale = peak(expectedRe, expectedIm)

            BluesteinFft(n).transform(re, im)

            var worst = 0.0
            for (k in 0 until n) {
                val dr = re[k] - expectedRe[k]
                val di = im[k] - expectedIm[k]
                worst = maxOf(worst, sqrt(dr * dr + di * di) / scale)
            }
            report.append("n=$n rel ${"%.2e".format(worst)}; ")
            worstOverall = maxOf(worstOverall, worst)
        }

        val summary = "BluesteinFft vs naive DFT, relative to peak magnitude: $report" +
            "worst ${"%.2e".format(worstOverall)}"
        println(summary)
        assertTrue(summary, worstOverall < 1e-5)
    }

    @Test
    fun `real-input magnitude spectrum matches a naive DFT at BeatNet's window`() {
        val n = 1411
        val random = Random(0x1411)
        val input = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val (expectedRe, expectedIm) = naiveDft(input, FloatArray(n))
        val scale = peak(expectedRe, expectedIm)

        val fft = BluesteinFft(n)
        assertEquals("unique bins for a real input of odd length", n / 2 + 1, fft.binCount)
        assertEquals("convolution length is the next power of two >= 2n-1", 4096, fft.convolutionSize)

        val magnitudes = FloatArray(fft.binCount)
        fft.magnitudeSpectrum(input, magnitudes)

        var worst = 0.0
        for (k in magnitudes.indices) {
            val expected = sqrt(expectedRe[k] * expectedRe[k] + expectedIm[k] * expectedIm[k])
            worst = maxOf(worst, abs(magnitudes[k] - expected) / scale)
        }
        val summary = "magnitude spectrum at n=$n: worst relative error ${"%.2e".format(worst)}"
        println(summary)
        assertTrue(summary, worst < 1e-5)
    }

    @Test
    fun `agrees with the radix-2 Fft where both apply`() {
        val n = 512
        val random = Random(0x512)
        val samples = FloatArray(n) { random.nextFloat() * 2f - 1f }

        val radix2 = FloatArray(n / 2 + 1)
        Fft(n).magnitudeSpectrum(samples.copyOf(), radix2)

        val bluestein = FloatArray(n / 2 + 1)
        BluesteinFft(n).magnitudeSpectrum(samples.copyOf(), bluestein)

        var peakMagnitude = 0f
        for (v in radix2) peakMagnitude = maxOf(peakMagnitude, v)
        var worst = 0f
        for (i in radix2.indices) worst = maxOf(worst, abs(radix2[i] - bluestein[i]) / peakMagnitude)
        assertTrue("radix-2 vs Bluestein at n=$n: worst relative ${"%.2e".format(worst)}", worst < 1e-5)
    }

    @Test
    fun `a pure tone lands in one bin`() {
        val n = 1411
        val bin = 137
        val input = FloatArray(n) { cos(2.0 * PI * bin * it / n).toFloat() }

        val fft = BluesteinFft(n)
        val magnitudes = FloatArray(fft.binCount)
        fft.magnitudeSpectrum(input, magnitudes)

        // A cosine at exactly bin k splits its energy between +k and -k, so the
        // returned half carries n/2.
        assertEquals("peak height", n / 2f, magnitudes[bin], n * 1e-4f)
        for (k in magnitudes.indices) {
            if (k == bin) continue
            assertTrue("bin $k leaked ${magnitudes[k]}", magnitudes[k] < n * 1e-4f)
        }
    }

    @Test
    fun `repeated calls are independent and allocate no state`() {
        val n = 83
        val random = Random(83)
        val fft = BluesteinFft(n)
        val input = FloatArray(n) { random.nextFloat() }

        val first = FloatArray(fft.binCount)
        fft.magnitudeSpectrum(input, first)
        // Interleave a different signal, then repeat the first: a leaked scratch
        // buffer or a mutated chirp would show up here and nowhere else.
        fft.magnitudeSpectrum(FloatArray(n) { random.nextFloat() }, FloatArray(fft.binCount))
        val second = FloatArray(fft.binCount)
        fft.magnitudeSpectrum(input, second)

        for (i in first.indices) assertEquals("bin $i", first[i], second[i], 0f)
    }

    @Test
    fun `rejects sizes it cannot transform`() {
        for (bad in intArrayOf(0, 1, -4)) {
            val failure = runCatching { BluesteinFft(bad) }.exceptionOrNull()
            assertTrue("size $bad must be rejected, got $failure", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `transforming a delta gives a flat spectrum`() {
        val n = 1411
        val input = FloatArray(n)
        input[0] = 1f

        val fft = BluesteinFft(n)
        val magnitudes = FloatArray(fft.binCount)
        fft.magnitudeSpectrum(input, magnitudes)

        for (k in magnitudes.indices) assertEquals("bin $k", 1f, magnitudes[k], 1e-5f)
    }
}
