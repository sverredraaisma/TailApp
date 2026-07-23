package com.tailapp.led

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `LedMatrix::configure` (`main/led/led_matrix.cpp`): rings spaced
 * evenly on y, LEDs within a ring spaced evenly on x, with the two `n==1`
 * special cases falling back to the midpoint.
 */
class LedLayoutTest {

    @Test
    fun `multi-ring spacing places rings evenly on y and leds evenly on x`() {
        val coords = LedLayout.coordsFor(listOf(2, 3))

        // Ring 0 (y=0): 2 LEDs at x=0, x=1.
        assertEquals(LedCoord(0.0f, 0.0f), coords[0])
        assertEquals(LedCoord(1.0f, 0.0f), coords[1])

        // Ring 1 (y=1): 3 LEDs at x=0, 0.5, 1.
        assertEquals(LedCoord(0.0f, 1.0f), coords[2])
        assertEquals(LedCoord(0.5f, 1.0f), coords[3])
        assertEquals(LedCoord(1.0f, 1.0f), coords[4])
    }

    @Test
    fun `five rings space evenly across the full y range`() {
        val coords = LedLayout.coordsFor(listOf(1, 1, 1, 1, 1))
        val ys = coords.map { it.y }
        assertEquals(listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f), ys)
    }

    @Test
    fun `single ring uses the y=0-5 special case instead of dividing by zero`() {
        val coords = LedLayout.coordsFor(listOf(4))
        assertTrue(coords.all { it.y == 0.5f })
    }

    @Test
    fun `single-led ring uses the x=0-5 special case instead of dividing by zero`() {
        val coords = LedLayout.coordsFor(listOf(1, 1, 1))
        assertEquals(listOf(0.5f, 0.5f, 0.5f), coords.map { it.x })
    }

    @Test
    fun `single ring with a single led is the center point`() {
        val coords = LedLayout.coordsFor(listOf(1))
        assertEquals(listOf(LedCoord(0.5f, 0.5f)), coords)
    }

    @Test
    fun `empty input returns an empty list`() {
        assertEquals(emptyList<LedCoord>(), LedLayout.coordsFor(emptyList()))
    }

    @Test
    fun `total coordinate count matches the sum of leds per ring`() {
        val ledsPerRing = listOf(8, 10, 12, 10, 8)
        assertEquals(ledsPerRing.sum(), LedLayout.coordsFor(ledsPerRing).size)
    }

    @Test
    fun `a ring with zero leds contributes no coordinates`() {
        val coords = LedLayout.coordsFor(listOf(2, 0, 2))
        assertEquals(4, coords.size)
    }
}
