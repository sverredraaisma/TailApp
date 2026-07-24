package com.tailapp.composer

import com.tailapp.led.LedCoord
import com.tailapp.led.PixelBuffer
import com.tailapp.model.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tail's own body as an effect input.
 *
 * Everything else in the composer reacts to sound; these react to the device —
 * a physical tap, which way it is deflected, how fast it is moving, and which
 * way is down. The whole point is that a look can respond to being touched, so
 * the assertions here are about *that* rather than about exact pixels
 * (CompositionRendererTest holds the line on blending arithmetic).
 */
class TailReactiveTest {

    /** Two rings of four, which is enough to have both an x and a y spread. */
    private val coords: List<LedCoord> = buildList {
        for (ring in 0..1) {
            for (i in 0..3) add(LedCoord(x = i / 4f, y = ring.toFloat()))
        }
    }

    private fun render(effect: ReactiveEffect, ctx: ReactiveContext): PixelBuffer {
        val out = PixelBuffer(coords.size)
        effect.render(out, coords, ctx)
        return out
    }

    private fun PixelBuffer.totalBrightness(): Long {
        var sum = 0L
        for (i in 0 until ledCount) {
            val packed = packed(i)
            sum += ((packed shr 16) and 0xFF) + ((packed shr 8) and 0xFF) + (packed and 0xFF)
        }
        return sum
    }

    // ── Tap ripple ─────────────────────────────────────────────────

    @Test
    fun `tap ripple draws nothing until the tail is tapped`() {
        val effect = ReactiveEffects.create("tap_ripple")!!
        assertEquals(0L, render(effect, testContext()).totalBrightness())
    }

    @Test
    fun `tap ripple lights up on a tap and fades away`() {
        val effect = ReactiveEffects.create("tap_ripple")!!

        val fresh = render(effect, tappedContext(secondsAgo = 0.05f)).totalBrightness()
        assertTrue("a fresh tap must light something", fresh > 0L)

        // Well past the default 0.9 s fade.
        val stale = render(effect, tappedContext(secondsAgo = 6f)).totalBrightness()
        assertEquals(0L, stale)
    }

    @Test
    fun `tap ripple starts at the end that was tapped`() {
        val effect = ReactiveEffects.create("tap_ripple")!!

        // Early in the wave, the lit pixels should still be near the origin.
        val fromBase = render(effect, tappedContext(TailEnd.BASE, secondsAgo = 0.02f))
        val fromTip = render(effect, tappedContext(TailEnd.TIP, secondsAgo = 0.02f))

        // coords ring 0 is y=0 (base), ring 1 is y=1 (tip).
        val baseRingLitByBase = (0..3).sumOf { fromBase.packed(it).toLong() and 0xFFFFFF }
        val tipRingLitByBase = (4..7).sumOf { fromBase.packed(it).toLong() and 0xFFFFFF }
        val baseRingLitByTip = (0..3).sumOf { fromTip.packed(it).toLong() and 0xFFFFFF }
        val tipRingLitByTip = (4..7).sumOf { fromTip.packed(it).toLong() and 0xFFFFFF }

        // A ripple that ignored the tapped end would light the two identically,
        // and the light would not appear to come from the hand.
        assertTrue(baseRingLitByBase > tipRingLitByBase)
        assertTrue(tipRingLitByTip > baseRingLitByTip)
    }

    @Test
    fun `tap ripple can be filtered to one end`() {
        val effect = ReactiveEffects.create("tap_ripple", mapOf("respondsTo" to 2f))!! // Tip only

        assertEquals(
            0L,
            render(effect, tappedContext(TailEnd.BASE, secondsAgo = 0.05f)).totalBrightness()
        )
        assertTrue(
            render(effect, tappedContext(TailEnd.TIP, secondsAgo = 0.05f)).totalBrightness() > 0L
        )
    }

    // ── Motion glow ────────────────────────────────────────────────

    @Test
    fun `motion glow brightens with wag speed`() {
        val effect = ReactiveEffects.create("motion_glow")!!

        val still = render(effect, testContext(tail = TailTelemetry.AT_REST)).totalBrightness()
        val moving = render(
            effect,
            testContext(tail = TailTelemetry(wagSpeed = 1f))
        ).totalBrightness()

        assertTrue("a wagging tail must glow brighter than a still one", moving > still)
        // ...but a still tail is dim, not dark: an effect that vanishes entirely
        // reads as broken rather than idle.
        assertTrue(still > 0L)
    }

