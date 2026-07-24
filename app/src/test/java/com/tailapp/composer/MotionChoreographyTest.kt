package com.tailapp.composer

import com.tailapp.ble.protocol.MotionTargetFrame
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Driving the tail's motors from the same analysis that drives the lights.
 *
 * The device can wag on its own but cannot know where the beat is, how loud the
 * room is, or that a drop just landed. Like the effects, everything here is a
 * pure function of the context, so a dropped or late frame moves the tail to
 * where it should be rather than leaving it behind.
 */
class MotionChoreographyTest {

    private fun contextAt(
        barPhase: Float = 0f,
        bpm: Float = 120f,
        level: Float = 1f,
        timeSeconds: Float = 0f,
        secondsSinceDrop: Float = ReactiveContext.NO_EVENT_SECONDS,
        beatCount: Int = 0
    ) = testContext(
        bpm = bpm,
        barPhase = barPhase,
        level = level,
        timeSeconds = timeSeconds,
        beatCount = beatCount,
        lastDrop = if (secondsSinceDrop < ReactiveContext.NO_EVENT_SECONDS) {
            DropEvent(
                timestampNanos = 0L,
                intensity = 1f,
                broadbandZ = 4f,
                bassZ = 4f,
                precededBy = SectionState.BUILDUP
            )
        } else {
            null
        },
        secondsSinceDrop = secondsSinceDrop
    )

    @Test
    fun `beat wag sweeps once per bar, not once per beat`() {
        val choreo = MotionChoreography()

        // A quarter of the way through the bar is the peak of a single sine
        // swing. Four sweeps a second at 128 BPM would read as vibration rather
        // than dance, and the mechanism could not follow it.
        val quarter = choreo.targetsFor(contextAt(barPhase = 0.25f))[0]
        val threeQuarter = choreo.targetsFor(contextAt(barPhase = 0.75f))[0]

        assertTrue("peak deflection at a quarter bar", quarter > 20f)
        assertTrue("opposite deflection three quarters through", threeQuarter < -20f)
    }

    @Test
    fun `beat wag holds still with no tempo`() {
        val choreo = MotionChoreography()
        val targets = choreo.targetsFor(contextAt(bpm = 0f, barPhase = 0.25f))
        // Guessing a tempo would have the tail swinging to nothing.
        assertEquals(0f, targets[0], 1e-4f)
    }

    @Test
    fun `the second half trails the first, so it reads as a tail`() {
        val choreo = MotionChoreography()
        val targets = choreo.targetsFor(contextAt(barPhase = 0.25f))

        assertTrue(abs(targets[1]) < abs(targets[0]))
        assertTrue("both halves move the same way", targets[0] * targets[1] > 0f)
    }

    @Test
    fun `amplitude follows loudness`() {
        val choreo = MotionChoreography()
        val loud = choreo.targetsFor(contextAt(barPhase = 0.25f, level = 1f))[0]
        val quiet = choreo.targetsFor(contextAt(barPhase = 0.25f, level = 0f))[0]

        assertTrue(loud > quiet)
        assertTrue("a quiet room still moves a little", quiet > 0f)
    }

    @Test
    fun `loudness gain of zero decouples amplitude from level`() {
        val choreo = MotionChoreography(MotionChoreography.Config(loudnessGain = 0f))
        val loud = choreo.targetsFor(contextAt(barPhase = 0.25f, level = 1f))[0]
        val quiet = choreo.targetsFor(contextAt(barPhase = 0.25f, level = 0f))[0]

        assertEquals(loud, quiet, 1e-3f)
    }

    @Test
    fun `a drop throws the tail and it springs back`() {
        val choreo = MotionChoreography(
            MotionChoreography.Config(mode = MotionChoreography.Mode.DROP_ONLY)
        )

        val atRest = choreo.targetsFor(contextAt(secondsSinceDrop = 5f))[0]
        val onDrop = choreo.targetsFor(contextAt(secondsSinceDrop = 0f))[0]
        val settling = choreo.targetsFor(contextAt(secondsSinceDrop = 0.5f))[0]

        assertEquals("still until a drop lands", 0f, atRest, 1e-4f)
        assertTrue(abs(onDrop) > 20f)
        assertTrue("the flick decays", abs(settling) < abs(onDrop))
    }

    @Test
    fun `consecutive drops throw the tail opposite ways`() {
        val choreo = MotionChoreography(
            MotionChoreography.Config(mode = MotionChoreography.Mode.DROP_ONLY)
        )
        // Two drops always throwing the same way would walk the tail to one side
        // rather than reading as a reaction.
        val first = choreo.targetsFor(contextAt(secondsSinceDrop = 0f, beatCount = 0))[0]
        val second = choreo.targetsFor(contextAt(secondsSinceDrop = 0f, beatCount = 1))[0]

        assertTrue(first * second < 0f)
    }

    @Test
    fun `follow-volume mode needs no tempo at all`() {
        val choreo = MotionChoreography(
            MotionChoreography.Config(mode = MotionChoreography.Mode.LOUDNESS)
        )
        // Sampled across a sway, with no BPM known.
        val samples = (0..20).map { choreo.targetsFor(contextAt(bpm = 0f, timeSeconds = it * 0.2f))[0] }

        assertTrue("must move without a beat", samples.any { it > 5f })
        assertTrue(samples.any { it < -5f })
    }

    @Test
    fun `the same context always gives the same targets`() {
        val choreo = MotionChoreography()
        // Position is derived, not integrated, so a repeated frame is idempotent
        // and a dropped one costs nothing.
        val a = choreo.targetsFor(contextAt(barPhase = 0.4f)).copyOf()
        val b = choreo.targetsFor(contextAt(barPhase = 0.4f)).copyOf()
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun `targets are never absurd, whatever the analysis says`() {
        val choreo = MotionChoreography()
        // The device clamps to its own axis limits, but sending a wild number
        // would still be wrong.
        for (phase in 0..100) {
            val targets = choreo.targetsFor(
                contextAt(barPhase = phase / 100f, level = 1f, secondsSinceDrop = 0f)
            )
            for (t in targets) assertTrue("target $t out of any sane range", abs(t) < 180f)
        }
    }

    // ── Wire format ────────────────────────────────────────────────

    @Test
    fun `the frame is four little-endian floats in FF02 order`() {
        val frame = MotionTargetFrame.build(floatArrayOf(10f, -20f, 30f, -40f))

        assertEquals(MotionTargetFrame.SIZE, frame.size)
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10f, buf.float, 1e-4f)
        assertEquals(-20f, buf.float, 1e-4f)
        assertEquals(30f, buf.float, 1e-4f)
        assertEquals(-40f, buf.float, 1e-4f)
    }

    @Test
    fun `distinct targets produce distinct frames`() {
        assertNotEquals(
            MotionTargetFrame.build(floatArrayOf(1f, 2f, 3f, 4f)).toList(),
            MotionTargetFrame.build(floatArrayOf(4f, 3f, 2f, 1f)).toList()
        )
    }
}
