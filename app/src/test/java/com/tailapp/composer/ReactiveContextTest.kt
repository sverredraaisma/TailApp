package com.tailapp.composer

import com.tailapp.beat.BeatEvent
import com.tailapp.beat.BeatType
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * The envelopes and accessors every effect reads through.
 *
 * These are the contract the whole effect library is written against, so they
 * are asserted numerically rather than by shape.
 */
class ReactiveContextTest {

    private val beat = BeatEvent(
        type = BeatType.BEAT,
        timestampNanos = 0L,
        bpm = 120f,
        beatInBar = 1,
        confidence = 0.9f
    )

    private val downbeat = beat.copy(type = BeatType.DOWNBEAT, beatInBar = 0)

    @Test
    fun `the beat envelope is full at the beat instant`() {
        val ctx = testContext(lastBeat = beat, secondsSinceBeat = 0f)

        assertEquals(1f, ctx.beatEnvelope(0.3f), 1e-6f)
    }

    @Test
    fun `the beat envelope decays exponentially`() {
        val ctx = testContext(lastBeat = beat, secondsSinceBeat = 0.15f)

        assertEquals(exp(-0.15f / 0.3f), ctx.beatEnvelope(0.3f), 1e-6f)
    }

    @Test
    fun `a beat still in the future contributes nothing`() {
        // The tracker predicts beats ahead so the BLE round trip can be absorbed;
        // lighting them early would defeat that.
        val ctx = testContext(lastBeat = beat, secondsSinceBeat = -0.05f)

        assertEquals(0f, ctx.beatEnvelope(0.3f), 0f)
    }

    @Test
    fun `with no beat yet the envelope is zero`() {
        assertEquals(0f, testContext().beatEnvelope(0.3f), 0f)
    }

    @Test
    fun `the downbeat boost applies only on a downbeat`() {
        val onBeat = testContext(lastBeat = beat, secondsSinceBeat = 0f, onDownbeat = false)
        val onDownbeat = testContext(lastBeat = downbeat, secondsSinceBeat = 0f, onDownbeat = true)

        assertEquals(1f, onBeat.beatEnvelope(0.3f, downbeatBoost = 1.5f), 1e-6f)
        assertEquals(1.5f, onDownbeat.beatEnvelope(0.3f, downbeatBoost = 1.5f), 1e-6f)
    }

    @Test
    fun `a zero decay does not divide by zero`() {
        val ctx = testContext(lastBeat = beat, secondsSinceBeat = 0.1f)

        assertTrue(ctx.beatEnvelope(0f).isFinite())
    }

    // --- drops ---

    private val drop = DropEvent(
        timestampNanos = 0L,
        intensity = 1f,
        broadbandZ = 4f,
        bassZ = 4f,
        precededBy = SectionState.BUILDUP
    )

    @Test
    fun `the drop envelope falls linearly across its window`() {
        val atStart = testContext(lastDrop = drop, secondsSinceDrop = 0f)
        val halfway = testContext(lastDrop = drop, secondsSinceDrop = 1f)

        assertEquals(1f, atStart.dropEnvelope(2f), 1e-6f)
        assertEquals(0.5f, halfway.dropEnvelope(2f), 1e-6f)
    }

    @Test
    fun `the drop envelope is zero past its window and before it starts`() {
        assertEquals(0f, testContext(lastDrop = drop, secondsSinceDrop = 2.5f).dropEnvelope(2f), 0f)
        assertEquals(0f, testContext(lastDrop = drop, secondsSinceDrop = -0.1f).dropEnvelope(2f), 0f)
        assertEquals(0f, testContext().dropEnvelope(2f), 0f)
    }

    @Test
    fun `a weaker drop produces a weaker envelope`() {
        val weak = drop.copy(intensity = 0f)
        val ctx = testContext(lastDrop = weak, secondsSinceDrop = 0f)

        // 0.5 + 0.5 * intensity
        assertEquals(0.5f, ctx.dropEnvelope(2f), 1e-6f)
    }

    // --- spectrum ---

    @Test
    fun `band reads are bounds-checked`() {
        val ctx = testContext(bands = floatArrayOf(0.1f, 0.2f, 0.3f))

        assertEquals(3, ctx.bandCount)
        assertEquals(0.2f, ctx.band(1), 0f)
        assertEquals(0f, ctx.band(-1), 0f)
        assertEquals(0f, ctx.band(99), 0f)
    }

    @Test
    fun `bandAtFraction interpolates between neighbouring bands`() {
        val ctx = testContext(bands = floatArrayOf(0f, 1f, 0f))

        assertEquals(0f, ctx.bandAtFraction(0f), 1e-6f)
        assertEquals(1f, ctx.bandAtFraction(0.5f), 1e-6f)
        assertEquals(0f, ctx.bandAtFraction(1f), 1e-6f)
        // A quarter of the way is halfway between bands 0 and 1.
        assertEquals(0.5f, ctx.bandAtFraction(0.25f), 1e-6f)
    }

    @Test
    fun `bandAtFraction copes with an empty or single-band spectrum`() {
        assertEquals(0f, testContext().bandAtFraction(0.5f), 0f)
        assertEquals(0.7f, testContext(bands = floatArrayOf(0.7f)).bandAtFraction(0.5f), 0f)
    }

    @Test
    fun `bandAtFraction clamps out-of-range input`() {
        val ctx = testContext(bands = floatArrayOf(0.25f, 0.75f))

        assertEquals(0.25f, ctx.bandAtFraction(-5f), 1e-6f)
        assertEquals(0.75f, ctx.bandAtFraction(5f), 1e-6f)
    }
}