    @Test
    fun `motion glow shifts colour with deflection direction`() {
        val effect = ReactiveEffects.create("motion_glow")!!

        val left = render(effect, testContext(tail = TailTelemetry(deflectionX = -1f, wagSpeed = 1f)))
        val right = render(effect, testContext(tail = TailTelemetry(deflectionX = 1f, wagSpeed = 1f)))

        assertNotEquals(left.packed(0), right.packed(0))
    }

    // ── Wag trail ──────────────────────────────────────────────────

    @Test
    fun `wag trail follows the deflection around the rings`() {
        // Fully left maps to ring position 0, centred maps to 0.5. Comparing
        // -1 against +1 would prove nothing: they are the same point on a ring.
        val effect = ReactiveEffects.create("wag_trail")!!
        val left = render(effect, testContext(dtSeconds = 0.03f, tail = TailTelemetry(deflectionX = -1f)))
        val brightestLeft = (0 until left.ledCount).maxBy { left.packed(it).toLong() and 0xFFFFFF }

        // A fresh instance, so the previous trail cannot bias the comparison.
        val centred = ReactiveEffects.create("wag_trail")!!
        val middle = render(centred, testContext(dtSeconds = 0.03f, tail = TailTelemetry(deflectionX = 0f)))
        val brightestMiddle = (0 until middle.ledCount).maxBy { middle.packed(it).toLong() and 0xFFFFFF }

        assertEquals(0f, coords[brightestLeft].x, 1e-6f)
        assertEquals(0.5f, coords[brightestMiddle].x, 1e-6f)
    }

    @Test
    fun `wag trail wraps around the ring rather than stopping at the seam`() {
        // Head at 0.0, wide enough to reach a quarter turn. The LED at x=0.75 is
        // a quarter turn away the short way, so a head that could not wrap
        // around the seam would leave it dark.
        val effect = ReactiveEffects.create("wag_trail", mapOf("width" to 0.3f))!!
        val out = render(effect, testContext(dtSeconds = 0.03f, tail = TailTelemetry(deflectionX = -1f)))

        val nearSeam = out.packed(3).toLong() and 0xFFFFFF
        val farSide = out.packed(2).toLong() and 0xFFFFFF
        assertTrue("x=0.75 is nearer to a head at 0 than x=0.5 is", nearSeam > farSide)
    }

    @Test
    fun `wag trail fades rather than clearing between frames`() {
        val effect = ReactiveEffects.create("wag_trail", mapOf("persistence" to 2f))!!

        // Deposit at one end...
        render(effect, testContext(dtSeconds = 0.03f, tail = TailTelemetry(deflectionX = -1f)))
        // ...then move the head far away and check the old position still glows.
        val after = render(effect, testContext(dtSeconds = 0.03f, tail = TailTelemetry(deflectionX = 1f)))

        assertTrue(
            "the trail is the whole effect; without it this is just a dot",
            (after.packed(0).toLong() and 0xFFFFFF) > 0L
        )
    }

    // ── Gravity level ──────────────────────────────────────────────

    @Test
    fun `gravity level lights the downhill side`() {
        val effect = ReactiveEffects.create("gravity_level")!!

        // Gravity pointing along +x: the LEDs nearest x=0 are "down".
        val tilted = render(effect, testContext(tail = TailTelemetry(gravityX = 1f, gravityY = 0f)))

        val atZero = tilted.packed(0).toLong() and 0xFFFFFF
        val opposite = tilted.packed(2).toLong() and 0xFFFFFF // x = 0.5, half a turn away
        assertTrue("the downhill side must be brighter than the uphill side", atZero > opposite)
    }

    @Test
    fun `a level tail glows evenly rather than picking a direction`() {
        val effect = ReactiveEffects.create("gravity_level")!!
        // Gravity straight down the tail's axis: there is no downhill *side*.
        val level = render(effect, testContext(tail = TailTelemetry(gravityX = 0f, gravityY = 0f, gravityZ = 1f)))

        val first = level.packed(0)
        for (i in 0 until level.ledCount) {
            assertEquals("an arbitrary direction would be a lie", first, level.packed(i))
        }
    }

    // ── Tap gate ───────────────────────────────────────────────────

    @Test
    fun `tap gate opens on a tap and closes again`() {
        val effect = ReactiveEffects.create("tap_gate")!!

        val closed = render(effect, testContext()).totalBrightness()
        val open = render(effect, tappedContext(secondsAgo = 0f)).totalBrightness()

        assertEquals("a closed gate blacks the group out by default", 0L, closed)
        assertTrue(open > 0L)
    }

