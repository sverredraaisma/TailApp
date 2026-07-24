package com.tailapp.composer

import com.tailapp.audio.FeatureFrame
import com.tailapp.beat.BeatEvent
import com.tailapp.beat.BeatType
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.drop.SectionStateUpdate
import com.tailapp.model.BlendMode
import com.tailapp.testutil.RecordingLightingOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge between the analysis tiers and the effect graph: what the scene
 * accumulates, how it normalises audio, and what reaches [ReactiveContext].
 */
class CompositionSceneTest {

    private val output = RecordingLightingOutput()
    private val scene = CompositionScene(output).apply { setLayout(listOf(4, 4)) }

    private fun audioFrame(index: Long, rms: Float, bands: FloatArray = FloatArray(4)) =
        FeatureFrame(
            index = index,
            timestampNanos = index * 20_000_000L,
            bands = bands,
            flux = 0f,
            rms = rms,
            bassEnergy = rms,
            midEnergy = rms * 0.5f,
            highEnergy = rms * 0.25f,
            spectralCentroidHz = 1000f
        )

    private fun beat(timestampNanos: Long, bpm: Float = 120f, beatInBar: Int = 0) =
        BeatEvent(BeatType.DOWNBEAT, timestampNanos, bpm, beatInBar, 0.9f)

    // --- calibration ---

    @Test
    fun `beats are shifted by the trigger offset before anything sees them`() {
        scene.triggerOffsetMillis = -40f

        scene.onBeat(beat(timestampNanos = 1_000_000_000L))

        // Both the context and the output must see the shifted instant, or the
        // calibration would apply to the lighting but not to event-driven outputs.
        assertEquals(960_000_000L, output.beats.single().timestampNanos)
        assertEquals(
            960_000_000L,
            scene.buildContext(nowNanos = 0L, dtSeconds = 0f).lastBeat!!.timestampNanos
        )
    }

    @Test
    fun `drops are shifted by the same offset`() {
        scene.triggerOffsetMillis = 25f

        scene.onDrop(DropEvent(1_000_000_000L, 1f, 4f, 4f, SectionState.BUILDUP))

        assertEquals(1_025_000_000L, output.drops.single().timestampNanos)
    }

    @Test
    fun `section updates are deliberately not offset`() {
        // Sections drive slow modulation, where tens of milliseconds are
        // invisible; offsetting them too would apply the calibration twice to the
        // beats inside a build-up.
        scene.triggerOffsetMillis = -100f
        scene.onSection(SectionStateUpdate(SectionState.BUILDUP, 1f, 5L, ramp = 0.5f))

        val ctx = scene.buildContext(0L, 0f)
        assertEquals(SectionState.BUILDUP, ctx.section)
        assertEquals(0.5f, ctx.sectionRamp, 0f)
    }

    // --- timing ---

    @Test
    fun `beat phase advances with the tempo between beats`() {
        scene.onBeat(beat(timestampNanos = 0L, bpm = 120f))

        // 120 BPM is a beat every 0.5 s, so a quarter second in is half a beat.
        assertEquals(0.5f, scene.buildContext(250_000_000L, 0f).beatPhase, 1e-4f)
        assertEquals(0f, scene.buildContext(0L, 0f).beatPhase, 1e-4f)
    }

    @Test
    fun `beat phase keeps cycling when a beat is missed`() {
        scene.onBeat(beat(timestampNanos = 0L, bpm = 120f))

        // 1.25 s after the last beat is 2.5 beats — phase should have wrapped
        // rather than run away or frozen, so anything driven by it coasts.
        assertEquals(0.5f, scene.buildContext(1_250_000_000L, 0f).beatPhase, 1e-4f)
    }

    @Test
    fun `bar phase combines the beat position in the bar with the beat phase`() {
        scene.onBeat(beat(timestampNanos = 0L, bpm = 120f, beatInBar = 2))

        // Beat 2 of 4, halfway to the next beat: (2 + 0.5) / 4.
        assertEquals(0.625f, scene.buildContext(250_000_000L, 0f).barPhase, 1e-4f)
    }

    @Test
    fun `with no beat yet there is no tempo and no phase`() {
        val ctx = scene.buildContext(0L, 0f)

        assertEquals(0f, ctx.bpm, 0f)
        assertEquals(0f, ctx.beatPhase, 0f)
        assertTrue(ctx.secondsSinceBeat >= ReactiveContext.NO_EVENT_SECONDS)
    }

    @Test
    fun `session time is measured from the first rendered frame, not from boot`() {
        scene.render(1_000_000_000L)

        assertEquals(0f, scene.buildContext(1_000_000_000L, 0f).timeSeconds, 1e-6f)
        assertEquals(2f, scene.buildContext(3_000_000_000L, 0f).timeSeconds, 1e-6f)
    }

    // --- audio normalisation ---

