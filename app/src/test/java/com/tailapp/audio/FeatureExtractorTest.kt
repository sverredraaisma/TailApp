package com.tailapp.audio

import com.tailapp.audio.dsp.LogFilterbank
import com.tailapp.testutil.SyntheticAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FeatureExtractorTest {

    private val config = FeatureConfig()

    @Test
    fun `frame count matches hop math`() {
        val extractor = FeatureExtractor(config)
        val samples = SyntheticAudio.sine(440f, 2f, config.sampleRate)

        val frames = extractor.push(samples, samples.size, endTimestampNanos = 0L)

        val expected = (samples.size - config.frameSize) / config.hopSize + 1
        assertEquals(expected, frames.size)
    }

    @Test
    fun `pushes smaller than one hop emit nothing until a hop completes`() {
        val extractor = FeatureExtractor(config)
        val tiny = SyntheticAudio.sine(440f, 0.01f, config.sampleRate) // well under hopSize samples

        val frames = extractor.push(tiny, tiny.size, endTimestampNanos = 0L)

        assertTrue(frames.isEmpty())
    }

    @Test
    fun `chunk boundary independence produces identical frames`() {
        val samples = SyntheticAudio.concat(
            SyntheticAudio.sine(300f, 0.2f, config.sampleRate),
            SyntheticAudio.clickTrack(120f, 0.2f, config.sampleRate)
        )

        // A consistent audio clock: the timestamp for "count samples have
        // arrived" is the same function regardless of how those samples were
        // chunked into push() calls.
        fun timestampFor(sampleCount: Int): Long = (sampleCount.toLong() * 1_000_000_000L) / config.sampleRate

        val whole = FeatureExtractor(config)
        val wholeFrames = whole.push(samples, samples.size, timestampFor(samples.size))

        val chunked = FeatureExtractor(config)
        val chunkedFrames = mutableListOf<FeatureFrame>()
        var offset = 0
        while (offset < samples.size) {
            val take = minOf(37, samples.size - offset)
            val chunk = samples.copyOfRange(offset, offset + take)
            offset += take
            chunkedFrames += chunked.push(chunk, take, timestampFor(offset))
        }

        assertEquals(wholeFrames.size, chunkedFrames.size)
        for (i in wholeFrames.indices) {
            val a = wholeFrames[i]
            val b = chunkedFrames[i]
            // Timestamps are derived from independently rounded per-chunk
            // clocks (see timestampFor), so allow the odd nanosecond of
            // truncation slack; the hop-spacing test below checks exactness.
            assertTrue("frame $i timestamp ${a.timestampNanos} vs ${b.timestampNanos}", abs(a.timestampNanos - b.timestampNanos) <= 1)
            assertEquals("frame $i flux", a.flux, b.flux, 1e-5f)
            assertEquals("frame $i rms", a.rms, b.rms, 1e-6f)
            assertEquals("frame $i bass", a.bassEnergy, b.bassEnergy, 1e-6f)
            assertEquals("frame $i mid", a.midEnergy, b.midEnergy, 1e-6f)
            assertEquals("frame $i high", a.highEnergy, b.highEnergy, 1e-6f)
            assertEquals("frame $i centroid", a.spectralCentroidHz, b.spectralCentroidHz, 1e-3f)
            assertArrayEquals("frame $i bands", a.bands, b.bands, 1e-5f)
        }
    }

    @Test
    fun `silence yields zero bands, flux and rms`() {
        val extractor = FeatureExtractor(config)
        val samples = SyntheticAudio.silence(1f, config.sampleRate)

        val frames = extractor.push(samples, samples.size, endTimestampNanos = 0L)

        assertTrue(frames.isNotEmpty())
        val expectedSilentBand = kotlin.math.log10(config.logAdd.toDouble()).toFloat() // log10(mul*0 + add)
        for (f in frames) {
            assertEquals(0f, f.rms, 1e-6f)
            assertEquals(0f, f.flux, 1e-6f)
            assertEquals(0f, f.bassEnergy, 1e-6f)
            assertEquals(0f, f.midEnergy, 1e-6f)
            assertEquals(0f, f.highEnergy, 1e-6f)
            for (b in f.bands) assertEquals(expectedSilentBand, b, 1e-5f)
        }
    }

    @Test
    fun `1 kHz sine peaks in the expected band with a matching centroid`() {
        val extractor = FeatureExtractor(config)
        val samples = SyntheticAudio.sine(1000f, 1f, config.sampleRate, amplitude = 0.8f)

        val frames = extractor.push(samples, samples.size, endTimestampNanos = 0L)
        val frame = frames.last()

        var peak = 0
        for (i in frame.bands.indices) if (frame.bands[i] > frame.bands[peak]) peak = i

        val filterbank = LogFilterbank(config.sampleRate, config.frameSize, config.bandsPerOctave, config.fMin, config.fMax)
        val peakCenter = filterbank.centerFrequenciesHz[peak]

        assertTrue("peak band at $peakCenter Hz, expected near 1000 Hz", abs(peakCenter - 1000f) < 150f)
        assertTrue("centroid ${frame.spectralCentroidHz} Hz, expected near 1000 Hz", abs(frame.spectralCentroidHz - 1000f) < 150f)
    }

    @Test
    fun `flux spikes on an amplitude step and stays low on a steady tone`() {
        val extractor = FeatureExtractor(config)
        val samples = SyntheticAudio.concat(
            SyntheticAudio.sine(500f, 1f, config.sampleRate, amplitude = 0.1f),
            SyntheticAudio.sine(500f, 1f, config.sampleRate, amplitude = 0.9f)
        )

        val frames = extractor.push(samples, samples.size, endTimestampNanos = 0L)

        // Frames well inside the first (quiet, steady) second, away from
        // frame 0 (whose flux is 0 by definition) and away from the step.
        val steady = frames.subList(5, 20)
        for (f in steady) assertTrue("steady flux was ${f.flux}", f.flux < 0.05f)

        val maxFlux = frames.maxOf { it.flux }
        assertTrue("expected a flux spike at the amplitude step, max was $maxFlux", maxFlux > 0.3f)
    }

    @Test
    fun `consecutive frame timestamps differ by exactly one hop`() {
        val extractor = FeatureExtractor(config)
        val samples = SyntheticAudio.sine(220f, 1f, config.sampleRate)

        val frames = extractor.push(samples, samples.size, endTimestampNanos = 10_000_000_000L)

        val hopNanos = (1_000_000_000L * config.hopSize) / config.sampleRate
        for (i in 1 until frames.size) {
            assertEquals(hopNanos, frames[i].timestampNanos - frames[i - 1].timestampNanos)
        }
    }

    @Test
    fun `reset starts over`() {
        val extractor = FeatureExtractor(config)
        val samples = SyntheticAudio.sine(300f, 0.5f, config.sampleRate)

        val frames1 = extractor.push(samples, samples.size, endTimestampNanos = 0L)
        extractor.reset()
        val frames2 = extractor.push(samples, samples.size, endTimestampNanos = 0L)

        assertEquals(frames1.size, frames2.size)
        assertEquals(0L, frames2.first().index)
        for (i in frames1.indices) {
            assertEquals(frames1[i].timestampNanos, frames2[i].timestampNanos)
            assertArrayEquals(frames1[i].bands, frames2[i].bands, 1e-6f)
        }
    }
}
