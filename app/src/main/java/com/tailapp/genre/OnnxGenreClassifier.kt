package com.tailapp.genre

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.tailapp.audio.dsp.Resampler
import java.nio.FloatBuffer

/**
 * The context tier: Discogs-EffNet plus the `genre_discogs400` head, on ONNX
 * Runtime, entirely on-device.
 *
 * ```
 *   window @ inputSampleRate
 *        -> Resampler            (only when the input is not already 16 kHz)
 *        -> EffnetMelSpectrogram (512/256 frames, 96 Slaney mel bands, log10)
 *        -> [patches, 128, 96]
 *        -> discogs-effnet       -> [patches, 1280]
 *        -> genre_discogs400     -> [patches, 400] sigmoid
 *        -> mean over patches    -> argmax + runners-up -> GenreState
 * ```
 *
 * **Nothing leaves the device.** Both graphs are local files; the only network
 * traffic in this feature's whole life is the one-time artifact fetch in
 * `tools/download_models.py`, run by a developer on a workstation.
 *
 * **Absent models are the normal case, not an error.** The weights are not in
 * the repo (CC BY-NC-ND — see [GenreModelStore]), so a fresh install has none.
 * [create] returns null then, `AppContainer` falls back to [NoGenreClassifier],
 * and the rest of the pipeline never knows. A model that is present but
 * unloadable is handled the same way, once, at first use: the failure is logged,
 * [isAvailable] goes false and every later window short-circuits — a broken
 * install must not cost a session-length stream of exceptions on the analysis
 * thread.
 *
 * **CPU execution is deliberate.** No NNAPI delegate: NNAPI is deprecated as of
 * Android 15 and ONNX Runtime's NNAPI EP falls back to CPU for most of an
 * EfficientNet anyway, so it buys partition overhead and a second code path to
 * debug. One patch every three seconds is a few tens of milliseconds of CPU.
 *
 * @param store where the artifacts live.
 * @param labels class names, parallel to the head's outputs.
 * @param inputSampleRate the rate `LightingEngine` hands windows over at — its
 *   `FeatureConfig.sampleRate` (22050), not the model's 16000. The engine sizes
 *   the window from its own feature rate, so this class resamples rather than
 *   pretending the contract's [sampleRate] changes anything.
 * @param windowSeconds seconds of audio per call. Must be long enough for one
 *   128-frame patch (2.048 s of 16 kHz audio) or every window is rejected.
 * @param minConfidence winner sigmoid below which the window is reported as
 *   "nothing useful to say". Left at 0 by default, and no caller passes anything
 *   else, so today nothing is gated here. This once deferred to a `GenreDebouncer`
 *   / `EffectControllerConfig.minGenreConfidence` pair that no longer exists —
 *   the rolling majority now lives in the composer's own state. If a threshold is
 *   ever wanted again, this is the one place for it: gating twice at two
 *   different values is how one ends up impossible to tune.
 */