    @Test
    fun `a steady signal normalises toward full level whatever its absolute size`() {
        // The point of the adaptive normaliser: a quiet phone mic and a loud line
        // input both end up using the whole 0..1 range, so a threshold set
        // against one means the same thing on the other.
        repeat(200) { scene.onAudioFrame(audioFrame(it.toLong(), rms = 0.02f)) }

        assertTrue(
            "quiet input never reached a usable level",
            scene.buildContext(0L, 0f).level > 0.9f
        )
    }

    @Test
    fun `the raw rms is passed through un-normalised`() {
        scene.onAudioFrame(audioFrame(0, rms = 0.02f))

        assertEquals(0.02f, scene.buildContext(0L, 0f).rms, 1e-6f)
    }

    @Test
    fun `the spectrum is normalised against one shared peak, preserving its shape`() {
        // Per-band normalisation would flatten the spectrum into a wall — every
        // band would eventually reach 1, including on near-silence.
        val bands = floatArrayOf(0.1f, 0.2f, 0.4f, 0.8f)
        repeat(200) { scene.onAudioFrame(audioFrame(it.toLong(), rms = 0.5f, bands = bands)) }

        val ctx = scene.buildContext(0L, 0f)
        assertEquals(4, ctx.bandCount)
        assertEquals(1f, ctx.band(3), 1e-3f)
        assertEquals(0.125f, ctx.band(0) / ctx.band(3), 1e-3f)
        assertEquals(0.5f, ctx.band(2) / ctx.band(3), 1e-3f)
    }

    @Test
    fun `silence leaves every band at zero`() {
        repeat(50) { scene.onAudioFrame(audioFrame(it.toLong(), rms = 0f, bands = FloatArray(4))) }

        val ctx = scene.buildContext(0L, 0f)
        for (i in 0 until ctx.bandCount) assertEquals(0f, ctx.band(i), 1e-6f)
    }

    @Test
    fun `loudness attacks faster than it releases`() {
        repeat(100) { scene.onAudioFrame(audioFrame(it.toLong(), rms = 0.5f)) }
        val loud = scene.buildContext(0L, 0f).level

        // One quiet frame must not collapse the level — a musical envelope, not
        // the waveform's own jitter.
        scene.onAudioFrame(audioFrame(100, rms = 0f))
        val afterOneQuietFrame = scene.buildContext(0L, 0f).level

        assertTrue(afterOneQuietFrame < loud)
        assertTrue("the release was as abrupt as the attack", afterOneQuietFrame > 0.5f)
    }

    // --- composition swapping ---

    @Test
    fun `a queued composition takes effect on the next rendered frame`() {
        scene.setComposition(
            Composition(id = "x", name = "X", layers = listOf(solidLayer("a", 0x203040)))
        )

        // Reported immediately, so the monitor can name it before the first frame.
        assertEquals("x", scene.composition.id)

        val frame = scene.render(0L)
        assertEquals(0x203040, frame.packed(0))
    }

    @Test
    fun `rendering publishes the frame to the output`() {
        scene.setComposition(
            Composition(id = "x", name = "X", layers = listOf(solidLayer("a", 0x010203)))
        )

        scene.render(42L)

        assertEquals(1, output.frames.size)
        assertEquals(42L, output.frameTimestamps.single())
        assertEquals(0x010203, output.frames.single().packed(0))
    }

    @Test
    fun `reset clears accumulated events but keeps the composition`() {
        scene.setComposition(
            Composition(id = "x", name = "X", layers = listOf(solidLayer("a", 0x010203)))
        )
        scene.onBeat(beat(0L))
        scene.onSection(SectionStateUpdate(SectionState.BREAKDOWN, 1f, 0L, 0.4f))
        repeat(50) { scene.onAudioFrame(audioFrame(it.toLong(), rms = 0.5f)) }
        scene.render(0L)

        scene.reset()

        val ctx = scene.buildContext(0L, 0f)
        assertEquals(0, ctx.beatCount)
        assertEquals(0f, ctx.level, 0f)
        assertEquals(SectionState.UNKNOWN, ctx.section)
        assertEquals(0, ctx.bandCount)
        // The stack itself survives — a session restart must not lose it.
        assertEquals("x", scene.composition.id)
        assertEquals(0x010203, scene.render(0L).packed(0))
    }

    @Test
    fun `the beat counter increments per beat and drives round-robin effects`() {
        repeat(3) { scene.onBeat(beat(timestampNanos = it * 500_000_000L)) }

        assertEquals(3, scene.buildContext(0L, 0f).beatCount)
    }

    @Test
    fun `a layout change resizes the rendered frame`() {
        scene.setComposition(
            Composition(
                id = "x", name = "X",
                layers = listOf(solidLayer("a", 0x010203, BlendMode.OVERWRITE))
            )
        )
        assertEquals(8, scene.render(0L).ledCount)

        scene.setLayout(listOf(5, 5, 5))

        assertEquals(15, scene.ledCount)
        assertEquals(15, scene.render(0L).ledCount)
    }
}
