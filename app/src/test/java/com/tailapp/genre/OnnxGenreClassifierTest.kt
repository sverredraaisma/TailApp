package com.tailapp.genre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Everything about the ONNX classifier that can be checked on the JVM.
 *
 * Inference itself cannot: `libonnxruntime.so` is an Android native library and
 * there are no instrumented tests in this project. What *is* testable is the
 * part that decides whether inference happens at all — and that is the part that
 * has to behave when the weights are absent, which on a fresh install is always.
 */
class OnnxGenreClassifierTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): GenreModelStore =
        GenreModelStore(File(temporaryFolder.root, GenreModelStore.DIRECTORY_NAME))

    private fun labelsJson(vararg names: String): String =
        """{"name":"test","classes":[${names.joinToString(",") { "\"$it\"" }}]}"""

    /** A metadata file with the full complement the head actually predicts. */
    private fun fullLabelsJson(): String =
        labelsJson(*Array(GenreLabels.EXPECTED_COUNT) { "Genre---$it" })

    // --- the models-absent path -----------------------------------------------

    @Test
    fun `reports not installed when the directory does not exist`() {
        val store = store()
        assertFalse(store.isInstalled)
        assertEquals(GenreModelStore.ALL, store.missing)
    }

    @Test
    fun `create returns null when the models are absent`() {
        assertNull(OnnxGenreClassifier.create(store(), inputSampleRate = 22050))
    }

    @Test
    fun `create returns null when only some artifacts are installed`() {
        val store = store()
        store.install(GenreModelStore.EMBEDDING_MODEL, ByteArrayInputStream(ByteArray(64) { 1 }))
        store.install(GenreModelStore.LABELS, ByteArrayInputStream(fullLabelsJson().toByteArray()))

        assertFalse(store.isInstalled)
        assertEquals(listOf(GenreModelStore.GENRE_HEAD), store.missing)
        assertNull(OnnxGenreClassifier.create(store, inputSampleRate = 22050))
    }

    @Test
    fun `create returns null when the labels file is unreadable`() {
        val store = store()
        GenreModelStore.ALL.forEach {
            store.install(it, ByteArrayInputStream(ByteArray(64) { 1 }))
        }
        assertTrue(store.isInstalled)
        // Installed but not JSON: a corrupt metadata file must not take the app
        // down, it must leave the genre tier inert like an absent one.
        assertNull(OnnxGenreClassifier.create(store, inputSampleRate = 22050))
    }

    @Test
    fun `create succeeds once all three artifacts are present`() {
        val store = store()
        store.install(GenreModelStore.EMBEDDING_MODEL, ByteArrayInputStream(ByteArray(64) { 1 }))
        store.install(GenreModelStore.GENRE_HEAD, ByteArrayInputStream(ByteArray(64) { 2 }))
        store.install(GenreModelStore.LABELS, ByteArrayInputStream(fullLabelsJson().toByteArray()))

        val classifier = OnnxGenreClassifier.create(store, inputSampleRate = 22050)
        assertNotNull(classifier)
        // Construction is lazy: no ONNX Runtime, no native library, nothing that
        // could throw on a JVM. The session is only built on the first classify.
        assertTrue(classifier!!.isAvailable)
        assertEquals(22050, classifier.sampleRate)
        assertEquals(OnnxGenreClassifier.DEFAULT_WINDOW_SECONDS, classifier.windowSeconds, 0f)
    }

    @Test
    fun `create rejects a truncated label list`() {
        val store = store()
        store.install(GenreModelStore.EMBEDDING_MODEL, ByteArrayInputStream(ByteArray(64) { 1 }))
        store.install(GenreModelStore.GENRE_HEAD, ByteArrayInputStream(ByteArray(64) { 2 }))
        // Well-formed JSON, right key, wrong length: the failure mode a capacity
        // hint cannot catch. The head still returns its own 400 scores, so a short
        // list produces a confident wrong genre rather than an error.
        store.install(GenreModelStore.LABELS, ByteArrayInputStream(labelsJson("A", "B").toByteArray()))

        assertTrue(store.isInstalled)
        assertNull(OnnxGenreClassifier.create(store, inputSampleRate = 22050))
    }

    @Test
    fun `the default window is exactly one patch, so no audio is analysed and dropped`() {
        val samples =
            (OnnxGenreClassifier.DEFAULT_WINDOW_SECONDS * EffnetMelSpectrogram.SAMPLE_RATE).toInt()
        val frames = EffnetMelSpectrogram.frameCount(samples)

        assertEquals("the window must fill exactly one patch", 1, EffnetMelSpectrogram.patchCount(frames))
        // ...and barely more than one, so the un-classified tail is the resampler
        // slack rather than a third of the window as it used to be.
        val used = EffnetMelSpectrogram.framesForPatches(1)
        assertTrue("$frames frames computed for $used used", frames - used <= 4)
    }

    // --- the store ------------------------------------------------------------

    @Test
    fun `install replaces an existing artifact atomically`() {
        val store = store()
        store.install(GenreModelStore.LABELS, ByteArrayInputStream("first".toByteArray()))
        store.install(GenreModelStore.LABELS, ByteArrayInputStream("second".toByteArray()))

        assertEquals("second", store.labels.readText())
        // No .part left behind to be mistaken for a real artifact.
        assertEquals(
            emptyList<String>(),
            store.directory.list()!!.filter { it.endsWith(".part") }
        )
    }

    @Test
    fun `install rejects unknown artifact names`() {
        val store = store()
        val failure = runCatching {
            store.install("something-else.onnx", ByteArrayInputStream(ByteArray(4)))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `clear removes every artifact`() {
        val store = store()
        GenreModelStore.ALL.forEach { store.install(it, ByteArrayInputStream(ByteArray(8) { 3 })) }
        assertTrue(store.isInstalled)

        store.clear()
        assertFalse(store.isInstalled)
        assertEquals(GenreModelStore.ALL, store.missing)
    }

    // --- label mapping --------------------------------------------------------

    @Test
    fun `labels parse out of the model metadata`() {
        val labels = GenreLabels.parse(
            """
            {
              "name": "Genre Discogs400",
              "classes": [
                "Blues---Boogie Woogie",
                "Children's---Educational",
                "Electronic---Drum n Bass",
                "Stage & Screen---Theme"
              ],
              "inference": {"sample_rate": 16000}
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "Blues---Boogie Woogie",
                "Children's---Educational",
                "Electronic---Drum n Bass",
                "Stage & Screen---Theme"
            ),
            labels
        )
    }

    @Test
    fun `labels handle escapes`() {
        // Raw string: what is written here is exactly the JSON bytes on disk.
        val labels = GenreLabels.parse("""{"classes":["a\"b","c\\d","e\u0041f","g\th"]}""")
        assertEquals(listOf("a\"b", "c\\d", "eAf", "g\th"), labels)
    }

    @Test
    fun `labels reject metadata without a classes array`() {
        val failure = runCatching { GenreLabels.parse("""{"name":"nope"}""") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `labels reject a truncated array`() {
        val failure = runCatching { GenreLabels.parse("""{"classes":["a","b""") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    // --- prediction -----------------------------------------------------------

    @Test
    fun `argmax maps to the right label`() {
        val labels = listOf("Rock---Punk", "Electronic---Trance", "Jazz---Bebop")
        val state = GenrePrediction.toState(
            scores = floatArrayOf(0.1f, 0.8f, 0.3f),
            labels = labels,
            timestampNanos = 42L
        )
        assertNotNull(state)
        assertEquals("Electronic---Trance", state!!.label)
        assertEquals(0.8f, state.confidence, 1e-6f)
        assertEquals(42L, state.timestampNanos)
    }

    @Test
    fun `alternatives are ordered best first and exclude the winner`() {
        val labels = listOf("a", "b", "c", "d", "e", "f")
        val state = GenrePrediction.toState(
            scores = floatArrayOf(0.10f, 0.90f, 0.55f, 0.70f, 0.20f, 0.65f),
            labels = labels,
            timestampNanos = 0L,
            alternativeCount = 3
        )!!

        assertEquals("b", state.label)
        assertEquals(listOf("d", "f", "c"), state.alternatives.map { it.first })
        assertEquals(listOf(0.70f, 0.65f, 0.55f), state.alternatives.map { it.second })

        val scores = state.alternatives.map { it.second }
        assertEquals(scores.sortedDescending(), scores)
        assertTrue(state.alternatives.none { it.first == state.label })
        assertTrue(state.alternatives.all { it.second <= state.confidence })
    }

    @Test
    fun `alternatives can be switched off`() {
        val state = GenrePrediction.toState(
            scores = floatArrayOf(0.2f, 0.9f),
            labels = listOf("a", "b"),
            timestampNanos = 0L,
            alternativeCount = 0
        )!!
        assertEquals(emptyList<Pair<String, Float>>(), state.alternatives)
    }

    @Test
    fun `a weak winner is reported as no opinion`() {
        assertNull(
            GenrePrediction.toState(
                scores = floatArrayOf(0.01f, 0.02f),
                labels = listOf("a", "b"),
                timestampNanos = 0L,
                minConfidence = 0.3f
            )
        )
    }

    @Test
    fun `a label list that does not match the head is rejected`() {
        // A mismatched labels file next to the right weights would otherwise
        // mislabel every window silently.
        assertNull(
            GenrePrediction.toState(
                scores = FloatArray(400) { 0.5f },
                labels = listOf("a", "b"),
                timestampNanos = 0L
            )
        )
    }

    @Test
    fun `patch scores are averaged, not concatenated`() {
        val pooled = GenrePrediction.poolPatches(
            scores = floatArrayOf(
                0.0f, 1.0f, 0.5f,
                1.0f, 0.0f, 0.5f
            ),
            patches = 2,
            classCount = 3
        )
        assertEquals(3, pooled.size)
        assertEquals(0.5f, pooled[0], 1e-6f)
        assertEquals(0.5f, pooled[1], 1e-6f)
        assertEquals(0.5f, pooled[2], 1e-6f)
    }

    @Test
    fun `a single patch is passed through`() {
        val pooled = GenrePrediction.poolPatches(floatArrayOf(0.1f, 0.9f), patches = 1, classCount = 2)
        assertEquals(0.1f, pooled[0], 0f)
        assertEquals(0.9f, pooled[1], 0f)
    }
}
