package com.tailapp.beat

import com.tailapp.audio.BeatNetFeatureExtractor
import com.tailapp.audio.BeatNetFrame
import com.tailapp.audio.FeatureConfig
import com.tailapp.testutil.BeatReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Everything about the CRNN activation source that can be checked on the JVM.
 *
 * Inference itself cannot: `libonnxruntime.so` is an Android native library and
 * this project has no instrumented tests — the same limitation the genre tier
 * documents. **Per-frame inference cost therefore cannot be measured here
 * either**, only on a device; `docs/beat-model.md` carries the operation count
 * and what it implies against the 20 ms hop.
 *
 * What *is* testable, and all of it matters:
 *
 * - the model-absent path, which on a fresh install is the only path that runs;
 * - the pairing gate: [CrnnActivationSource.create] refuses a shared front-end
 *   whose frames could not be matched up with BeatNet's one for one;
 * - that a mis-shaped frame disables the source rather than corrupting the input;
 * - that an unloadable model degrades to silence exactly once;
 * - that [CrnnActivationSource.reset] clears the cell state as well as the
 *   hidden state.
 *
 * The numeric check that used to live here — that the 272-vector fed to the model
 * is madmom's stacked difference — moved to `BeatNetFeatureExtractorTest` along
 * with the code that builds it. It is stronger there: the whole vector is now
 * computed from audio rather than assembled from reference bands, and it is
 * diffed against madmom at **1.1e-6** over all 81 328 values.
 */
class CrnnActivationSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): BeatModelStore =
        BeatModelStore(File(temporaryFolder.root, BeatModelStore.DIRECTORY_NAME))

    private fun installStub(store: BeatModelStore, bytes: Int = 64) {
        store.install(BeatModelStore.CRNN_MODEL, ByteArrayInputStream(ByteArray(bytes) { 1 }))
    }

    /**
     * The shipped shared front-end. It is *not* BeatNet's — that is the whole
     * finding of `BeatNetFrontEndParityTest` — and it does not need to be: the
     * CRNN is fed by [BeatNetFeatureExtractor]. What [CrnnActivationSource.create]
     * checks of it is only whether the two can be driven off one audio stream.
     */
    private val pairableConfig = FeatureConfig()

    private fun frame(features: FloatArray, index: Long = 0L) = BeatNetFrame(
        index = index,
        timestampNanos = index * 20_000_000L,
        features = features
    )

    // --- the model-absent path ---------------------------------------------------

    @Test
    fun `reports not installed when the directory does not exist`() {
        val store = store()
        assertFalse(store.isInstalled)
        assertEquals(BeatModelStore.ALL, store.missing)
    }

    @Test
    fun `create returns null when the model is absent`() {
        assertNull(CrnnActivationSource.create(store(), pairableConfig))
    }

    @Test
    fun `create returns null for a zero-length model`() {
        val store = store()
        store.directory.mkdirs()
        store.crnn.createNewFile()

        assertFalse("an empty file is not an installed model", store.isInstalled)
        assertNull(CrnnActivationSource.create(store, pairableConfig))
    }

    @Test
    fun `install is atomic and replaces an existing model`() {
        val store = store()
        installStub(store, bytes = 16)
        assertTrue(store.isInstalled)
        assertEquals(16L, store.crnn.length())

        installStub(store, bytes = 32)
        assertEquals(32L, store.crnn.length())
        assertFalse("no .part is left behind", File(store.directory, "${BeatModelStore.CRNN_MODEL}.part").exists())

        store.clear()
        assertFalse(store.isInstalled)
    }

    @Test
    fun `install rejects an unknown artifact name`() {
        val store = store()
        val failure = runCatching {
            store.install("something-else.onnx", ByteArrayInputStream(ByteArray(4)))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    // --- the pairing gate --------------------------------------------------------

    @Test
    fun `create accepts the shipped feature config, because the CRNN brings its own front-end`() {
        val store = store()
        installStub(store)

        // This used to be the gate that was closed: FeatureConfig()'s filterbank
        // resolves to 205 bands and BeatNet needs 136, so the source refused. It
        // is no longer the right question — the model is fed by
        // BeatNetFeatureExtractor, not by the shared one — and what create now
        // checks is only whether the two front-ends can be driven off one stream.
        assertNotNull(
            "the shipped front-end shares BeatNet's sample rate and hop, so the two pair 1:1",
            CrnnActivationSource.create(store, pairableConfig)
        )
        assertEquals(BeatNetFeatureExtractor.SAMPLE_RATE, pairableConfig.sampleRate)
        assertEquals(BeatNetFeatureExtractor.HOP_SIZE, pairableConfig.hopSize)
    }

    @Test
    fun `create refuses a mismatched sample rate or hop`() {
        val store = store()
        installStub(store)

        // A different rate means the two extractors would be fed differently
        // scaled audio; a different hop means their frame streams run at
        // different rates and no fixed pairing exists.
        assertNull(CrnnActivationSource.create(store, pairableConfig.copy(sampleRate = 44100)))
        assertNull(CrnnActivationSource.create(store, pairableConfig.copy(hopSize = 512)))
    }

    @Test
    fun `create refuses a shared window shorter than BeatNet's first frame`() {
        val store = store()
        installStub(store)

        // 512 < 706: the shared extractor would emit frame 0 before the BeatNet
        // frame it pairs with existed, and the activation would arrive late.
        assertNull(CrnnActivationSource.create(store, pairableConfig.copy(frameSize = 512)))
        assertTrue(
            "the shipped window is comfortably past it",
            pairableConfig.frameSize > BeatNetFeatureExtractor.FIRST_FRAME_END
        )
    }

    @Test
    fun `a frame of the wrong width disables the source instead of corrupting the input`() {
        val store = store()
        installStub(store)
        val source = CrnnActivationSource.create(store, pairableConfig)!!

        // 136 is the band count, not the feature width: the classic half-vector
        // mistake, and it must not reach the model.
        val activation = source.activation(frame(FloatArray(CrnnActivationSource.BAND_COUNT)))

        assertEquals(0f, activation.beat, 0f)
        assertEquals(0f, activation.downbeat, 0f)
        assertFalse("a shape mismatch is structural; there is no retry", source.isAvailable)
    }

    @Test
    fun `an unloadable model degrades to silence exactly once`() {
        val store = store()
        installStub(store)  // 64 bytes of 0x01 is not an ONNX graph
        val source = CrnnActivationSource.create(store, pairableConfig)!!
        assertTrue("loading is lazy; create must not touch the runtime", source.isAvailable)

        val features = FloatArray(CrnnActivationSource.FEATURE_SIZE) { 0.5f }
        val first = source.activation(frame(features))

        // On the JVM this fails as UnsatisfiedLinkError (no native runtime); on a
        // device it would fail as a parse error. Either way it must be caught.
        assertEquals(0f, first.beat, 0f)
        assertEquals(0f, first.downbeat, 0f)
        assertFalse(source.isAvailable)

        val second = source.activation(frame(features, index = 1))
        assertEquals(0f, second.beat, 0f)
        assertEquals(0f, second.downbeat, 0f)
    }

    // --- state handling ----------------------------------------------------------

    @Test
    fun `reset clears the hidden state and the cell state`() {
        val store = store()
        installStub(store)
        val source = CrnnActivationSource.create(store, pairableConfig)!!

        val (hidden, cell) = source.recurrentState
        assertEquals(
            CrnnActivationSource.HIDDEN_LAYERS * CrnnActivationSource.HIDDEN_SIZE,
            hidden.size
        )
        assertEquals(hidden.size, cell.size)

        hidden.fill(0.7f)
        cell.fill(-0.3f)
        source.reset()

        assertTrue("hidden state cleared", source.recurrentState[0].all { it == 0f })
        assertTrue(
            "cell state cleared — an LSTM has two state tensors and forgetting the " +
                "second is the bug that reads as 'it tracks worse the second time'",
            source.recurrentState[1].all { it == 0f }
        )
    }

    /**
     * The other half of a session's memory — the frame the positive difference is
     * taken against — belongs to [BeatNetFeatureExtractor] now, and
     * `BeatNetFeatureExtractorTest.reset starts over` covers it. This pins the
     * split so the two halves cannot quietly diverge: resetting the extractor
     * alone would leave the LSTM mid-bar, resetting the source alone would leave
     * the next frame differencing against a previous session's audio.
     * `LightingEngine.reset` does both.
     */
    @Test
    fun `the difference history belongs to the extractor, not to this`() {
        val extractor = BeatNetFeatureExtractor()
        val audio = FloatArray(BeatNetFeatureExtractor.FIRST_FRAME_END + 2 * BeatNetFeatureExtractor.HOP_SIZE) {
            if (it > BeatNetFeatureExtractor.FIRST_FRAME_END) 0.5f else 0f
        }

        val frames = extractor.push(audio, endTimestampNanos = 0L)
        assertTrue("the step produced a rise", frames.last().features.drop(CrnnActivationSource.BAND_COUNT).any { it > 0f })

        extractor.reset()
        val afterReset = extractor.push(audio, endTimestampNanos = 0L)
        assertTrue(
            "after reset the first frame has no predecessor and so no difference",
            afterReset.first().features.drop(CrnnActivationSource.BAND_COUNT).all { it == 0f }
        )
    }

    // --- numeric parity with madmom ----------------------------------------------

    @Test
    fun `the exported graph's tensor names are the ones this class asks for`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        val names = reference.onnxTensorNames
        for (expected in listOf(
            CrnnActivationSource.INPUT_FEATURES,
            CrnnActivationSource.INPUT_H0,
            CrnnActivationSource.INPUT_C0,
            CrnnActivationSource.OUTPUT_PROBS,
            CrnnActivationSource.OUTPUT_HN,
            CrnnActivationSource.OUTPUT_CN
        )) {
            assertTrue("the exported graph has a tensor named '$expected' (found $names)", expected in names)
        }

        assertEquals(CrnnActivationSource.HIDDEN_LAYERS, reference.hiddenLayers)
        assertEquals(CrnnActivationSource.HIDDEN_SIZE, reference.hiddenSize)
        assertEquals(CrnnActivationSource.BAND_COUNT, reference.numFilters)
        assertEquals(CrnnActivationSource.FEATURE_SIZE, reference.featureDim)
        assertEquals(CrnnActivationSource.SAMPLE_RATE, reference.sampleRate)
        assertEquals(CrnnActivationSource.HOP_SIZE, reference.hopSize)
        assertEquals(CrnnActivationSource.WINDOW_SIZE, reference.winLength)
    }

    @Test
    fun `the model's three outputs are a softmax`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        val activations = reference.activations
        assertEquals(reference.frames * 3, activations.size)
        for (t in 0 until reference.frames) {
            val sum = activations[t * 3] + activations[t * 3 + 1] + activations[t * 3 + 2]
            assertEquals("frame $t's classes sum to 1", 1f, sum, 1e-5f)
        }
        // The beat channel is the one BeatActivation.beat reports, and it is not
        // constant: a fixture where every frame scored the same would pass every
        // other assertion here while proving nothing.
        val beat = FloatArray(reference.frames) { activations[it * 3] }
        assertTrue("the beat channel varies", beat.maxOrNull()!! - beat.minOrNull()!! > 0.3f)
    }

    /**
     * The 272-vector the model is handed is exactly what
     * [BeatNetFeatureExtractor] emits — no copy, no reshape, no reordering in
     * between. That is the whole of this class's input contract now, and the
     * reason the numeric parity check lives in `BeatNetFeatureExtractorTest`
     * (max 1.1e-6 against madmom over 81 328 values) rather than here.
     */
    @Test
    fun `the input width is the extractor's output width`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        assertEquals(CrnnActivationSource.FEATURE_SIZE, BeatNetFeatureExtractor.FEATURE_SIZE)
        assertEquals(CrnnActivationSource.FEATURE_SIZE, reference.featureDim)

        val frames = BeatNetFeatureExtractor().push(reference.signal, endTimestampNanos = 0L)
        assertTrue(frames.isNotEmpty())
        for (frame in frames) {
            assertEquals(CrnnActivationSource.FEATURE_SIZE, frame.features.size)
        }
    }
}
