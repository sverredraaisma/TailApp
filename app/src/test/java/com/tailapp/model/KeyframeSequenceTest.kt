package com.tailapp.model

import com.tailapp.ble.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local preview, and the validation that keeps a sequence the device would
 * refuse from ever being sent.
 *
 * [KeyframeSequence.sample] is a port of `KeyframeSequence::sample`, so these
 * assert the numbers that function produces — a preview that eased its spans or
 * smoothed the loop wrap would look better and lie about what the tail does.
 */
class KeyframeSequenceTest {

    /** A hundredth of the 0.01° the wire carries — see the note in the linear test. */
    private val GRID = 1e-4f

    private fun pose(x: Float) = TailPose(baseX = x, baseY = x / 2f, tipX = -x, tipY = 0f)

    private val ramp = KeyframeSequence(
        keyframes = listOf(
            Keyframe(0, TailPose()),
            Keyframe(1000, pose(90f))
        )
    )

    // ── Interpolation ──────────────────────────────────────────────

    @Test
    fun `a keyframe's own time gives that keyframe's pose`() {
        assertEquals(0f, ramp.sampleAtMillis(0).baseX, 0f)
        assertEquals(90f, ramp.sampleAtMillis(1000).baseX, 0f)
        assertEquals(-90f, ramp.sampleAtMillis(1000).tipX, 0f)
    }

    @Test
    fun `between keyframes is linear, not eased`() {
        // A quarter of the way along a 0..90 ramp is 22.5, and an ease would
        // put it anywhere but there.
        //
        // GRID is a hundredth of the 0.01° the wire can even express. It is not
        // zero because the firmware converts times as `t_ms * 0.001f`, so 0.75 s
        // is the nearest float to 0.75 rather than 0.75 — this port reproduces
        // that instead of computing a rounder number the device never sees.
        assertEquals(22.5f, ramp.sampleAtMillis(250).baseX, 0f)
        assertEquals(45f, ramp.sampleAtMillis(500).baseX, 0f)
        assertEquals(67.5f, ramp.sampleAtMillis(750).baseX, GRID)
        // Every channel travels on the same clock.
        assertEquals(22.5f, ramp.sampleAtMillis(500).baseY, 0f)
        assertEquals(-45f, ramp.sampleAtMillis(500).tipX, 0f)
    }

    @Test
    fun `the correct span is chosen with more than two keyframes`() {
        val sequence = KeyframeSequence(
            listOf(
                Keyframe(0, TailPose(baseX = 0f)),
                Keyframe(300, TailPose(baseX = 30f)),
                Keyframe(700, TailPose(baseX = -10f))
            )
        )
        assertEquals(15f, sequence.sampleAtMillis(150).baseX, 0f)
        assertEquals(30f, sequence.sampleAtMillis(300).baseX, 0f)
        assertEquals(10f, sequence.sampleAtMillis(500).baseX, GRID)
        assertEquals(-10f, sequence.sampleAtMillis(700).baseX, 0f)
    }

    @Test
    fun `outside a non-looping sequence the nearest keyframe is held`() {
        assertEquals(0f, ramp.sampleAtMillis(-500).baseX, 0f)
        assertEquals(90f, ramp.sampleAtMillis(5000).baseX, 0f)
    }

    @Test
    fun `a looping sequence wraps, and steps at the wrap`() {
        val looping = ramp.copy(loop = true)
        // The wrap is a step back to the first keyframe, not a blend into it:
        // making the ends meet is the author's job, and smoothing it here would
        // show a transition the device does not perform.
        assertEquals(0f, looping.sample(1.0f).baseX, 0f)
        assertEquals(45f, looping.sample(1.5f).baseX, 0f)
        assertEquals(89.99f, looping.sample(0.9999f).baseX, 0.02f)
    }

    @Test
    fun `a single keyframe is a held pose at any time`() {
        val held = KeyframeSequence(listOf(Keyframe(0, pose(40f))))
        assertEquals(0, held.durationMs)
        assertEquals(40f, held.sampleAtMillis(0).baseX, 0f)
        assertEquals(40f, held.sampleAtMillis(9999).baseX, 0f)
    }

