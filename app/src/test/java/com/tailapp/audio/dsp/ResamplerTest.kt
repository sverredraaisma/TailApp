package com.tailapp.audio.dsp

import com.tailapp.testutil.SyntheticAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ResamplerTest {

    @Test
    fun `44100 to 22050 halves the length`() {
        val input = SyntheticAudio.sine(440f, 1f, 44100)
        val resampler = Resampler(44100, 22050)
        val out = FloatArray(input.size)

        val written = resampler.resample(input, input.size, out)

        assertTrue("written=$written, input=${input.size}", abs(written - input.size / 2) <= 2)
    }

    @Test
    fun `440 Hz sine stays 440 Hz after resampling`() {
        val inRate = 44100
        val outRate = 22050
        val input = SyntheticAudio.sine(440f, 1f, inRate)
        val resampler = Resampler(inRate, outRate)
        val out = FloatArray(input.size)

        val written = resampler.resample(input, input.size, out)

        val fftSize = Integer.highestOneBit(written)
        val fft = Fft(fftSize)
        val re = out.copyOf(fftSize)
        val im = FloatArray(fftSize)
        fft.transform(re, im)

        var peakBin = 1
        var peakMag = -1f
        for (k in 1 until fftSize / 2) {
            val mag = re[k] * re[k] + im[k] * im[k]
            if (mag > peakMag) {
                peakMag = mag
                peakBin = k
            }
        }
        val peakHz = peakBin.toFloat() * outRate / fftSize
        assertEquals(440f, peakHz, 30f)
    }

    @Test
    fun `resampling in 37-sample chunks matches one block, sample for sample`() {
        val input = SyntheticAudio.concat(
            SyntheticAudio.sine(440f, 0.3f, 44100),
            SyntheticAudio.whiteNoise(0.3f, 44100, amplitude = 0.3f, seed = 5)
        )

        val whole = Resampler(44100, 22050)
        val wholeOut = FloatArray(input.size)
        val wholeWritten = whole.resample(input, input.size, wholeOut)

        val chunked = Resampler(44100, 22050)
        val chunkedOut = FloatArray(input.size)
        val scratch = FloatArray(input.size)
        var writtenTotal = 0
        var offset = 0
        while (offset < input.size) {
            val take = minOf(37, input.size - offset)
            val chunk = input.copyOfRange(offset, offset + take)
            val n = chunked.resample(chunk, take, scratch)
            System.arraycopy(scratch, 0, chunkedOut, writtenTotal, n)
            writtenTotal += n
            offset += take
        }

        assertEquals(wholeWritten, writtenTotal)
        for (i in 0 until wholeWritten) {
            assertEquals("sample $i", wholeOut[i], chunkedOut[i], 1e-4f)
        }
    }

    @Test
    fun `reset returns to a fresh state`() {
        val input = SyntheticAudio.sine(440f, 0.1f, 44100)

        val warmed = Resampler(44100, 22050)
        warmed.resample(input, input.size, FloatArray(input.size))
        warmed.reset()
        val afterReset = FloatArray(input.size)
        val writtenAfterReset = warmed.resample(input, input.size, afterReset)

        val fresh = Resampler(44100, 22050)
        val freshOut = FloatArray(input.size)
        val writtenFresh = fresh.resample(input, input.size, freshOut)

        assertEquals(writtenFresh, writtenAfterReset)
        for (i in 0 until writtenFresh) {
            assertEquals(freshOut[i], afterReset[i], 1e-6f)
        }
    }

    @Test
    fun `zero count is a no-op`() {
        val resampler = Resampler(44100, 22050)
        val out = FloatArray(10)
        val written = resampler.resample(FloatArray(0), 0, out)
        assertEquals(0, written)
    }
}
