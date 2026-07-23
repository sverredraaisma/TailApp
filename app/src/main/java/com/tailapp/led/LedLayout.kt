package com.tailapp.led

/**
 * Builds the normalised coordinate map for a ring/LED layout.
 *
 * Mirrors `LedMatrix::configure` (`main/led/led_matrix.cpp`): rings are evenly
 * spaced on the y axis (`r / (numRings - 1)`), and each ring's LEDs are evenly
 * spaced on the x axis (`l / (ledsInRing - 1)`) independently of every other
 * ring. Both axes fall back to the ring/strip midpoint (`0.5`) when there is
 * only one element to space, since a `0 / 0` division is undefined.
 */
object LedLayout {
    fun coordsFor(ledsPerRing: List<Int>): List<LedCoord> {
        val numRings = ledsPerRing.size
        val coords = ArrayList<LedCoord>(ledsPerRing.sum())

        for (r in 0 until numRings) {
            val y = if (numRings == 1) 0.5f else r / (numRings - 1).toFloat()

            val count = ledsPerRing[r]
            for (l in 0 until count) {
                val x = if (count == 1) 0.5f else l / (count - 1).toFloat()
                coords.add(LedCoord(x, y))
            }
        }

        return coords
    }
}
