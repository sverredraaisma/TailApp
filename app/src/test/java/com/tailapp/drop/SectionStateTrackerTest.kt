package com.tailapp.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The section state machine, driven with synthetic stats-rate snapshots.
 *
 * Working in snapshots rather than audio keeps these tests about the state
 * machine's rules — debounce, hold, ramp — instead of about whether a particular
 * synthetic waveform happens to produce the right z-scores.
 */
class SectionStateTrackerTest {

    private val config = TransientConfig()

    /** Feeds snapshots at the configured stats rate, returning every committed change. */
    private class Feeder(private val config: TransientConfig, private val tracker: SectionStateTracker) {
        private val stepNanos = (1e9 / config.statsRateHz).toLong()
        var nowNanos = 0L
            private set

        fun feed(
            seconds: Float,
            rmsZ: Float,
            bassZ: Float = rmsZ,
            onsetDensityZ: Float = 0f,
            warm: Boolean = true,
            drop: DropEvent? = null
        ): List<SectionStateUpdate> {
            val samples = (seconds * config.statsRateHz).toInt().coerceAtLeast(1)
            val changes = mutableListOf<SectionStateUpdate>()
            repeat(samples) { i ->
                nowNanos += stepNanos
                val snapshot = snapshot(nowNanos, rmsZ, bassZ, onsetDensityZ, warm)
                // A drop, when given, belongs to the first sample of the stretch.
                val event = if (i == 0) drop?.copy(timestampNanos = nowNanos) else null
                tracker.update(snapshot, event)?.let(changes::add)
            }
            return changes
        }

        fun ramp(seconds: Float, rmsZFrom: Float, rmsZTo: Float, onsetZFrom: Float, onsetZTo: Float):
            List<SectionStateUpdate> {
            val samples = (seconds * config.statsRateHz).toInt().coerceAtLeast(1)
            val changes = mutableListOf<SectionStateUpdate>()
            repeat(samples) { i ->
                val t = if (samples <= 1) 1f else i.toFloat() / (samples - 1)
                nowNanos += stepNanos
                val rmsZ = rmsZFrom + (rmsZTo - rmsZFrom) * t
                val snapshot = snapshot(
                    nowNanos,
                    rmsZ = rmsZ,
                    // Bass held well below the broadband level: a filtered riser.
                    bassZ = rmsZ - 1f,
                    onsetDensityZ = onsetZFrom + (onsetZTo - onsetZFrom) * t,
                    warm = true
                )
                tracker.update(snapshot)?.let(changes::add)
            }
            return changes
        }

        private fun snapshot(
            nowNanos: Long,
            rmsZ: Float,
            bassZ: Float,
            onsetDensityZ: Float,
            warm: Boolean
        ) = TransientSnapshot(
            timestampNanos = nowNanos,
            rms = 0.3f, bass = 0.2f, centroidHz = 1500f,
            rmsZ = rmsZ, bassZ = bassZ, centroidZ = 0f,
            onsetDensity = 4f, onsetDensityZ = onsetDensityZ,
            bassRise = 1f, warm = warm
        )
    }

    private fun feeder(tracker: SectionStateTracker) = Feeder(config, tracker)

    private fun drop() = DropEvent(
        timestampNanos = 0L,
        intensity = 0.8f,
        broadbandZ = 2.5f,
        bassZ = 2.2f,
        precededBy = SectionState.BUILDUP
    )

    @Test
    fun `an unwarm detector stays unknown`() {
        val tracker = SectionStateTracker(config)
        val changes = feeder(tracker).feed(20f, rmsZ = 2f, warm = false)

        assertTrue(changes.isEmpty())
        assertEquals(SectionState.UNKNOWN, tracker.state)
    }

    @Test
    fun `a drop commits immediately without waiting for the debounce`() {
        val tracker = SectionStateTracker(config)
        val f = feeder(tracker)
        // Start from a quiet section, so the drop is genuinely a state change.
        f.feed(5f, rmsZ = -1.2f)
        assertEquals(SectionState.INTRO, tracker.state)

        // One sample only — far shorter than sectionDebounceSeconds.
        val changes = f.feed(0.1f, rmsZ = 2.5f, drop = drop())

        assertEquals(listOf(SectionState.DROP), changes.map { it.state })
        assertEquals(1f, changes.single().confidence, 0.001f)
    }

