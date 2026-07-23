package com.tailapp.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transient tier end to end: statistics, drops and section state together. */
class TransientAnalyzerTest {

    private val config = TransientConfig()

    @Test
    fun `only stats-rate frames produce a result`() {
        val signals = TransientTestSignals()
        val analyzer = TransientAnalyzer(config, signals.config)
        val framesPerSample = (signals.config.framesPerSecond / config.statsRateHz).toInt()

        val results = (0 until framesPerSample * 4).count {
            analyzer.process(signals.frame(0.2f, 0.1f)) != null
        }

        assertEquals(4, results)
    }

    @Test
    fun `a drop is stamped with the section it came out of`() {
        val signals = TransientTestSignals()
        val analyzer = TransientAnalyzer(config, signals.config)

        // Quiet opening, then a filtered riser: energy and onsets climbing while
        // the low end stays out of the way.
        signals.steady(14f, rms = 0.06f, bass = 0.02f, flux = 0.01f)
            .forEach(analyzer::process)
        signals.ramp(
            14f,
            rmsFrom = 0.10f, rmsTo = 0.45f,
            bassFrom = 0.02f, bassTo = 0.03f,
            fluxFrom = 0.02f, fluxTo = 0.35f
        ).forEach(analyzer::process)
        val sectionBeforeDrop = analyzer.sectionState

        val drop = signals.steady(3f, rms = 1.0f, bass = 0.9f, flux = 0.4f)
            .firstNotNullOfOrNull { analyzer.process(it)?.drop }

        assertNotNull("expected the step after the riser to fire", drop)
        assertEquals(sectionBeforeDrop, drop!!.precededBy)
        assertEquals("the drop should move the section on", SectionState.DROP, analyzer.sectionState)
    }

    @Test
    fun `results always carry the live section, changes only on transitions`() {
        val signals = TransientTestSignals()
        val analyzer = TransientAnalyzer(config, signals.config)

        val results = (signals.steady(20f, rms = 0.3f, bass = 0.2f))
            .mapNotNull { analyzer.process(it) }

        assertTrue(results.isNotEmpty())
        assertTrue("every result carries a section", results.all { it.section.state == analyzer.sectionState })
        assertTrue(
            "section changes should be rare compared with samples",
            results.count { it.sectionChange != null } < results.size / 4
        )
    }

    @Test
    fun `reset clears both tiers`() {
        val signals = TransientTestSignals()
        val analyzer = TransientAnalyzer(config, signals.config)
        signals.steady(20f, rms = 0.3f, bass = 0.2f).forEach(analyzer::process)

        analyzer.reset()

        assertEquals(SectionState.UNKNOWN, analyzer.sectionState)
        assertTrue(!analyzer.snapshot.warm)
        assertNull(signals.steady(2f, rms = 1.2f, bass = 1.1f).firstNotNullOfOrNull { analyzer.process(it)?.drop })
    }
}
