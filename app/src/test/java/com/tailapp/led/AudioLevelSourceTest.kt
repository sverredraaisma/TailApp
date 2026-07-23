package com.tailapp.led

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `FftBuffer` (`main/config/fft_buffer.h` / `.cpp`): 200ms staleness
 * window, loudness/bin accessors that return 0 when stale.
 */
class AudioLevelSourceTest {

    private var now = 0L
    private val source = AudioLevelSource(clock = { now })

    @Test
    fun `is not fresh before any frame is written`() {
        assertFalse(source.isFresh)
        assertEquals(0, source.loudness)
        assertEquals(0.0f, source.loudnessNormalized, 0.0f)
    }

    @Test
    fun `is fresh immediately after a write`() {
        source.write(200, byteArrayOf(1, 2, 3))
        assertTrue(source.isFresh)
        assertEquals(200, source.loudness)
    }

    @Test
    fun `loudness normalized divides by 255`() {
        source.write(51, byteArrayOf())
        assertEquals(0.2f, source.loudnessNormalized, 1e-6f)
    }

    @Test
    fun `becomes stale after 200ms and reports zero`() {
        source.write(255, byteArrayOf(10, 20))
        now += 200_000_000L // exactly at the boundary: `< 200ms` excludes it
        assertFalse(source.isFresh)
        assertEquals(0, source.loudness)
        assertEquals(0, source.bin(0))
    }

    @Test
    fun `stays fresh just under the 200ms boundary`() {
        source.write(255, byteArrayOf(10, 20))
        now += 199_000_000L
        assertTrue(source.isFresh)
        assertEquals(255, source.loudness)
    }

    @Test
    fun `bin returns 0 for an out-of-range index`() {
        source.write(100, byteArrayOf(5, 6, 7))
        assertEquals(0, source.bin(-1))
        assertEquals(0, source.bin(3))
        assertEquals(6, source.bin(1))
    }

    @Test
    fun `bin values are treated as unsigned bytes`() {
        source.write(0, byteArrayOf(0xFF.toByte()))
        assertEquals(255, source.bin(0))
    }

    @Test
    fun `numBins does not gate on freshness, mirroring the firmware asymmetry`() {
        source.write(10, byteArrayOf(1, 2, 3, 4))
        now += 500_000_000L // well past stale
        assertFalse(source.isFresh)
        assertEquals(4, source.numBins) // still reports the last frame's bin count
        assertEquals(0, source.bin(0)) // but bin() itself is gated and returns 0
    }

    @Test
    fun `a write longer than MAX_FFT_BINS is truncated`() {
        source.write(1, ByteArray(200) { 1 })
        assertEquals(AudioLevelSource.MAX_FFT_BINS, source.numBins)
    }

    @Test
    fun `loudness is masked to a single byte like a uint8_t assignment`() {
        source.write(300, byteArrayOf()) // 300 and 0xFF = 44
        assertEquals(300 and 0xFF, source.loudness)
    }
}