    @Test
    fun `quiet after high energy becomes a breakdown, but only after the debounce`() {
        val tracker = SectionStateTracker(config)
        val f = feeder(tracker)
        f.feed(10f, rmsZ = 1f)
        assertEquals(SectionState.DROP, tracker.state)

        val tooShort = f.feed(config.sectionDebounceSeconds - 0.5f, rmsZ = -1.5f)
        assertTrue("committed before the debounce elapsed", tooShort.isEmpty())

        val committed = f.feed(2f, rmsZ = -1.5f)
        assertEquals(listOf(SectionState.BREAKDOWN), committed.map { it.state })
    }

    @Test
    fun `a drop holds the section for its hold window`() {
        val tracker = SectionStateTracker(config)
        val f = feeder(tracker)
        f.feed(5f, rmsZ = 0.8f)
        f.feed(0.1f, rmsZ = 2.5f, drop = drop())

        // Energy sags a little but stays near the mean: still the drop section.
        val changes = f.feed(config.dropHoldSeconds - 1f, rmsZ = 0.1f)

        assertTrue("state changed during the hold window: $changes", changes.isEmpty())
        assertEquals(SectionState.DROP, tracker.state)
    }

    @Test
    fun `sustained quiet after high energy becomes an outro`() {
        val tracker = SectionStateTracker(config)
        val f = feeder(tracker)
        f.feed(10f, rmsZ = 1f)

        val changes = f.feed(config.outroSeconds + 10f, rmsZ = -1.5f)

        assertEquals(
            listOf(SectionState.BREAKDOWN, SectionState.OUTRO),
            changes.map { it.state }
        )
    }

    @Test
    fun `a quiet start reads as an intro`() {
        val tracker = SectionStateTracker(config)
        val changes = feeder(tracker).feed(5f, rmsZ = -1.5f)

        assertEquals(listOf(SectionState.INTRO), changes.map { it.state })
    }

    @Test
    fun `climbing energy and onset density with suppressed bass becomes a build-up`() {
        val tracker = SectionStateTracker(config)
        val f = feeder(tracker)
        f.feed(6f, rmsZ = -1.2f)
        assertEquals(SectionState.INTRO, tracker.state)

        val changes = f.ramp(12f, rmsZFrom = 0.2f, rmsZTo = 1.5f, onsetZFrom = 0.5f, onsetZTo = 2f)

        assertEquals(listOf(SectionState.BUILDUP), changes.map { it.state })
    }

    @Test
    fun `the build-up ramp climbs towards one`() {
        val tracker = SectionStateTracker(config)
        val f = feeder(tracker)
        f.feed(6f, rmsZ = -1.2f)
        f.ramp(4f, rmsZFrom = 0.4f, rmsZTo = 1f, onsetZFrom = 0.8f, onsetZTo = 1.5f)
        assertEquals(SectionState.BUILDUP, tracker.state)

        val early = tracker.currentUpdate(f.nowNanos).ramp
        f.ramp(config.buildupRampSeconds, rmsZFrom = 1f, rmsZTo = 2f, onsetZFrom = 1.5f, onsetZTo = 3f)
        val late = tracker.currentUpdate(f.nowNanos).ramp

        assertTrue("early=$early late=$late", late > early)
        assertEquals(1f, late, 0.001f)
    }

    @Test
    fun `the ramp is zero outside a build-up`() {
        val tracker = SectionStateTracker(config)
        val f = feeder(tracker)
        f.feed(10f, rmsZ = 1f)

        assertEquals(SectionState.DROP, tracker.state)
        assertEquals(0f, tracker.currentUpdate(f.nowNanos).ramp, 0f)
    }

    @Test
    fun `reset returns the tracker to unknown`() {
        val tracker = SectionStateTracker(config)
        val f = feeder(tracker)
        f.feed(10f, rmsZ = 1f)
        assertNotNull(tracker.state)

        tracker.reset()

        assertEquals(SectionState.UNKNOWN, tracker.state)
        assertNull(feeder(tracker).feed(0.1f, rmsZ = 1f, warm = false).firstOrNull())
    }
}
