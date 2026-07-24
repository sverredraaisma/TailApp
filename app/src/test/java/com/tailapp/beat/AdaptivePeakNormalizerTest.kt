package com.tailapp.beat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePeakNormalizerTest {

    @Test
    fun `the first value maps to its own peak`() {
        val norm = AdaptivePeakNormalizer()
        assertEquals(1f, norm.normalize(0.15f), 1e-4f)
    }

    @Test
    fun `a weak signal is stretched to full range`() {
        // The measured CRNN case: peaks ~0.15 over a ~0.07 floor. After the peak
        // is learned, the floor should sit well below the peak's 1.0, restoring a
        // contrast the raw 2:1 ratio never had at the top of the range.
        val norm = AdaptivePeakNormalizer(halfLifeSeconds = 2.5f, framesPerSecond = 50f)
        repeat(10) { norm.normalize(0.15f) } // establish the peak
        val peakOut = norm.normalize(0.15f)
        val floorOut = norm.normalize(0.07f)

        assertEquals("peak maps to 1", 1f, peakOut, 1e-3f)
        assertTrue("floor should map below the peak: $floorOut", floorOut < 0.6f)
        assertTrue("floor stays positive", floorOut > 0f)
    }

    @Test
    fun `absolute level does not matter, only the ratio`() {
        val quiet = AdaptivePeakNormalizer()
        val loud = AdaptivePeakNormalizer()
        // Same 2:1 shape at two very different levels gives the same output.
        val quietOut = quiet.normalize(0.02f).let { quiet.normalize(0.01f) }
        val loudOut = loud.normalize(0.8f).let { loud.normalize(0.4f) }
        assertEquals(quietOut, loudOut, 1e-4f)
    }

    @Test
    fun `the peak decays so a later louder beat is not permanently suppressed`() {
        val norm = AdaptivePeakNormalizer(halfLifeSeconds = 1f, framesPerSecond = 50f)
        norm.normalize(1.0f) // a loud transient sets a high peak
        // After a couple of seconds of quiet the reference has decayed a lot...
        repeat(120) { norm.normalize(0.001f) }
        // ...so a moderate beat now reads as a strong one again.
        assertTrue("a later beat should recover toward 1", norm.normalize(0.2f) > 0.5f)
    }

    @Test
    fun `zero in stays zero out`() {
        val norm = AdaptivePeakNormalizer()
        norm.normalize(0.3f)
        assertEquals(0f, norm.normalize(0f), 0f)
    }

    @Test
    fun `reset forgets the peak`() {
        val norm = AdaptivePeakNormalizer()
        norm.normalize(0.9f)
        norm.reset()
        assertEquals("after reset the next value defines the new peak", 1f, norm.normalize(0.1f), 1e-4f)
    }
}
