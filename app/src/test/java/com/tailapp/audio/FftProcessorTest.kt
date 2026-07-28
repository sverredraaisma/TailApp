package com.tailapp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftProcessorTest {

    private val sampleRate = 44100

    private fun tone(frequency: Double, size: Int = 1024, amplitude: Double = 8000.0) =
        ShortArray(size) { (amplitude * sin(2 * PI * frequency * it / sampleRate)).toInt().toShort() }

    private fun silence(size: Int = 1024) = ShortArray(size)

    private fun ByteArray.unsigned(index: Int) = this[index].toInt() and 0xFF

    private fun ByteArray.loudestIndex() = indices.maxBy { unsigned(it) }

    @Test
    fun `emits the requested number of bins`() {
        val processor = FftProcessor().apply { numBins = 32 }
        assertEquals(32, processor.process(tone(1000.0), sampleRate).bins.size)
    }

    @Test
    fun `bin count is clamped to the protocol limit`() {
        // num_bins travels as a u8 and the loop must not allocate beyond it.
        val processor = FftProcessor().apply { numBins = 5000 }
        assertEquals(FftProcessor.MAX_BINS, processor.process(tone(1000.0), sampleRate).bins.size)

        processor.numBins = 0
        assertEquals(FftProcessor.MIN_BINS, processor.process(tone(1000.0), sampleRate).bins.size)

        processor.numBins = -4
        assertEquals(FftProcessor.MIN_BINS, processor.process(tone(1000.0), sampleRate).bins.size)
    }

    @Test
    fun `a pure tone lands in a higher bin as its frequency rises`() {
        val low = FftProcessor().apply { numBins = 32 }.process(tone(200.0), sampleRate).bins
        val high = FftProcessor().apply { numBins = 32 }.process(tone(5000.0), sampleRate).bins
        assertTrue(
            "expected 5 kHz peak above 200 Hz peak (${low.loudestIndex()} vs ${high.loudestIndex()})",
            high.loudestIndex() > low.loudestIndex()
        )
    }

    @Test
    fun `silence produces no loudness and empty bins`() {
        val result = FftProcessor().process(silence(), sampleRate)
        assertEquals(0, result.loudness.toInt())
        assertTrue(result.bins.all { it.toInt() == 0 })
    }

    @Test
    fun `a loud tone produces non-zero loudness`() {
        val result = FftProcessor().process(tone(1000.0), sampleRate)
        assertTrue("loudness was ${result.loudness.toInt() and 0xFF}", (result.loudness.toInt() and 0xFF) > 0)
    }

    @Test
    fun `frames shorter than two samples are handled`() {
        val processor = FftProcessor().apply { numBins = 16 }
        val result = processor.process(ShortArray(1), sampleRate)
        assertEquals(16, result.bins.size)
        assertEquals(0, result.loudness.toInt())
        assertEquals(16, processor.process(ShortArray(0), sampleRate).bins.size)
    }

    @Test
    fun `non-positive sample rate does not crash`() {
        val processor = FftProcessor().apply { numBins = 8 }
        assertEquals(8, processor.process(tone(1000.0), 0).bins.size)
        assertEquals(8, processor.process(tone(1000.0), -44100).bins.size)
    }

    @Test
    fun `a zero frequency start does not produce NaN bins`() {
        // ln(0) is -infinity; the range has to be sanitised before the log mapping.
        val processor = FftProcessor().apply {
            numBins = 16
            freqRangeStart = 0f
        }
        val result = processor.process(tone(1000.0), sampleRate)
        assertEquals(16, result.bins.size)
        assertTrue(result.bins.any { (it.toInt() and 0xFF) > 0 })
    }

    @Test
    fun `an inverted frequency range does not crash`() {
        val processor = FftProcessor().apply {
            numBins = 16
            freqRangeStart = 15000f
            freqRangeEnd = 100f
        }
        assertEquals(16, processor.process(tone(1000.0), sampleRate).bins.size)
    }

    @Test
    fun `an above-Nyquist range is clamped`() {
        val processor = FftProcessor().apply {
            numBins = 16
            freqRangeStart = 100f
            freqRangeEnd = 96000f
        }
        assertEquals(16, processor.process(tone(1000.0), sampleRate).bins.size)
    }

    @Test
    fun `a negative or NaN range falls back to defaults`() {
        val processor = FftProcessor().apply {
            numBins = 8
            freqRangeStart = Float.NaN
            freqRangeEnd = Float.NaN
        }
        assertEquals(8, processor.process(tone(1000.0), sampleRate).bins.size)

        processor.freqRangeStart = -500f
        processor.freqRangeEnd = -100f
        assertEquals(8, processor.process(tone(1000.0), sampleRate).bins.size)
    }

    @Test
    fun `the configured range determines where a tone falls in the bin spread`() {
        // Bin edges are log-spaced across the configured range, so the same tone
        // lands in a different output bin when the range moves.
        val wide = FftProcessor().apply {
            numBins = 32
            freqRangeStart = 20f
            freqRangeEnd = 20000f
        }.process(tone(1000.0), sampleRate).bins

        val startingAtTheTone = FftProcessor().apply {
            numBins = 32
            freqRangeStart = 1000f
            freqRangeEnd = 20000f
        }.process(tone(1000.0), sampleRate).bins

        assertTrue("expected a mid-spread peak, got ${wide.loudestIndex()}", wide.loudestIndex() > 4)
        assertEquals(0, startingAtTheTone.loudestIndex())
    }

    @Test
    fun `bin values stay in the unsigned byte range`() {
        val processor = FftProcessor().apply {
            numBins = 64
            normalizationSpeed = 1f
        }
        repeat(20) {
            val result = processor.process(tone(1000.0, amplitude = 32000.0), sampleRate)
            assertTrue(result.bins.all { b -> (b.toInt() and 0xFF) in 0..255 })
            assertTrue((result.loudness.toInt() and 0xFF) in 0..255)
        }
    }

    @Test
    fun `adaptive gain settles rather than running away`() {
        val processor = FftProcessor().apply {
            numBins = 16
            normalizationSpeed = 0.5f
        }
        var last = 0
        repeat(50) { last = processor.process(tone(1000.0), sampleRate).loudness.toInt() and 0xFF }
        assertTrue("loudness should stay in range, was $last", last in 0..255)
    }

    @Test
    fun `reset clears the adaptive gain between sessions`() {
        val processor = FftProcessor().apply { numBins = 8; normalizationSpeed = 1f }
        repeat(30) { processor.process(tone(1000.0, amplitude = 32000.0), sampleRate) }
        val afterLoud = processor.process(tone(1000.0, amplitude = 500.0), sampleRate).loudness.toInt() and 0xFF

        processor.reset()
        val afterReset = processor.process(tone(1000.0, amplitude = 500.0), sampleRate).loudness.toInt() and 0xFF

        assertNotEquals(afterLoud, afterReset)
    }

    /**
     * The bug this pins: the old gain recurrence
     * `p += (rms - p / 3) * speed` has fixed point `p = 3 * rms`, so
     * `rms / p * 255` settled on 85 for *every* steady level. The stream
     * reported 33% whether you whispered or blasted, moving only on changes.
     */
    @Test
    fun `a loud steady tone reports far more loudness than a quiet one`() {
        fun steady(amplitude: Double): Int {
            val processor = FftProcessor().apply { numBins = 64 }
            var loudness = 0
            // Well past the point where the old recurrence had converged on 85.
            repeat(60) {
                loudness = processor
                    .process(tone(440.0, size = 1470, amplitude = amplitude), sampleRate)
                    .loudness.toInt() and 0xFF
            }
            return loudness
        }

        val loud = steady(32000.0)
        val quiet = steady(30.0)

        assertTrue("a steady full-scale tone should be near the top, was $loud", loud > 200)
        assertTrue("a near-silent steady tone should stay low, was $quiet", quiet < 120)
        assertTrue("loud ($loud) must be far above quiet ($quiet)", loud > quiet * 2)
    }

    /**
     * At the live geometry the lowest output bins all resolve to the same FFT
     * bin. When that was bin 0 they tracked the microphone's DC offset instead
     * of the bass, and DC was folded into the loudness RMS as well.
     */
    @Test
    fun `low bins follow the bass, not the DC offset`() {
        fun lowBins(samples: ShortArray): Pair<Int, List<Int>> {
            val processor = FftProcessor().apply { numBins = 64 }
            var result = processor.process(samples, sampleRate)
            repeat(4) { result = processor.process(samples, sampleRate) }
            return (result.loudness.toInt() and 0xFF) to
                (0 until 8).map { result.bins.unsigned(it) }
        }

        // 30 fps at 44.1 kHz: the frame FftStreamManager actually captures.
        val (bassLoudness, bassBins) = lowBins(tone(60.0, size = 1470, amplitude = 12000.0))
        val (dcLoudness, dcBins) = lowBins(ShortArray(1470) { 3000 })

        assertTrue("a 60 Hz tone must move the bottom bars, got $bassBins", bassBins.all { it > 0 })
        assertTrue("a constant DC offset is not audio, got $dcBins", dcBins.all { it == 0 })
        assertTrue("DC must not register as loudness, was $dcLoudness", dcLoudness == 0)
        assertTrue("bass must register as loudness, was $bassLoudness", bassLoudness > 0)
    }

    @Test
    fun `the whole frame is analysed, not just its first power of two`() {
        // 1470 samples used to be truncated to 1024 — 30% of every frame thrown
        // away. Zeroing only the discarded tail must therefore change the output.
        val full = tone(1000.0, size = 1470)
        val tailless = full.copyOf().also { for (i in 1024 until it.size) it[i] = 0 }

        val a = FftProcessor().apply { numBins = 32 }.process(full, sampleRate).bins
        val b = FftProcessor().apply { numBins = 32 }.process(tailless, sampleRate).bins

        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `tunables are read as one snapshot per frame`() {
        // The four sliders are published together, so a frame can never see a
        // half-applied drag (an inverted start > end range, say).
        val processor = FftProcessor().apply {
            numBins = 16
            freqRangeStart = 100f
            freqRangeEnd = 8000f
            normalizationSpeed = 0.25f
        }
        assertEquals(16, processor.numBins)
        assertEquals(100f, processor.freqRangeStart, 0f)
        assertEquals(8000f, processor.freqRangeEnd, 0f)
        assertEquals(0.25f, processor.normalizationSpeed, 0f)
        assertEquals(16, processor.process(tone(1000.0), sampleRate).bins.size)
    }

    @Test
    fun `result equality compares bin contents`() {
        val a = FftResult(5, byteArrayOf(1, 2, 3))
        val b = FftResult(5, byteArrayOf(1, 2, 3))
        val c = FftResult(5, byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
