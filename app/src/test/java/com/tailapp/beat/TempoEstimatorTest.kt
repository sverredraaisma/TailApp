package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tempo estimation on synthetic activation trains.
 *
 * The ±2 BPM target is tight relative to the lag grid — at 50 frames a second,
 * one frame of lag is worth about 5 BPM near 128 — so these tests are really
 * checking that the parabolic peak interpolation works.
 */
class TempoEstimatorTest {

    private val config = FeatureConfig()

    private fun estimateFor(bpm: Float, seconds: Float = 20f, noise: Float = 0.05f): TempoEstimator {
        val signals = BeatTestSignals(config)
        val estimator = TempoEstimator(config)
        signals.activationTrain(bpm, seconds, noise).forEach { estimator.update(it) }
        return estimator
    }

    @Test
    fun `nothing is reported before enough history has arrived`() {
        val signals = BeatTestSignals(config)
        val estimator = TempoEstimator(config)

        // One second: far short of the minimum window.
        signals.activationTrain(128f, 1f).forEach { estimator.update(it) }

        assertFalse(estimator.hasEstimate)
        assertEquals(0f, estimator.bpm, 0f)
    }

    @Test
    fun `tracks 90 BPM within two BPM`() {
        val estimator = estimateFor(90f)
        assertEquals(90f, estimator.bpm, 2f)
    }

    @Test
    fun `tracks 128 BPM within two BPM`() {
        val estimator = estimateFor(128f)
        assertEquals(128f, estimator.bpm, 2f)
    }

    @Test
    fun `tracks 174 BPM within two BPM`() {
        val estimator = estimateFor(174f)
        assertEquals(174f, estimator.bpm, 2f)
    }

    @Test
    fun `confidence is higher for a clean grid than for noise`() {
        val clean = estimateFor(128f, noise = 0.02f)

        val noisy = TempoEstimator(config)
        val random = kotlin.random.Random(3)
        repeat((20 * config.framesPerSecond).toInt()) { noisy.update(random.nextFloat()) }

        assertTrue(
            "clean=${clean.confidence} noisy=${noisy.confidence}",
            clean.confidence > noisy.confidence
        )
    }

    @Test
    fun `follows a tempo change within a few seconds`() {
        val signals = BeatTestSignals(config)
        val estimator = TempoEstimator(config)

        signals.activationTrain(128f, 20f).forEach { estimator.update(it) }
        val before = estimator.bpm
        assertEquals(128f, before, 2f)

        // The autocorrelation window is 8 s, so a new tempo cannot dominate it
        // before roughly that much of the new material has arrived.
        signals.activationTrain(150f, 14f).forEach { estimator.update(it) }

        assertEquals("did not follow the change (was $before)", 150f, estimator.bpm, 3f)
    }

    @Test
    fun `hysteresis keeps the estimate steady through a moment of ambiguity`() {
        val signals = BeatTestSignals(config)
        val estimator = TempoEstimator(config)
        signals.activationTrain(128f, 20f).forEach { estimator.update(it) }
        val locked = estimator.bpm

        // Two seconds of noise: not enough evidence to justify moving.
        val random = kotlin.random.Random(5)
        repeat((2 * config.framesPerSecond).toInt()) { estimator.update(random.nextFloat()) }

        assertTrue(
            "estimate wandered from $locked to ${estimator.bpm}",
            abs(estimator.bpm - locked) < 4f
        )
    }

    @Test
    fun `a refinement close to the current period nudges it, a wild one is ignored`() {
        val estimator = estimateFor(128f)
        val period = estimator.periodFrames

        estimator.refinePeriod(period * 1.02f)
        val nudged = estimator.periodFrames
        assertTrue("a plausible observation should move the estimate", nudged > period)

        estimator.refinePeriod(period * 2f)
        assertEquals("a wild observation means a lost beat, not a tempo change", nudged, estimator.periodFrames, 1e-4f)
    }

    @Test
    fun `reset clears the estimate`() {
        val estimator = estimateFor(128f)
        assertTrue(estimator.hasEstimate)

        estimator.reset()

        assertFalse(estimator.hasEstimate)
        assertEquals(0f, estimator.bpm, 0f)
    }
}
