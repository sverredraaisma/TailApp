package com.tailapp.drop

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mean and standard deviation over a fixed-length trailing window, updated in
 * O(1) per sample.
 *
 * The transient tier compares everything against its own recent history rather
 * than against absolute levels, so that a quiet living room and a loud club need
 * the same code and no per-genre threshold table.
 *
 * Running sums are corrected when the window is full: subtracting the evicted
 * sample from a `Double` accumulator is exact enough over the tens of thousands
 * of updates a session sees, and the window is re-summed periodically anyway to
 * stop error accumulating over a long set.
 *
 * @param windowSize samples retained. Must be positive.
 */
class RollingStats(val windowSize: Int) {
    init {
        require(windowSize > 0) { "windowSize must be positive" }
    }

    private val values = FloatArray(windowSize)
    private var writeIndex = 0
    private var filled = 0
    private var sum = 0.0
    private var sumSquares = 0.0
    private var updatesSinceResum = 0

    /** Samples currently in the window. */
    val count: Int get() = filled

    /** True once the window holds at least [minimum] samples. */
    fun isWarm(minimum: Int): Boolean = filled >= minimum

    val mean: Float get() = if (filled == 0) 0f else (sum / filled).toFloat()

    val standardDeviation: Float
        get() {
            if (filled < 2) return 0f
            val variance = (sumSquares / filled) - (sum / filled) * (sum / filled)
            return if (variance <= 0.0) 0f else sqrt(variance).toFloat()
        }

    /** The most recently added sample, or 0 when empty. */
    val latest: Float
        get() = if (filled == 0) 0f else values[(writeIndex - 1 + windowSize) % windowSize]

    fun add(value: Float) {
        if (filled == windowSize) {
            val evicted = values[writeIndex]
            sum -= evicted
            sumSquares -= evicted.toDouble() * evicted
        } else {
            filled++
        }
        values[writeIndex] = value
        sum += value
        sumSquares += value.toDouble() * value
        writeIndex = (writeIndex + 1) % windowSize

        if (++updatesSinceResum >= RESUM_INTERVAL) resum()
    }

    /**
     * How many standard deviations [value] sits above the window mean.
     *
     * Returns 0 for a window with no spread — a perfectly steady signal has no
     * scale to measure a deviation against, and treating that as "infinitely
     * surprising" would make the detector fire on the first sample of noise.
     */
    fun zScore(value: Float): Float {
        val sd = standardDeviation
        if (sd <= EPSILON) return 0f
        return (value - mean) / sd
    }

    /** Mean of the [n] most recent samples, or of everything held if fewer exist. */
    fun recentMean(n: Int): Float {
        val take = minOf(n, filled)
        if (take <= 0) return 0f
        var total = 0.0
        for (i in 1..take) {
            total += values[(writeIndex - i + windowSize) % windowSize]
        }
        return (total / take).toFloat()
    }

    /**
     * Mean of the [n] samples ending [skip] samples ago — the "before" figure a
     * rising edge is measured against.
     */
    fun meanBefore(skip: Int, n: Int): Float {
        if (filled <= skip) return 0f
        val take = minOf(n, filled - skip)
        if (take <= 0) return 0f
        var total = 0.0
        for (i in 1..take) {
            total += values[(writeIndex - skip - i + windowSize) % windowSize]
        }
        return (total / take).toFloat()
    }

    fun reset() {
        values.fill(0f)
        writeIndex = 0
        filled = 0
        sum = 0.0
        sumSquares = 0.0
        updatesSinceResum = 0
    }

    private fun resum() {
        var s = 0.0
        var sq = 0.0
        for (i in 0 until filled) {
            val v = values[i]
            s += v
            sq += v.toDouble() * v
        }
        sum = s
        sumSquares = max(sq, 0.0)
        updatesSinceResum = 0
    }

    private companion object {
        const val EPSILON = 1e-6f

        /** Rebuild the accumulators this often to keep drift from long sessions out. */
        const val RESUM_INTERVAL = 4096
    }
}
