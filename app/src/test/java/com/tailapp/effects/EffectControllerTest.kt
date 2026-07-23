package com.tailapp.effects

import com.tailapp.beat.BeatEvent
import com.tailapp.beat.BeatType
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.genre.GenreState
import com.tailapp.testutil.RecordingLightingOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectControllerTest {

    private val second = 1_000_000_000L
    private val output = RecordingLightingOutput()
    private val renderer = ReactiveRenderer().apply { setLayout(listOf(8, 10, 8)) }

    private fun controller(config: EffectControllerConfig = EffectControllerConfig()) =
        EffectController(renderer, output, config)

    private fun beat(atNanos: Long) = BeatEvent(BeatType.BEAT, atNanos, 128f, 1, 0.9f)

    @Test
    fun `starts on the default profile`() {
        val controller = controller()

        assertEquals(EffectProfiles.DEFAULT.id, controller.activeProfile.id)
        assertFalse(controller.isOverridden)
    }

    @Test
    fun `the trigger offset shifts event timestamps`() {
        // Negative offset fires earlier, compensating for the lag between
        // deciding to light a beat and the LEDs actually changing.
        val controller = controller(EffectControllerConfig(triggerOffsetMillis = -50f))

        controller.onBeat(beat(second))

        assertEquals(second - 50_000_000L, output.beats.single().timestampNanos)
    }

    @Test
    fun `the offset can be changed at runtime`() {
        val controller = controller()

        controller.onBeat(beat(second))
        controller.triggerOffsetMillis = 120f
        controller.onBeat(beat(second))

        assertEquals(second, output.beats.first().timestampNanos)
        assertEquals(second + 120_000_000L, output.beats.last().timestampNanos)
    }

    @Test
    fun `the offset applies to drops too`() {
        val controller = controller(EffectControllerConfig(triggerOffsetMillis = -30f))

        controller.onDrop(DropEvent(second, 0.9f, 3f, 3f, SectionState.BUILDUP))

        assertEquals(second - 30_000_000L, output.drops.single().timestampNanos)
    }

    @Test
    fun `a debounced genre run switches the profile exactly once`() {
        val controller = controller()
        output.clear()

        repeat(6) { i ->
            controller.onGenre(GenreState("Electronic---Hardstyle", 0.9f, second * (i + 1)))
        }

        assertEquals(EffectProfiles.HARDSTYLE.id, controller.activeProfile.id)
        assertEquals(
            "the output should be told about the change once",
            listOf(EffectProfiles.HARDSTYLE.id),
            output.profiles.map { it.id }
        )
    }

    @Test
    fun `an ambiguous genre stream leaves the profile alone`() {
        val controller = controller()
        output.clear()

        listOf("trance", "hardstyle", "house", "trance", "hardstyle", "house").forEachIndexed { i, label ->
            controller.onGenre(GenreState(label, 0.9f, second * (i + 1)))
        }

        assertEquals(EffectProfiles.DEFAULT.id, controller.activeProfile.id)
        assertTrue(output.profiles.isEmpty())
    }

    @Test
    fun `low-confidence predictions never switch the profile`() {
        val controller = controller()

        repeat(10) { i ->
            controller.onGenre(GenreState("Electronic---Trance", 0.1f, second * (i + 1)))
        }

        assertEquals(EffectProfiles.DEFAULT.id, controller.activeProfile.id)
    }

    @Test
    fun `a manual override beats the classifier until it is cleared`() {
        val controller = controller()
        controller.manualProfile = EffectProfiles.AMBIENT
        assertTrue(controller.isOverridden)
        assertEquals(EffectProfiles.AMBIENT.id, controller.activeProfile.id)

        repeat(6) { i ->
            controller.onGenre(GenreState("Electronic---Hardstyle", 0.9f, second * (i + 1)))
        }
        assertEquals(
            "the classifier must not override a manual choice",
            EffectProfiles.AMBIENT.id, controller.activeProfile.id
        )

        controller.manualProfile = null

        assertEquals(
            "clearing the override should fall back to what the classifier decided",
            EffectProfiles.HARDSTYLE.id, controller.activeProfile.id
        )
    }

    @Test
    fun `frames are rendered and dispatched with their timestamp`() {
        val controller = controller()

        controller.renderFrame(second)
        controller.renderFrame(second + 33_000_000L)

        assertEquals(2, output.frames.size)
        assertEquals(listOf(second, second + 33_000_000L), output.frameTimestamps)
        assertEquals(26, output.frames.first().ledCount)
    }

    @Test
    fun `reset returns to the default profile and clears the renderer`() {
        val controller = controller()
        repeat(6) { i -> controller.onGenre(GenreState("Electronic---Trance", 0.9f, second * (i + 1))) }
        controller.onBeat(beat(second))
        assertEquals(EffectProfiles.TRANCE.id, controller.activeProfile.id)

        controller.reset()

        assertEquals(EffectProfiles.DEFAULT.id, controller.activeProfile.id)
    }
}
