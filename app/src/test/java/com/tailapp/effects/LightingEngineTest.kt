package com.tailapp.effects

import com.tailapp.audio.BeatNetFeatureExtractor
import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureExtractor
import com.tailapp.beat.BeatModelStore
import com.tailapp.composer.Composition
import com.tailapp.composer.EffectLayer
import com.tailapp.model.BlendMode
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

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

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
        dispatcher: CoroutineDispatcher,
        beatModelStore: BeatModelStore? = null
    ): Harness {
        val output = RecordingLightingOutput()
        val source = PlaybackAudioSource(audio, sampleRate)
        val engine = LightingEngine(
            output = output,
            ledLayout = layout,
            scope = scope,
            featureConfig = featureConfig,
            beatModelStore = beatModelStore,
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
    fun `the active composition drives the rendered pixels`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val harness = harness(SyntheticAudio.clickTrack(128f, 5f, featureConfig.sampleRate), scope = scope, dispatcher = dispatcher)
        harness.engine.start()

        // A flat colour is the one composition whose exact output is knowable
        // without reimplementing an effect here — so this asserts the wiring
        // (swap → scene → compositor → output), not the look.
        harness.engine.composition = Composition(
            id = "test.solid",
            name = "Solid",
            layers = listOf(
                EffectLayer(
                    id = "base",
                    name = "Base",
                    effectId = "solid",
                    params = mapOf("color" to 0x204060.toFloat(), "brightness" to 1f),
                    blendMode = BlendMode.OVERWRITE
                )
            )
        )

        assertEquals("test.solid", harness.engine.state.value.compositionId)
        assertEquals("Solid", harness.engine.state.value.compositionName)

        harness.output.clear()
        harness.engine.renderFrame(0L)

        val frame = harness.output.frames.last()
        assertEquals(48, frame.ledCount)
        assertEquals(0x20, frame.red(0))
        assertEquals(0x40, frame.green(0))
        assertEquals(0x60, frame.blue(0))
        assertEquals(0x60, frame.blue(frame.ledCount - 1))

        harness.engine.stop()
        scope.cancel()
    }

    @Test
    fun `loudness and spectrum from the analysis reach the effects`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val harness = harness(
            SyntheticAudio.clickTrack(128f, 5f, featureConfig.sampleRate),
            scope = scope,
            dispatcher = dispatcher
        )
        harness.engine.start()

        // `levelBoost = 1` makes brightness *entirely* the normalised loudness,
        // so a lit pixel here can only have come from audio reaching the context.
        harness.engine.composition = Composition(
            id = "test.level",
            name = "Level",
            layers = listOf(
                EffectLayer(
                    id = "base",
                    name = "Base",
                    effectId = "solid",
                    params = mapOf(
                        "color" to 0xFFFFFF.toFloat(),
                        "brightness" to 1f,
                        "levelBoost" to 1f
                    ),
                    blendMode = BlendMode.OVERWRITE
                )
            )
        )

        // Before any audio the level is zero, so the same stack renders black.
        harness.output.clear()
        harness.engine.renderFrame(0L)
        assertEquals(0, harness.output.frames.last().red(0))

        drive(harness)

        harness.output.clear()
        harness.engine.renderFrame(0L)
        assertTrue(
            "loudness never reached the effect",
            harness.output.frames.last().red(0) > 0
        )

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

    // --- the CRNN activation path -------------------------------------------------

    @Test
    fun `with no beat model the DSP activation runs and says so`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val harness = harness(
            SyntheticAudio.clickTrack(128f, 5f, featureConfig.sampleRate),
            scope = scope,
            dispatcher = dispatcher
        )
        harness.engine.start()

        assertEquals(BeatActivationKind.SPECTRAL_FLUX, harness.engine.state.value.activationSource)
        drive(harness)
        assertEquals(BeatActivationKind.SPECTRAL_FLUX, harness.engine.state.value.activationSource)

        harness.engine.stop()
        scope.cancel()
    }

    /**
     * The degradation path, which is the only CRNN path the JVM can reach:
     * `libonnxruntime.so` is an Android native library, so the first inference
     * throws `UnsatisfiedLinkError` here exactly as a corrupt model would on a
     * device.
     *
     * What must survive that is everything else. The second front-end is built
     * and driven, the model is asked and fails once, the source disables itself,
     * and the session finishes tracking the tempo on the DSP activation with the
     * state reporting which one is live. A broken install must cost one logged
     * failure, not a session.
     */
    @Test
    fun `an unloadable beat model degrades to the DSP activation mid-session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val store = BeatModelStore(File(temporaryFolder.root, BeatModelStore.DIRECTORY_NAME))
        store.install(BeatModelStore.CRNN_MODEL, ByteArrayInputStream(ByteArray(64) { 1 }))

        val harness = harness(
            SyntheticAudio.clickTrack(128f, 30f, featureConfig.sampleRate),
            scope = scope,
            dispatcher = dispatcher,
            beatModelStore = store
        )
        harness.engine.start()

        // Loading is lazy, so before any audio the engine believes the CRNN is live.
        assertEquals(BeatActivationKind.CRNN, harness.engine.state.value.activationSource)

        drive(harness)

        assertEquals(
            "the first inference fails and the source disables itself for good",
            BeatActivationKind.SPECTRAL_FLUX,
            harness.engine.state.value.activationSource
        )
        assertEquals("the pipeline kept tracking", 128f, harness.engine.state.value.bpm, 2f)
        assertTrue("no beats reached the output", harness.output.beats.isNotEmpty())

        harness.engine.stop()
        scope.cancel()
    }

    /**
     * The pairing arithmetic `LightingEngine`'s KDoc claims, checked against the
     * two extractors rather than restated.
     *
     * Shared frame `i` takes BeatNet frame `i + 3`'s activation, and the two
     * windows close 19 samples (0.86 ms) apart.
     */
    @Test
    fun `the two front-ends pair three frames apart, 19 samples out`() {
        val sampleRate = featureConfig.sampleRate
        val audio = SyntheticAudio.clickTrack(120f, 3f, sampleRate)
        val endNanos = (audio.size.toLong() * 1_000_000_000L) / sampleRate

        val sharedFrames = FeatureExtractor(featureConfig).push(audio, endTimestampNanos = endNanos)
        val beatNetFrames = BeatNetFeatureExtractor().push(audio, endTimestampNanos = endNanos)

        val lag = (featureConfig.frameSize - BeatNetFeatureExtractor.FIRST_FRAME_END) / featureConfig.hopSize
        assertEquals("the documented lag", 3, lag)

        // Frame i + lag must always exist by the time frame i does: BeatNet runs
        // ahead by exactly that many, plus whatever the tail of the buffer gave it.
        assertTrue(
            "BeatNet produced ${beatNetFrames.size} frames against ${sharedFrames.size} shared ones",
            beatNetFrames.size >= sharedFrames.size + lag
        )

        val residualSamples = featureConfig.frameSize -
            BeatNetFeatureExtractor.FIRST_FRAME_END - lag * featureConfig.hopSize
        assertEquals("the documented residual", 19, residualSamples)

        // And it is visible in the timestamps, which are what the decoder sees.
        val residualNanos = (residualSamples.toLong() * 1_000_000_000L) / sampleRate
        for (i in sharedFrames.indices) {
            val delta = sharedFrames[i].timestampNanos - beatNetFrames[i + lag].timestampNanos
            assertTrue(
                "frame $i: shared window closes ${delta}ns after its BeatNet partner's, expected ~$residualNanos",
                abs(delta - residualNanos) <= 1L
            )
        }
    }
}
