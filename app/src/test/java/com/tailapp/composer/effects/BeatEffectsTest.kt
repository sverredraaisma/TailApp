package com.tailapp.composer.effects

import com.tailapp.beat.BeatEvent
import com.tailapp.beat.BeatType
import com.tailapp.composer.ReactiveEffects
import com.tailapp.composer.testContext
import com.tailapp.led.LedLayout
import com.tailapp.led.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp

/**
 * The beat-driven effects, asserted against arithmetic worked out by hand.
 *
 * Two rings of four: LEDs 0-3 sit at `y = 0` (the base) and 4-7 at `y = 1` (the
 * tip), with `x` running 0, 1/3, 2/3, 1 across each ring.
 */
class BeatEffectsTest {

    private val ledsPerRing = listOf(4, 4)
    private val coords = LedLayout.coordsFor(ledsPerRing)

    private fun render(effectId: String, params: Map<String, Float>, ctx: com.tailapp.composer.ReactiveContext): PixelBuffer {
        val buffer = PixelBuffer(coords.size)
        ReactiveEffects.create(effectId, params)!!.render(buffer, coords, ctx)
        return buffer
    }

    private val beat = BeatEvent(BeatType.BEAT, 0L, 120f, 1, 0.9f)

    // --- beat flash ---

    @Test
    fun `a beat flash is full colour at the beat instant`() {
        val frame = render(
            "beat_flash",
            mapOf(
                "colorMode" to 0f, "color" to 0x804020.toFloat(),
                "decay" to 0.3f, "downbeatBoost" to 1f, "brightness" to 1f
            ),
            testContext(lastBeat = beat, secondsSinceBeat = 0f)
        )

        for (i in 0 until frame.ledCount) assertEquals(0x804020, frame.packed(i))
    }

    @Test
    fun `a beat flash decays by exp(-t over decay)`() {
        val frame = render(
            "beat_flash",
            mapOf(
                "colorMode" to 0f, "color" to 0xFFFFFF.toFloat(),
                "decay" to 0.5f, "downbeatBoost" to 1f, "brightness" to 1f
            ),
            testContext(lastBeat = beat, secondsSinceBeat = 0.5f)
        )

        // exp(-1) = 0.367879; 255 * 0.367879 = 93.8, truncated to 93.
        val expected = (255 * exp(-1f)).toInt()
        assertEquals(93, expected)
        assertEquals((expected shl 16) or (expected shl 8) or expected, frame.packed(0))
    }

    @Test
    fun `a downbeat is allowed to clip past full brightness`() {
        // The extra headroom is what makes the first beat of a bar read as an
        // accent rather than just another flash.
        val frame = render(
            "beat_flash",
            mapOf(
                "colorMode" to 0f, "color" to 0x808080.toFloat(),
                "decay" to 0.3f, "downbeatBoost" to 1.5f, "brightness" to 1f
            ),
            testContext(
                lastBeat = beat.copy(type = BeatType.DOWNBEAT, beatInBar = 0),
                secondsSinceBeat = 0f,
                onDownbeat = true
            )
        )

        // 128 * 1.5 = 192 on every channel.
        assertEquals(0xC0C0C0, frame.packed(0))
    }

    @Test
    fun `a beat still in the future is not lit early`() {
        val frame = render(
            "beat_flash",
            mapOf("colorMode" to 0f, "color" to 0xFFFFFF.toFloat(), "decay" to 0.3f),
            testContext(lastBeat = beat, secondsSinceBeat = -0.02f)
        )

        assertEquals(0, frame.packed(0))
    }

    @Test
    fun `cycling the hue steps the colour with the beat count`() {
        val frame = render(
            "beat_flash",
            mapOf(
                "colorMode" to 1f, "hueStep" to 120f,
                "decay" to 0.3f, "downbeatBoost" to 1f, "brightness" to 1f
            ),
            testContext(lastBeat = beat, secondsSinceBeat = 0f, beatCount = 1)
        )

        // Hue 120° through the firmware's integer HSV is pure green.
        assertEquals(0x00FF00, frame.packed(0))
    }

    // --- strobe ---

    private fun strobe(params: Map<String, Float>, ctx: com.tailapp.composer.ReactiveContext) =
        render(
            "strobe",
            mapOf(
                "color" to 0xFFFFFF.toFloat(), "duty" to 0.35f, "floor" to 0f,
                "freeHz" to 8f, "brightness" to 1f
            ) + params,
            ctx
        )

    @Test
    fun `a one-beat strobe is lit at the start of a beat and dark past its duty`() {
        val lit = strobe(
            mapOf("division" to 2f),
            testContext(bpm = 120f, lastBeat = beat, beatCount = 0, beatPhase = 0f)
        )
        val dark = strobe(
            mapOf("division" to 2f),
            testContext(bpm = 120f, lastBeat = beat, beatCount = 0, beatPhase = 0.5f)
        )

        assertEquals(0xFFFFFF, lit.packed(0))
        assertEquals(0, dark.packed(0))
    }