    @Test
    fun `tap gate stays grey so it dims rather than tints`() {
        val effect = ReactiveEffects.create("tap_gate")!!
        val out = render(effect, tappedContext(secondsAgo = 0.1f))

        for (i in 0 until out.ledCount) {
            val packed = out.packed(i)
            val r = (packed shr 16) and 0xFF
            val g = (packed shr 8) and 0xFF
            val b = packed and 0xFF
            assertEquals("a tinted modulator recolours what it modulates", r, g)
            assertEquals(g, b)
        }
    }

    // ── Context helpers ────────────────────────────────────────────

    @Test
    fun `tapEnvelope ignores a tap from the other end`() {
        val ctx = tappedContext(TailEnd.BASE, secondsAgo = 0f)
        assertTrue(ctx.tapEnvelope(1f, TailEnd.BASE) > 0.9f)
        assertEquals(0f, ctx.tapEnvelope(1f, TailEnd.TIP), 0f)
        assertTrue(ctx.tapEnvelope(1f, null) > 0.9f)
    }

    @Test
    fun `tapEnvelope is zero before any tap`() {
        assertEquals(0f, testContext().tapEnvelope(1f), 0f)
    }

    // ── Telemetry derivation ───────────────────────────────────────

    private fun motionState(
        positions: List<Float>,
        xLimits: Pair<Float, Float> = -90f to 90f,
        yLimits: Pair<Float, Float> = -45f to 45f
    ) = MotionState(
        activePatternId = 0,
        params = List(8) { 0f },
        encoderPositions = positions,
        gravityX = 0f,
        gravityY = 0f,
        gravityZ = 1f,
        xAxisMin = xLimits.first,
        xAxisMax = xLimits.second,
        yAxisMin = yLimits.first,
        yAxisMax = yLimits.second
    )

    @Test
    fun `deflection is a fraction of the configured travel, not raw degrees`() {
        val tracker = TailTelemetryTracker()

        // 45 degrees on an axis limited to +/-90 is half deflection...
        val wide = tracker.update(motionState(listOf(45f, 45f, 0f, 0f)), 0L)
        assertEquals(0.5f, wide.deflectionX, 1e-3f)

        // ...and the same 45 degrees on a +/-45 axis is full deflection. An
        // effect written against degrees would look wrong on one of these.
        val narrow = TailTelemetryTracker()
            .update(motionState(listOf(45f, 45f, 0f, 0f), xLimits = -45f to 45f), 0L)
        assertEquals(1f, narrow.deflectionX, 1e-3f)
    }

    @Test
    fun `deflection is clamped and averages the two halves`() {
        val tracker = TailTelemetryTracker()
        val telemetry = tracker.update(motionState(listOf(90f, 0f, 0f, 0f)), 0L)
        assertEquals(0.5f, telemetry.deflectionX, 1e-3f)

        val over = TailTelemetryTracker().update(motionState(listOf(500f, 500f, 0f, 0f)), 0L)
        assertEquals(1f, over.deflectionX, 1e-3f)
    }

    @Test
    fun `wag speed comes from how fast deflection changes`() {
        val tracker = TailTelemetryTracker()

        tracker.update(motionState(listOf(-90f, -90f, 0f, 0f)), 0L)
        // Swing corner to corner in 50 ms: about as fast as it gets.
        val fast = tracker.update(motionState(listOf(90f, 90f, 0f, 0f)), 50_000_000L)
        assertTrue("a full sweep must register as movement", fast.wagSpeed > 0.2f)

        // Held still, the speed falls away.
        var telemetry = fast
        var t = 50_000_000L
        repeat(40) {
            t += 50_000_000L
            telemetry = tracker.update(motionState(listOf(90f, 90f, 0f, 0f)), t)
        }
        assertTrue("a still tail must settle to no movement", telemetry.wagSpeed < 0.05f)
    }

    @Test
    fun `the first sample reports no movement rather than an infinite one`() {
        // With no previous sample there is no interval to divide by; guessing
        // would make every reconnect look like a violent wag.
        val telemetry = TailTelemetryTracker().update(motionState(listOf(90f, 90f, 0f, 0f)), 0L)
        assertEquals(0f, telemetry.wagSpeed, 0f)
    }

    @Test
    fun `a degenerate axis window yields no deflection instead of dividing by it`() {
        val telemetry = TailTelemetryTracker()
            .update(motionState(listOf(30f, 30f, 0f, 0f), xLimits = 0f to 0f), 0L)
        assertEquals(0f, telemetry.deflectionX, 0f)
    }
}
