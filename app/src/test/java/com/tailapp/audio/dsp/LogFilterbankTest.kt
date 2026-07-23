package com.tailapp.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

class LogFilterbankTest {

    private val sampleRate = 22050
    private val frameSize = 2048
    private val bandsPerOctave = 24
    private val fMin = 30f
    private val fMax = 17000f // exceeds Nyquist (11025 Hz) on purpose, mirrors FeatureConfig's defaults

    @Test
    fun `band count matches the log-spacing formula, clamped to Nyquist`() {
        val fb = LogFilterbank(sampleRate, frameSize, bandsPerOctave, fMin, fMax)

        val effectiveFMax = minOf(fMax, sampleRate / 2f)
        val octaves = ln((effectiveFMax / fMin).toDouble()) / ln(2.0)
        val expectedCount = floor(octaves * bandsPerOctave).toInt() + 1

        assertEquals(expectedCount, fb.bandCount)
        assertEquals(expectedCount, fb.centerFrequenciesHz.size)
    }

    @Test
    fun `smaller bandsPerOctave yields fewer bands`() {
        val coarse = LogFilterbank(sampleRate, frameSize, 6, fMin, fMax)
        val fine = LogFilterbank(sampleRate, frameSize, 24, fMin, fMax)
        assertTrue(coarse.bandCount < fine.bandCount)
    }

    @Test
    fun `centre frequencies are strictly increasing and span fMin to Nyquist-clamped fMax`() {
        val fb = LogFilterbank(sampleRate, frameSize, bandsPerOctave, fMin, fMax)
        val centers = fb.centerFrequenciesHz

        assertEquals(fMin, centers.first(), 0.01f)
        for (i in 1 until centers.size) {
            assertTrue("center[$i]=${centers[i]} should exceed center[${i - 1}]=${centers[i - 1]}", centers[i] > centers[i - 1])
        }
        assertTrue(centers.last() <= sampleRate / 2f)
        assertTrue("last center ${centers.last()} should be within an octave of Nyquist", centers.last() > sampleRate / 4f)
    }

    @Test
    fun `sine at a known frequency peaks in the band containing it`() {
        val fb = LogFilterbank(sampleRate, frameSize, bandsPerOctave, fMin, fMax)
        val fft = Fft(frameSize)

        val freq = 4000f // well above the low-octave sub-bin-collapse region
        val samples = FloatArray(frameSize) { i -> sin(2.0 * PI * freq * i / sampleRate).toFloat() }
        val magnitudes = FloatArray(fb.binCount)
        fft.magnitudeSpectrum(samples, magnitudes)

        val bands = FloatArray(fb.bandCount)
        fb.apply(magnitudes, bands)

        var peakIndex = 0
        for (i in bands.indices) if (bands[i] > bands[peakIndex]) peakIndex = i

        val peakCenter = fb.centerFrequenciesHz[peakIndex]
        assertTrue("peak band centred at $peakCenter Hz, expected near $freq Hz", abs(peakCenter - freq) < 200f)
    }

    @Test
    fun `silence produces all-zero bands`() {
        val fb = LogFilterbank(sampleRate, frameSize, bandsPerOctave, fMin, fMax)
        val magnitudes = FloatArray(fb.binCount)
        val bands = FloatArray(fb.bandCount)

        fb.apply(magnitudes, bands)

        for (b in bands) assertEquals(0f, b, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects fMax not greater than fMin`() {
        LogFilterbank(sampleRate, frameSize, bandsPerOctave, 100f, 100f)
    }
}