    @Test
    fun `a half-beat strobe fires twice per beat`() {
        // Halfway through the beat is a whole period at this division, so the
        // phase wraps back to lit — the property a free-running timer would drift
        // away from.
        val frame = strobe(
            mapOf("division" to 1f),
            testContext(bpm = 120f, lastBeat = beat, beatCount = 0, beatPhase = 0.5f)
        )

        assertEquals(0xFFFFFF, frame.packed(0))
    }

    @Test
    fun `the strobe stays in phase across many beats`() {
        val frame = strobe(
            mapOf("division" to 2f),
            testContext(bpm = 120f, lastBeat = beat, beatCount = 400, beatPhase = 0.1f)
        )

        assertEquals(0xFFFFFF, frame.packed(0))
    }

    @Test
    fun `with no tempo the strobe free-runs rather than vanishing`() {
        val frame = strobe(
            mapOf("division" to 2f, "freeHz" to 8f),
            testContext(bpm = 0f, lastBeat = null, timeSeconds = 0f)
        )

        assertEquals(0xFFFFFF, frame.packed(0))
    }

    @Test
    fun `the off level is the floor, not necessarily black`() {
        val frame = strobe(
            mapOf("division" to 2f, "floor" to 0.5f),
            testContext(bpm = 120f, lastBeat = beat, beatCount = 0, beatPhase = 0.9f)
        )

        // 255 * 0.5 = 127 (truncated) on every channel.
        assertEquals(0x7F7F7F, frame.packed(0))
    }

    // --- bar sweep ---

    @Test
    fun `the bar sweep fills in proportion to the beat's position in the bar`() {
        // Beat 1 of 4 with `smooth` off fills (1+1)/4 = half the tail, so the base
        // ring is lit and the tip ring is not.
        val frame = render(
            "bar_sweep",
            mapOf(
                "color" to 0x00FF00.toFloat(), "tipColor" to 0x00FF00.toFloat(),
                "direction" to 0f, "beatsPerBar" to 4f, "smooth" to 0f,
                "softness" to 0.01f, "brightness" to 1f
            ),
            testContext(lastBeat = beat.copy(beatInBar = 1), secondsSinceBeat = 0f)
        )

        assertEquals(0x00FF00, frame.packed(0))
        assertEquals(0, frame.packed(4))
    }

    @Test
    fun `the last beat of the bar fills the whole tail`() {
        val frame = render(
            "bar_sweep",
            mapOf(
                "color" to 0x00FF00.toFloat(), "tipColor" to 0x00FF00.toFloat(),
                "direction" to 0f, "beatsPerBar" to 4f, "smooth" to 0f,
                "softness" to 0.01f, "brightness" to 1f
            ),
            testContext(lastBeat = beat.copy(beatInBar = 3), secondsSinceBeat = 0f)
        )

        assertEquals(0x00FF00, frame.packed(0))
        assertEquals(0x00FF00, frame.packed(4))
    }

    @Test
    fun `reversing the direction fills from the tip instead`() {
        val frame = render(
            "bar_sweep",
            mapOf(
                "color" to 0x00FF00.toFloat(), "tipColor" to 0x00FF00.toFloat(),
                "direction" to 1f, "beatsPerBar" to 4f, "smooth" to 0f,
                "softness" to 0.01f, "brightness" to 1f
            ),
            testContext(lastBeat = beat.copy(beatInBar = 1), secondsSinceBeat = 0f)
        )

        assertEquals(0, frame.packed(0))
        assertEquals(0x00FF00, frame.packed(4))
    }

    @Test
    fun `the bar sweep draws nothing before the first beat`() {
        val frame = render("bar_sweep", mapOf("color" to 0x00FF00.toFloat()), testContext())

        for (i in 0 until frame.ledCount) assertEquals(0, frame.packed(i))
    }

    // --- sparkle ---

    @Test
    fun `a beat sparkle lights the same LEDs for every frame of that beat`() {
        // Seeded on the beat index, not re-rolled per frame: a beat is drawn
        // across many frames, and re-rolling would shimmer it into mush.
        val params = mapOf(
            "trigger" to 0f, "colorMode" to 0f, "color" to 0xFFFFFF.toFloat(),
            "density" to 0.5f, "decay" to 10f, "brightness" to 1f
        )
        val early = render("sparkle", params, testContext(lastBeat = beat, secondsSinceBeat = 0f, beatCount = 7))
        val later = render("sparkle", params, testContext(lastBeat = beat, secondsSinceBeat = 0.05f, beatCount = 7))

        for (i in 0 until early.ledCount) {
            assertEquals(
                "LED $i changed between two frames of the same beat",
                early.packed(i) != 0,
                later.packed(i) != 0
            )
        }
    }

    @Test
    fun `a different beat lights a different constellation`() {
        val params = mapOf(
            "trigger" to 0f, "colorMode" to 0f, "color" to 0xFFFFFF.toFloat(),
            "density" to 0.5f, "decay" to 10f, "brightness" to 1f
        )
        val first = render("sparkle", params, testContext(lastBeat = beat, secondsSinceBeat = 0f, beatCount = 1))
        val second = render("sparkle", params, testContext(lastBeat = beat, secondsSinceBeat = 0f, beatCount = 2))

        val differs = (0 until first.ledCount).any { first.packed(it) != second.packed(it) }
        assertEquals(true, differs)
    }
}
