package com.tailapp.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class RollingStatsTest {

    @Test
    fun `mean and deviation over a partial window`() {
        val stats = RollingStats(10)
        listOf(2f, 4f, 4f, 4f, 5f, 5f, 7f, 9f).forEach(stats::add)

        assertEquals(8, stats.count)
        assertEquals(5f, stats.mean, 1e-4f)
        assertEquals(2f, stats.standardDeviation, 1e-4f)
    }

    @Test
    fun `old samples fall out of the window`() {
        val stats = RollingStats(4)
        listOf(100f, 100f, 100f, 100f).forEach(stats::add)
        assertEquals(100f, stats.mean, 1e-4f)

        listOf(0f, 0f, 0f, 0f).forEach(stats::add)

        assertEquals("the 100s should have been evicted", 0f, stats.mean, 1e-4f)
        assertEquals(4, stats.count)
    }

    @Test
    fun `z-score measures deviations from the window mean`() {
        val stats = RollingStats(100)
        repeat(50) { stats.add(10f) }
        repeat(50) { stats.add(20f) }

        // Mean 15, population sd 5.
        assertEquals(15f, stats.mean, 1e-3f)
        assertEquals(5f, stats.standardDeviation, 1e-3f)
        assertEquals(2f, stats.zScore(25f), 1e-3f)
        assertEquals(-1f, stats.zScore(10f), 1e-3f)
    }

    @Test
    fun `a window with no spread reports a zero z-score`() {
        val stats = RollingStats(20)
        repeat(20) { stats.add(3f) }

        // A perfectly steady signal offers no scale to measure surprise against;
        // reporting infinity here would make the detector fire on the first breath
        // of noise after silence.
        assertEquals(0f, stats.zScore(1000f), 0f)
    }

    @Test
    fun `warmth tracks how much history is held`() {
        val stats = RollingStats(10)
        assertFalse(stats.isWarm(5))
        repeat(4) { stats.add(1f) }
        assertFalse(stats.isWarm(5))
        stats.add(1f)
        assertTrue(stats.isWarm(5))
    }

    @Test
    fun `recentMean and meanBefore address the window from the newest end`() {
        val stats = RollingStats(10)
        (1..10).forEach { stats.add(it.toFloat()) }

        assertEquals(10f, stats.latest, 0f)
        assertEquals(9.5f, stats.recentMean(2), 1e-4f)
        assertEquals(8f, stats.recentMean(5), 1e-4f)
        // Three samples ending two samples back: 6, 7, 8.
        assertEquals(7f, stats.meanBefore(skip = 2, n = 3), 1e-4f)
    }

    @Test
    fun `recentMean clamps to what is held`() {
        val stats = RollingStats(10)
        stats.add(4f)
        stats.add(6f)

        assertEquals(5f, stats.recentMean(50), 1e-4f)
        assertEquals(0f, stats.meanBefore(skip = 5, n = 3), 0f)
    }

    @Test
    fun `reset empties the window`() {
        val stats = RollingStats(5)
        (1..5).forEach { stats.add(it.toFloat()) }
        stats.reset()

        assertEquals(0, stats.count)
        assertEquals(0f, stats.mean, 0f)
        assertEquals(0f, stats.standardDeviation, 0f)
        assertEquals(0f, stats.latest, 0f)
    }

    /**
     * The accumulators are incremental, so a long session must not let rounding
     * error drift the reported statistics away from a fresh recomputation.
     */
    @Test
    fun `statistics stay accurate over many updates`() {
        val window = 450
        val stats = RollingStats(window)
        val values = FloatArray(200_000) { 0.5f + 0.4f * kotlin.math.sin(it / 37.0).toFloat() }
        values.forEach(stats::add)

        val tail = values.takeLast(window)
        val expectedMean = tail.sum() / window
        val expectedSd = sqrt(tail.sumOf { d -> ((d - expectedMean).toDouble() * (d - expectedMean)) } / window)

        assertEquals(expectedMean, stats.mean, 1e-4f)
        assertEquals(expectedSd.toFloat(), stats.standardDeviation, 1e-4f)
    }
}
