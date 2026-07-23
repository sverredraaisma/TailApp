package com.tailapp.audio

import com.tailapp.audio.dsp.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class FftTest {

    @Test
    fun `impulse produces a flat magnitude spectrum`() {
        val size = 64
        val fft = Fft(size)
        val re = FloatArray(size)
        val im = FloatArray(size)
        re[0] = 1f

        fft.transform(re, im)

        for (k in 0 until size) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            assertEquals("bin $k", 1f, mag, 1e-4f)
        }
    }

    @Test
    fun `sine at an exact bin frequency concentrates energy in that bin`() {
        val size = 1024
        val binIndex = 40
        val re = FloatArray(size) { i -> sin(2.0 * PI * binIndex * i / size).toFloat() }
        val im = FloatArray(size)
        val fft = Fft(size)

        fft.transform(re, im)

        var peakBin = -1
        var peakMag = -1f
        for (k in 0 until size / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            if (mag > peakMag) {
                peakMag = mag
                peakBin = k
            }
        }
        assertEquals(binIndex, peakBin)

        // An exact-bin sine has no spectral leakage: every bin other than the
        // peak and its negative-frequency mirror should be ~zero.
        for (k in 0 until size) {
            if (k == binIndex || k == size - binIndex) continue
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            assertTrue("bin $k leaked $mag (peak was $peakMag)", mag < 0.05f)
        }
    }

    @Test
    fun `Parseval energy is conserved between time and frequency domains`() {
        val size = 512
        val random = Random(1)
        val re = FloatArray(size) { random.nextFloat() * 2f - 1f }
        val im = FloatArray(size)

        var timeEnergy = 0.0
        for (v in re) timeEnergy += v.toDouble() * v

        val fft = Fft(size)
        fft.transform(re, im)

        var freqEnergy = 0.0
        for (k in 0 until size) freqEnergy += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        freqEnergy /= size

        assertEquals(timeEnergy, freqEnergy, timeEnergy * 0.02)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non power-of-two size`() {
        Fft(100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `transform rejects mismatched array lengths`() {
        val fft = Fft(64)
        fft.transform(FloatArray(64), FloatArray(32))
    }
}
