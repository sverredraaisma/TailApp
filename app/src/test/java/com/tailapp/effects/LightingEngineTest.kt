package com.tailapp.effects

import com.tailapp.audio.FeatureConfig
import com.tailapp.testutil.PlaybackAudioSource
import com.tailapp.testutil.RecordingLightingOutput
import com.tailapp.testutil.SyntheticAudio
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole pipeline end to end: synthetic audio in, LED frames out.
 *
 * The engine's loops are driven by hand rather than by their coroutines, so the
 * test is deterministic and finishes in milliseconds. What it proves is the part
 * unit tests cannot: that the real extractor, the real tracker and the real
 * renderer agree about sample rates, hop timing and event timestamps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightingEngineTest {

    private val featureConfig = FeatureConfig()
    private val layout = MutableStateFlow(listOf(8, 10, 12, 10, 8))

    private class Harness(
        val engine: LightingEngine,
        val output: RecordingLightingOutput,
        val source: PlaybackAudioSource
    )

    private fun harness(
        audio: FloatArray,
        sampleRate: Int = FeatureConfig().sampleRate,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher
    ): Harness {
        val output = RecordingLightingOutput()
        val source = PlaybackAudioSource(audio, sampleRate)
        val engine = LightingEngine(
            output = output,
            ledLayout = layout,
            scope = scope,
            featureConfig = featureConfig,
            // The engine's own loops must run on the test scheduler, not on
            // Dispatchers.Default — otherwise they race the hand-driven pump
            // below on a real thread and corrupt the extractor's ring buffer.
            workDispatcher = dispatcher,
            audioSourceFactory = { source },
            clock = { 0L }
        )
        return Harness(engine, output, source)
    }

    /** Pushes the whole buffer through, stepping the wall clock in step with the audio. */
    private fun drive(harness: Harness, renderEveryNanos: Long = 33_000_000L) {
        var nowNanos = 0L
        var nextRender = 0L
        // One pump per ~10 ms of audio, which is what the real loop's poll gives.
        val stepNanos = 10_000_000L
        while (!harness.source.isExhausted) {
            harness.engine.pumpAnalysis(nowNanos)
            if (nowNanos >= nextRender) {
                harness.engine.renderFrame(nowNanos)
                nextRender = nowNanos + renderEveryNanos
            }
            nowNanos += stepNanos
        }
    }

    @Test
    fun `tracks the tempo of a click track through the whole pipeline`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val audio = SyntheticAudio.clickTrack(128f, 30f, featureConfig.sampleRate)
        val harness = harness(audio, scope = scope, dispatcher = dispatcher)
        harness.engine.start()

        drive(harness)

        val state = harness.engine.state.value
        assertEquals("tempo through the real front-end", 128f, state.bpm, 2f)
        assertTrue("no beats reached the output", harness.output.beats.isNotEmpty())
        assertNotNull(state.lastBeat)
        harness.engine.stop()
        scope.cancel()
    }

    @Test
    fun `resamples a 44100 Hz source without losing the tempo`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val audio = SyntheticAudio.clickTrack(128f, 30f, 44100)
        val harness = harness(audio, sampleRate = 44100, scope = scope, dispatcher = dispatcher)
        harness.engine.start()

        drive(harness)

        assertEquals(128f, harness.engine.state.value.bpm, 2f)
        harness.engine.stop()
        scope.cancel()
    }

    @Test
    fun `frames are rendered for the configured layout`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val harness = harness(SyntheticAudio.clickTrack(120f, 5f, featureConfig.sampleRate), scope = scope, dispatcher = dispatcher)
        harness.engine.start()

        drive(harness)

        assertTrue(harness.output.frames.isNotEmpty())
        assertEquals(48, harness.output.frames.first().ledCount)
        assertEquals(48, harness.output.openedWithLedCount)
        harness.engine.stop()
        assertTrue("the output should be closed on stop", harness.output.closed)
        scope.cancel()
    }

    @Test
    fun `silence produces frames but no beats`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val harness = harness(SyntheticAudio.silence(15f, featureConfig.sampleRate), scope = scope, dispatcher = dispatcher)
        harness.engine.start()

        drive(harness)

        assertTrue("silence produced ${harness.output.beats.size} beats", harness.output.beats.isEmpty())
        assertTrue("the render loop should keep running", harness.output.frames.isNotEmpty())
        harness.engine.stop()
        scope.cancel()
    }

    @Test
    fun `a drop in the audio reaches the output`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        // A long quiet stretch fills the detector's trailing window; the step
        // after it is what should fire.
        val audio = SyntheticAudio.energyStep(25f, 5f, featureConfig.sampleRate)
        val harness = harness(audio, scope = scope, dispatcher = dispatcher)
        harness.engine.start()

        drive(harness)

        assertTrue("no drop detected in an energy step", harness.output.drops.isNotEmpty())
        harness.engine.stop()
        scope.cancel()
    }

    @Test
    fun `a manual profile override reaches the renderer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val harness = harness(SyntheticAudio.clickTrack(128f, 5f, featureConfig.sampleRate), scope = scope, dispatcher = dispatcher)
        harness.engine.start()

        harness.engine.manualProfile = EffectProfiles.HARDSTYLE

        assertEquals(EffectProfiles.HARDSTYLE.id, harness.engine.state.value.profileId)
        assertTrue(harness.engine.state.value.isProfileOverridden)
        assertEquals(EffectProfiles.HARDSTYLE.id, harness.output.profiles.last().id)
        harness.engine.stop()
        scope.cancel()
    }

    @Test
    fun `the trigger offset shifts every dispatched beat by the same amount`() = runTest {
        // The pipeline is deterministic, so running the same audio twice and
        // differencing the two beat streams isolates the offset exactly — no
        // tolerance, and no dependence on where the tracker happened to lock on.
        suspend fun beatsWithOffset(offsetMillis: Float): List<Long> {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher)
            val harness = harness(
                SyntheticAudio.clickTrack(128f, 20f, featureConfig.sampleRate),
                scope = scope,
                dispatcher = dispatcher
            )
            harness.engine.start()
            harness.engine.triggerOffsetMillis = offsetMillis
            drive(harness)
            harness.engine.stop()
            scope.cancel()
            return harness.output.beats.map { it.timestampNanos }
        }

        val unshifted = beatsWithOffset(0f)
        val shifted = beatsWithOffset(-40f)

        assertTrue("no beats to compare", unshifted.isNotEmpty())
        assertEquals("the offset changed which beats were detected", unshifted.size, shifted.size)
        unshifted.zip(shifted).forEach { (plain, offset) ->
            assertEquals(plain - 40_000_000L, offset)
        }
    }
}
