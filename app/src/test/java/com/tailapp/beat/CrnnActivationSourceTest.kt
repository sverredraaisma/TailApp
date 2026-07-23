package com.tailapp.beat

import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
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
import kotlin.math.abs

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
 * - the model-absent and wrong-front-end paths, which on a fresh install are the
 *   only paths that ever run;
 * - that [CrnnActivationSource.reset] clears the cell state as well as the
 *   hidden state;
 * - that the 272-vector this builds is byte-for-byte the one madmom's
 *   `SpectrogramDifferenceProcessor` builds, diffed against the reference dump.
 *   That last one is the only *numeric* check available without the native
 *   runtime, and it covers the part of the contract most likely to be wrong.
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
     * A [FeatureConfig] whose filterbank happens to resolve to BeatNet's 136
     * bands, so [CrnnActivationSource.create]'s geometry gate can be tested from
     * the passing side.
     *
     * It is *not* BeatNet's front-end — nothing in this project is, which is the
     * whole finding of `BeatNetFrontEndParityTest`. It is 30 Hz to 1500 Hz at 24
     * bands per octave, which lands on 136 bands by arithmetic. The gate checks
     * the shape of what it will be fed, and this has that shape.
     */
    private val bandMatchedConfig = FeatureConfig(fMax = 1500f)

    private fun frame(bands: FloatArray, index: Long = 0L) = FeatureFrame(
        index = index,
        timestampNanos = index * 20_000_000L,
        bands = bands,
        flux = 0f,
        rms = 0f,
        bassEnergy = 0f,
        midEnergy = 0f,
        highEnergy = 0f,
        spectralCentroidHz = 0f
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
        assertNull(CrnnActivationSource.create(store(), bandMatchedConfig))
    }

    @Test
    fun `create returns null for a zero-length model`() {
        val store = store()
        store.directory.mkdirs()
        store.crnn.createNewFile()

        assertFalse("an empty file is not an installed model", store.isInstalled)
        assertNull(CrnnActivationSource.create(store, bandMatchedConfig))
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

    // --- the wrong-front-end path ------------------------------------------------

    @Test
    fun `create refuses the shipped feature config even when the model is installed`() {
        val store = store()
        installStub(store)

        // The gate that is closed today: FeatureConfig()'s filterbank resolves to
        // 205 bands, BeatNet's to 136. See BeatNetFrontEndParityTest.
        assertNull(
            "the CRNN must not run on frames it was not trained on",
            CrnnActivationSource.create(store, FeatureConfig())
        )
    }

    @Test
    fun `create refuses a mismatched sample rate or hop`() {
        val store = store()
        installStub(store)

        assertNull(CrnnActivationSource.create(store, bandMatchedConfig.copy(sampleRate = 44100)))
        assertNull(CrnnActivationSource.create(store, bandMatchedConfig.copy(hopSize = 512)))
    }

    @Test
    fun `create accepts a front-end with BeatNet's frame geometry`() {
        val store = store()
        installStub(store)
        assertNotNull(CrnnActivationSource.create(store, bandMatchedConfig))
    }

    @Test
    fun `a frame of the wrong width disables the source instead of corrupting the input`() {
        val store = store()
        installStub(store)
        val source = CrnnActivationSource.create(store, bandMatchedConfig)!!

        val activation = source.activation(frame(FloatArray(64)))

        assertEquals(0f, activation.beat, 0f)
        assertEquals(0f, activation.downbeat, 0f)
        assertFalse("a shape mismatch is structural; there is no retry", source.isAvailable)
    }

    @Test
    fun `an unloadable model degrades to silence exactly once`() {
        val store = store()
        installStub(store)  // 64 bytes of 0x01 is not an ONNX graph
        val source = CrnnActivationSource.create(store, bandMatchedConfig)!!
        assertTrue("loading is lazy; create must not touch the runtime", source.isAvailable)

        val bands = FloatArray(CrnnActivationSource.BAND_COUNT) { 0.5f }
        val first = source.activation(frame(bands))

        // On the JVM this fails as UnsatisfiedLinkError (no native runtime); on a
        // device it would fail as a parse error. Either way it must be caught.
        assertEquals(0f, first.beat, 0f)
        assertEquals(0f, first.downbeat, 0f)
        assertFalse(source.isAvailable)

        val second = source.activation(frame(bands, index = 1))
        assertEquals(0f, second.beat, 0f)
        assertEquals(0f, second.downbeat, 0f)
    }

    // --- state handling ----------------------------------------------------------

    @Test
    fun `reset clears the hidden state and the cell state`() {
        val store = store()
        installStub(store)
        val source = CrnnActivationSource.create(store, bandMatchedConfig)!!

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

    @Test
    fun `reset clears the difference history so the next frame starts clean`() {
        val store = store()
        installStub(store)
        val source = CrnnActivationSource.create(store, bandMatchedConfig)!!
        val bands = CrnnActivationSource.BAND_COUNT

        source.buildInput(FloatArray(bands) { 0.1f })
        source.buildInput(FloatArray(bands) { 0.9f })
        assertTrue(
            "the second frame sees a rise",
            (bands until 2 * bands).any { source.featureVector[it] > 0f }
        )

        source.reset()
        source.buildInput(FloatArray(bands) { 0.9f })
        assertTrue(
            "after reset the first frame has no predecessor and so no difference",
            (bands until 2 * bands).all { source.featureVector[it] == 0f }
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
     * The one numeric parity check that does not need the native runtime: the
     * 272-vector [CrnnActivationSource.buildInput] assembles, against the one
     * madmom's `SpectrogramDifferenceProcessor(diff_ratio=0.5, positive_diffs=True,
     * stack_diffs=np.hstack)` assembled for the same bands.
     *
     * Achieved: **max |Kotlin − madmom| = 0.0** across all 300x272 values. It is
     * exact rather than close because the two do the same float32 subtraction on
     * the same float32 inputs; the reference bands are handed in rather than
     * recomputed, so nothing upstream can contribute rounding.
     */
    @Test
    fun `the feature vector matches madmom's stacked difference exactly`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        val store = store()
        installStub(store)
        val source = CrnnActivationSource.create(store, bandMatchedConfig)!!

        val bands = reference.bands()
        val bandCount = reference.numFilters
        val width = reference.featureDim
        val expected = reference.features

        var worst = 0f
        var worstAt = -1
        for (t in 0 until reference.frames) {
            val row = FloatArray(bandCount)
            System.arraycopy(bands, t * bandCount, row, 0, bandCount)
            source.buildInput(row)
            for (i in 0 until width) {
                val delta = abs(source.featureVector[i] - expected[t * width + i])
                if (delta > worst) {
                    worst = delta
                    worstAt = t * width + i
                }
            }
        }

        assertTrue(
            "max |Kotlin - madmom| = $worst at index $worstAt over " +
                "${reference.frames}x$width values",
            worst == 0f
        )
    }
}
