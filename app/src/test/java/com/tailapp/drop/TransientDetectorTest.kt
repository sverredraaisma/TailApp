package com.tailapp.drop

import com.tailapp.audio.FeatureFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drop detector on synthetic energy profiles.
 *
 * Each test feeds a quiet stretch long enough to fill the trailing window, then
 * whatever it is testing, and checks what came back. Timings are asserted in
 * seconds from the step rather than in samples, so the assertions survive a
 * change to [TransientConfig.statsRateHz].
 */
class TransientDetectorTest {

    private val config = TransientConfig()

    private fun feed(detector: TransientDetector, frames: List<FeatureFrame>): List<DropEvent> =
        frames.mapNotNull { detector.process(it)?.drop }

    @Test
    fun `no drops before the window has warmed up`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        // A step this early cannot be judged: there is no history to judge against.
        val frames = signals.steady(2f, rms = 0.05f, bass = 0.02f) +
            signals.steady(2f, rms = 0.6f, bass = 0.5f)

        assertTrue(feed(detector, frames).isEmpty())
    }

    @Test
    fun `steady audio never fires`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        val drops = feed(detector, signals.steady(40f, rms = 0.3f, bass = 0.2f))

        assertTrue("steady audio produced ${drops.size} drops", drops.isEmpty())
    }

    @Test
    fun `silence never fires`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        val drops = feed(detector, signals.steady(30f, rms = 0f, bass = 0f, flux = 0f))

        assertTrue(drops.isEmpty())
    }

    @Test
    fun `a joint energy and bass step fires within a second of the step`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        feed(detector, signals.steady(20f, rms = 0.12f, bass = 0.05f))
        val stepStartNanos = signals.lastTimestampNanos
        val drops = feed(detector, signals.steady(5f, rms = 0.75f, bass = 0.7f))

        assertEquals("expected exactly one drop", 1, drops.size)
        val latencySeconds = (drops[0].timestampNanos - stepStartNanos) / 1e9
        assertTrue("fired $latencySeconds s after the step", latencySeconds <= 1.0)
        assertTrue("intensity should be above zero", drops[0].intensity > 0f)
        assertTrue(drops[0].broadbandZ >= config.dropBroadbandZ)
        assertTrue(drops[0].bassZ >= config.dropBassZ)
    }

    @Test
    fun `a treble-only spike does not fire`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        feed(detector, signals.steady(20f, rms = 0.12f, bass = 0.05f))
        // Broadband energy jumps but the low end does not follow — a crash cymbal,
        // a shout, a snare roll. Requiring both is the whole point.
        val drops = feed(detector, signals.steady(5f, rms = 0.75f, bass = 0.05f))

        assertTrue("treble-only spike produced ${drops.size} drops", drops.isEmpty())
    }

    @Test
    fun `a slow swell does not fire`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        feed(detector, signals.steady(20f, rms = 0.12f, bass = 0.05f))
        // Same destination as the step test, reached over 20 s. The z-scores get
        // there eventually, but the rise ratio against the preceding second does
        // not — which is exactly the difference between a drop and a fade-in.
        val drops = feed(
            detector,
            signals.ramp(20f, rmsFrom = 0.12f, rmsTo = 0.75f, bassFrom = 0.05f, bassTo = 0.7f)
        )

        assertTrue("a swell produced ${drops.size} drops", drops.isEmpty())
    }

    @Test
    fun `a second step inside the refractory window is suppressed`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        feed(detector, signals.steady(20f, rms = 0.12f, bass = 0.05f))
        val first = feed(detector, signals.steady(1f, rms = 0.75f, bass = 0.7f))
        feed(detector, signals.steady(1f, rms = 0.12f, bass = 0.05f))
        val duringRefractory = feed(detector, signals.steady(1f, rms = 0.9f, bass = 0.85f))

        assertEquals(1, first.size)
        assertTrue("fired again inside the refractory window", duringRefractory.isEmpty())
    }

    @Test
    fun `a step after the refractory window fires again`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        feed(detector, signals.steady(20f, rms = 0.12f, bass = 0.05f))
        val first = feed(detector, signals.steady(2f, rms = 0.75f, bass = 0.7f))
        feed(detector, signals.steady(config.dropRefractorySeconds + 2f, rms = 0.12f, bass = 0.05f))
        val second = feed(detector, signals.steady(2f, rms = 0.9f, bass = 0.85f))

        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    @Test
    fun `intensity scales with how far past the threshold the spike went`() {
        fun intensityFor(rms: Float, bass: Float): Float {
            val signals = TransientTestSignals()
            val detector = TransientDetector(config, signals.config)
            feed(detector, signals.steady(20f, rms = 0.12f, bass = 0.05f))
            return feed(detector, signals.steady(3f, rms = rms, bass = bass)).first().intensity
        }

        val modest = intensityFor(0.5f, 0.4f)
        val huge = intensityFor(2.5f, 2.2f)

        assertTrue("modest=$modest huge=$huge", huge > modest)
        assertTrue(modest in 0f..1f)
        assertEquals(1f, huge, 0.001f)
    }

    @Test
    fun `snapshots report warmth and plausible z-scores`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        // One stats sample's worth of frames, then the first snapshot exists.
        val framesPerSample = (signals.config.framesPerSecond / config.statsRateHz).toInt()
        val early = (1..framesPerSample).firstNotNullOfOrNull { detector.process(signals.frame(0.2f, 0.1f)) }
        assertNotNull("a full stats interval should produce a sample", early)
        // First sample of the session: nothing to compare against yet.
        assertTrue(!detector.snapshot.warm)

        feed(detector, signals.steady(20f, rms = 0.2f, bass = 0.1f))
        assertTrue("window should be warm after 20 s", detector.snapshot.warm)
        assertTrue("steady audio should sit near its own mean", kotlin.math.abs(detector.snapshot.rmsZ) < 3f)
    }

    @Test
    fun `only stats-rate frames produce a sample`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        val framesPerSample = (signals.config.framesPerSecond / config.statsRateHz).toInt()
        val samples = (0 until framesPerSample * 3).count {
            detector.process(signals.frame(0.2f, 0.1f)) != null
        }

        assertEquals(3, samples)
    }

    @Test
    fun `reset clears the history`() {
        val signals = TransientTestSignals()
        val detector = TransientDetector(config, signals.config)

        feed(detector, signals.steady(20f, rms = 0.12f, bass = 0.05f))
        detector.reset()
        assertTrue(!detector.snapshot.warm)

        // With the window emptied the same step must be ignored again until the
        // detector has re-earned its history.
        val drops = feed(detector, signals.steady(2f, rms = 0.75f, bass = 0.7f))
        assertTrue(drops.isEmpty())
        assertNull(detector.process(signals.frame(0.1f, 0.05f))?.drop)
    }
}
