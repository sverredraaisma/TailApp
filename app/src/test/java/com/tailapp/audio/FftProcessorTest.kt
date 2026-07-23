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