    @Test
    fun `poses are interpolated on the wire's grid, not the authored floats`() {
        // 0.005° cannot survive the int16 of hundredths, so the device holds
        // 0.01°. A preview keeping the extra digit would drift over a long ramp.
        val sequence = KeyframeSequence(
            listOf(
                Keyframe(0, TailPose(baseX = 0.005f)),
                Keyframe(1000, TailPose(baseX = 0.005f))
            )
        )
        assertEquals(0.01f, sequence.sampleAtMillis(0).baseX, 0f)
        assertEquals(0.01f, sequence.sampleAtMillis(400).baseX, 0f)
    }

    @Test
    fun `motor targets come out in the order the device applies them`() {
        // keyframe_pattern.cpp: axis 0 (X) takes both segments, then axis 1 (Y).
        val targets = TailPose(baseX = 1f, baseY = 2f, tipX = 3f, tipY = 4f).motorTargets
        assertEquals(listOf(1f, 3f, 2f, 4f), targets.toList())
    }

    // ── Validation ─────────────────────────────────────────────────

    @Test
    fun `a well-formed sequence has nothing to report`() {
        assertTrue(ramp.validate().isEmpty())
        assertTrue(ramp.isValid)
    }

    @Test
    fun `an empty sequence is refused`() {
        assertEquals(
            listOf(SequenceProblem.Empty),
            KeyframeSequence(keyframes = emptyList()).validate()
        )
    }

    @Test
    fun `more keyframes than the device stores is refused`() {
        val tooMany = KeyframeSequence(
            List(Protocol.MAX_SEQUENCE_KEYFRAMES + 1) { Keyframe(it * 10, TailPose()) }
        )
        assertEquals(
            listOf(SequenceProblem.TooManyKeyframes(129)),
            tooMany.validate()
        )
    }

    @Test
    fun `a sequence that does not start at zero is refused`() {
        val late = KeyframeSequence(listOf(Keyframe(40, TailPose()), Keyframe(80, TailPose())))
        assertEquals(listOf(SequenceProblem.FirstNotAtZero(40)), late.validate())
    }

    @Test
    fun `two keyframes at one instant are refused`() {
        // A zero-length span is a division by zero in the interpolator.
        val duplicate = KeyframeSequence(
            listOf(Keyframe(0, TailPose()), Keyframe(200, TailPose()), Keyframe(200, TailPose()))
        )
        assertEquals(listOf(SequenceProblem.DuplicateTime(2, 200)), duplicate.validate())
    }

    @Test
    fun `timestamps that run backwards are refused`() {
        val backwards = KeyframeSequence(
            listOf(Keyframe(0, TailPose()), Keyframe(200, TailPose()), Keyframe(100, TailPose()))
        )
        assertEquals(listOf(SequenceProblem.OutOfOrder(2, 100, 200)), backwards.validate())
    }

    @Test
    fun `a negative timestamp is refused`() {
        val negative = KeyframeSequence(
            listOf(Keyframe(0, TailPose()), Keyframe(-100, TailPose()))
        )
        assertEquals(listOf(SequenceProblem.NegativeTime(1, -100)), negative.validate())
    }

    @Test
    fun `an angle the int16 cannot carry is refused rather than wrapped`() {
        // 400° would encode as 40000, which reads back as -25536 — the tail
        // slamming the other way, not a rejected upload.
        val steep = KeyframeSequence(listOf(Keyframe(0, TailPose(tipX = 400f))))
        assertEquals(listOf(SequenceProblem.AngleOutOfRange(0, 400f)), steep.validate())
        assertTrue(KeyframeSequence(listOf(Keyframe(0, TailPose(tipX = 327f)))).isValid)
    }

    @Test
    fun `every problem in a sequence is reported, not just the first`() {
        val broken = KeyframeSequence(
            listOf(Keyframe(10, TailPose()), Keyframe(10, TailPose(baseY = 500f)))
        )
        val problems = broken.validate()
        assertEquals(
            listOf(
                SequenceProblem.FirstNotAtZero(10),
                SequenceProblem.DuplicateTime(1, 10),
                SequenceProblem.AngleOutOfRange(1, 500f)
            ),
            problems
        )
    }

    @Test
    fun `the encoded size is the header plus a record per keyframe`() {
        assertEquals(8 + 2 * 12, ramp.encodedSize)
        assertEquals(1000, ramp.durationMs)
        assertEquals(1f, ramp.durationSeconds, 0f)
    }
}