class OnnxGenreClassifier internal constructor(
    private val store: GenreModelStore,
    private val labels: List<String>,
    private val inputSampleRate: Int,
    override val windowSeconds: Float,
    private val minConfidence: Float,
    private val alternativeCount: Int
) : GenreClassifier {

    override val sampleRate: Int get() = inputSampleRate

    private val melSpectrogram = EffnetMelSpectrogram()

    /**
     * Kept across calls on purpose: [Resampler] carries a fractional read
     * position and the previous sample, and windows arrive back to back, so
     * resetting per window would stamp a discontinuity into every one of them.
     */
    private val resampler: Resampler? =
        if (inputSampleRate == EffnetMelSpectrogram.SAMPLE_RATE) null
        else Resampler(inputSampleRate, EffnetMelSpectrogram.SAMPLE_RATE)

    private var resampled = FloatArray(
        if (resampler == null) 0
        else (windowSeconds * EffnetMelSpectrogram.SAMPLE_RATE).toInt() + RESAMPLE_SLACK
    )

    private var environment: OrtEnvironment? = null
    private var embeddingSession: OrtSession? = null
    private var headSession: OrtSession? = null
    private var embeddingInput: String = EMBEDDING_INPUT
    private var embeddingOutput: String = EMBEDDING_OUTPUT
    private var headInput: String = HEAD_INPUT
    private var headOutput: String = HEAD_OUTPUT

    private var loaded = false
    private var failed = false

    /** False once loading has been tried and failed; there is no retry. */
    val isAvailable: Boolean get() = !failed

    /**
     * Classifies one window.
     *
     * `@Synchronized` against [close]: the engine runs this on its own dispatcher
     * so a 50-150 ms forward pass cannot stall the render loop, which means a
     * session teardown can now land *during* an inference. Closing the sessions
     * out from under a running `OrtSession.run` is a native crash, not an
     * exception, so the two are mutually exclusive and `close` waits.
     */
    @Synchronized
    override fun classify(samples: FloatArray, timestampNanos: Long): GenreState? {
        if (failed) return null
        if (!ensureLoaded()) return null

        val (audio, count) = toModelRate(samples)
        if (count < EffnetMelSpectrogram.MIN_SAMPLES_PER_PATCH) {
            // Not a failure: a short window simply cannot fill a patch. Warn once
            // per session's worth of windows would be nicer, but this is a
            // configuration mistake that should be loud in logcat.
            Log.w(TAG, "window of $count samples is shorter than one patch; check windowSeconds")
            return null
        }

        val patchValues = EffnetMelSpectrogram.PATCH_FRAMES * EffnetMelSpectrogram.NUM_BANDS
        val patchData = melSpectrogram.computePatches(audio, count)
        val patches = patchData.size / patchValues
        if (patches == 0) return null

        return try {
            val embeddings = runEmbedding(patchData, patches)
            val scores = runHead(embeddings, patches)
            val pooled = GenrePrediction.poolPatches(scores, patches, labels.size)
            GenrePrediction.toState(pooled, labels, timestampNanos, minConfidence, alternativeCount)
        } catch (e: Exception) {
            // One bad inference should not take the lighting session with it, but
            // it is almost always structural (wrong model, wrong shape), so stop
            // trying rather than repeating it every three seconds.
            Log.e(TAG, "inference failed; disabling the genre classifier", e)
            failed = true
            close()
            null
        }
    }

    /**
     * Releases both ONNX sessions — including EffNet's arena, which is the large
     * one — and leaves the classifier reloadable: a later [classify] rebuilds
     * them. Called from the engine's teardown, so a session that ends does not
     * hold tens of megabytes for the rest of the process's life.
     */
    @Synchronized
    override fun close() {
        runCatching { embeddingSession?.close() }
        runCatching { headSession?.close() }
        embeddingSession = null
        headSession = null
        // The OrtEnvironment is process-wide and shared; closing it here would
        // pull it out from under anything else that later wants a session.
        environment = null
        loaded = false
    }

    // --- inference ------------------------------------------------------------

    private fun runEmbedding(patchData: FloatArray, patches: Int): FloatArray {
        val env = environment!!
        val session = embeddingSession!!
        val shape = longArrayOf(
            patches.toLong(),
            EffnetMelSpectrogram.PATCH_FRAMES.toLong(),
            EffnetMelSpectrogram.NUM_BANDS.toLong()
        )
        OnnxTensor.createTensor(env, FloatBuffer.wrap(patchData), shape).use { input ->
            session.run(mapOf(embeddingInput to input)).use { result ->
                val tensor = result.get(embeddingOutput).get() as OnnxTensor
                val out = FloatArray(patches * EMBEDDING_SIZE)
                tensor.floatBuffer.get(out)
                return out
            }
        }
    }

    private fun runHead(embeddings: FloatArray, patches: Int): FloatArray {
        val env = environment!!
        val session = headSession!!
        val shape = longArrayOf(patches.toLong(), EMBEDDING_SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(embeddings), shape).use { input ->
            session.run(mapOf(headInput to input)).use { result ->
                val tensor = result.get(headOutput).get() as OnnxTensor
                // The head's real class count, not the label list's. Reading
                // `patches * labels.size` floats out of a `[patches, 400]` buffer
                // is silently valid when the labels are truncated, and yields the
                // argmax of a *prefix* of patch 0 for every patch — a confident
                // wrong genre with nothing logged.
                val classes = tensor.info.shape.last().toInt()
                check(classes == labels.size) {
                    "the genre head predicts $classes classes but ${labels.size} labels are " +
                        "installed; the labels file does not belong to these weights"
                }
                val out = FloatArray(patches * classes)
                tensor.floatBuffer.get(out)
                return out
            }
        }
    }

    private fun toModelRate(samples: FloatArray): Pair<FloatArray, Int> {
        val converter = resampler ?: return samples to samples.size

        // Linear resampling emits at most ceil(count * ratio) + 1 samples.
        val needed =
            (samples.size.toLong() * EffnetMelSpectrogram.SAMPLE_RATE / inputSampleRate).toInt() + RESAMPLE_SLACK
        if (resampled.size < needed) resampled = FloatArray(needed)
        return resampled to converter.resample(samples, samples.size, resampled)
    }

    // --- loading --------------------------------------------------------------

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (failed) return false

        return try {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // The analysis thread is already sharing a phone with the render
                // loop and the BLE stream; two threads is enough for a model that
                // runs once every few seconds.
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            val embedding = env.createSession(store.embeddingModel.absolutePath, options)
            val head = env.createSession(store.genreHead.absolutePath, options)

            embeddingInput = resolve(embedding.inputNames, EMBEDDING_INPUT, "embedding input")
            embeddingOutput = resolve(embedding.outputNames, EMBEDDING_OUTPUT, "embedding output")
            headInput = resolve(head.inputNames, HEAD_INPUT, "head input")
            headOutput = resolve(head.outputNames, HEAD_OUTPUT, "head output")

            environment = env
            embeddingSession = embedding
            headSession = head
            loaded = true
            Log.i(TAG, "genre models loaded from ${store.directory}")
            true
        } catch (e: Throwable) {
            // Throwable, not Exception: a missing libonnxruntime.so surfaces as
            // UnsatisfiedLinkError, and that must degrade to "no genre tier"
            // rather than killing the analysis thread.
            Log.e(TAG, "could not load the genre models from ${store.directory}", e)
            failed = true
            close()
            false
        }
    }

    /**
     * Picks a tensor name: the one the conversion produced if the model has it,
     * otherwise the model's only one. A single-input, single-output graph that
     * happens to have been converted with different names still works.
     */
    private fun resolve(available: Set<String>, preferred: String, what: String): String {
        if (preferred in available) return preferred
        require(available.size == 1) { "$what is ambiguous: expected $preferred, found $available" }
        val only = available.first()
        Log.w(TAG, "$what is '$only', not '$preferred'; using it")
        return only
    }

    companion object {
        private const val TAG = "OnnxGenreClassifier"

        /** Headroom so linear resampling landing a sample short cannot cost the patch. */
        private const val WINDOW_SLACK = 1.015f

        /** Width of Discogs-EffNet's penultimate layer, and the head's input. */
        const val EMBEDDING_SIZE = 1280

        // Resolved from the converted graphs by tools/convert_to_onnx.py, which
        // prints them; they are the frozen graphs' own names, not the serving
        // signature names in the model JSON.
        const val EMBEDDING_INPUT = "serving_default_melspectrogram:0"
        const val EMBEDDING_OUTPUT = "PartitionedCall:1"
        const val HEAD_INPUT = "serving_default_model_Placeholder:0"
        const val HEAD_OUTPUT = "PartitionedCall:0"

        /** Headroom for the resampler's at-most-one-extra-sample overshoot. */
        private const val RESAMPLE_SLACK = 4

        /**
         * One whole patch, plus 1.5% for the resampler to land short.
         *
         * It used to be three seconds, which is 187 mel frames of which the
         * single 128-frame patch consumed 128: a third of the mel work thrown
         * away, and — worse — the last 0.94 s of every window never classified at
         * all, because the windows are contiguous rather than overlapping. Sizing
         * the window to the patch grid instead means ~98% of the stream reaches
         * the model and nothing is computed to be discarded. Whatever smooths
         * these predictions downstream sees more of them than before, not fewer.
         */
        val DEFAULT_WINDOW_SECONDS =
            EffnetMelSpectrogram.secondsForPatches(1) * WINDOW_SLACK

        /**
         * Builds a classifier, or returns null when the models are not installed
         * or their metadata is unreadable.
         *
         * Does not touch ONNX Runtime — no native library is loaded until the
         * first [classify] — so calling this on the main thread during startup
         * is cheap and cannot throw.
         */
        fun create(
            store: GenreModelStore,
            inputSampleRate: Int,
            windowSeconds: Float = DEFAULT_WINDOW_SECONDS,
            minConfidence: Float = 0f,
            alternativeCount: Int = 4
        ): OnnxGenreClassifier? {
            if (!store.isInstalled) {
                Log.i(TAG, "genre models not installed (missing ${store.missing}); staying inert")
                return null
            }
            val labels = try {
                GenreLabels.parse(store.labels.readText())
            } catch (e: Exception) {
                Log.e(TAG, "could not read genre labels from ${store.labels}", e)
                return null
            }
            // The head is `genre_discogs400`: exactly 400 outputs. A shorter list
            // is a truncated or mismatched download, and it does not fail at
            // inference — it quietly mislabels every window from a prefix of the
            // real score vector. Refuse it here, where the cause is still visible.
            if (labels.size != GenreLabels.EXPECTED_COUNT) {
                Log.e(
                    TAG,
                    "${store.labels} holds ${labels.size} labels, expected " +
                        "${GenreLabels.EXPECTED_COUNT}; the metadata does not match the weights. " +
                        "Re-run tools/download_models.py."
                )
                return null
            }
            return OnnxGenreClassifier(
                store = store,
                labels = labels,
                inputSampleRate = inputSampleRate,
                windowSeconds = windowSeconds,
                minConfidence = minConfidence,
                alternativeCount = alternativeCount
            )
        }
    }
}
