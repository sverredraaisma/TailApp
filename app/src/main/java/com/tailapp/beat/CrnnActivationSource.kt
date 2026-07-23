package com.tailapp.beat

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.tailapp.audio.BeatNetFeatureExtractor
import com.tailapp.audio.BeatNetFrame
import com.tailapp.audio.FeatureConfig
import java.nio.FloatBuffer

/**
 * BeatNet's CRNN as a beat activation function: one frame in, beat and downbeat
 * probabilities out, entirely on-device.
 *
 * ```
 *   BeatNetFeatureExtractor  ->  BeatNetFrame.features (272)
 *        -> beatnet-crnn.onnx  (conv -> 2x LSTM(150) -> linear -> softmax)
 *        -> [beat, downbeat, non-beat]
 *        -> BeatActivation(beat, downbeat)
 * ```
 *
 * **This is deliberately not an [ActivationSource].** That interface hands out a
 * [com.tailapp.audio.FeatureFrame] — 205 unit-peak bands from a 2048-sample
 * trailing window — and the CRNN was trained on something else entirely: 136
 * unit-area bands from a 1411-sample *centred* window, stacked with their
 * positive difference. `docs/beat-model.md` measures what happens when the two
 * are conflated: rebanding our frames onto BeatNet's geometry as carefully as the
 * data allows still makes the model report half-time and collapses the downbeat
 * channel from twelve clean peaks to one. So this takes [BeatNetFrame]s from
 * [BeatNetFeatureExtractor] — which matches madmom to 1.1e-6 — and nothing else
 * can be handed to it by accident. `LightingEngine` runs both extractors on the
 * same audio and pairs their frames; see its KDoc for how, and for the residual
 * alignment offset.
 *
 * **The recurrent state is ours, not the graph's.** BeatNet's PyTorch module
 * keeps its LSTM state in instance attributes and never resets it — run two
 * files through one instance and the second starts from wherever the first
 * ended. `tools/export_beatnet.py` lifts that state into explicit graph inputs
 * and outputs precisely so [reset] can guarantee a clean session here. Two
 * tensors, not one: BeatNet's recurrent layer is an LSTM, so there is a cell
 * state as well as a hidden state, and forgetting the cell state is a bug that
 * only shows up as "it tracks worse the second time".
 *
 * **Absent models are the normal case.** The `.onnx` is a build output that does
 * not exist upstream (see [BeatModelStore]), so a fresh install has none.
 * [create] returns null then and `LightingEngine` runs the DSP activation
 * ([SpectralFluxActivationSource], inside whichever [BeatDecoder] is selected)
 * exactly as before. A model that is present but unloadable degrades the same
 * way, once, at first use: the failure is logged, [isAvailable] goes false, every
 * later frame short-circuits to a zero activation, and the engine falls back to
 * the DSP path for the rest of the session. A broken install must not cost a
 * 50 Hz stream of exceptions on the analysis thread.
 *
 * @param store where the `.onnx` lives.
 */
class CrnnActivationSource internal constructor(
    private val store: BeatModelStore
) {

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loaded = false
    private var failed = false

    /** False once loading or inference has failed; there is no retry. */
    val isAvailable: Boolean get() = !failed

    private val hiddenState = FloatArray(STATE_SIZE)
    private val cellState = FloatArray(STATE_SIZE)

    /**
     * The LSTM's hidden and cell state, for the test that [reset] clears *both*.
     * Live arrays, in that order. Inference cannot run on the JVM, so poking
     * these is the only way to assert the thing that actually goes wrong —
     * clearing the hidden state and forgetting the cell state.
     */
    internal val recurrentState: List<FloatArray> get() = listOf(hiddenState, cellState)

    /**
     * Runs one frame.
     *
     * @param frame a [BeatNetFrame] from [BeatNetFeatureExtractor]; its
     *   `features` array is submitted directly, with no copy and no reshaping.
     *   A frame of the wrong width is structural — the extractor feeding this is
     *   not the one it was built for — so it disables the source rather than
     *   handing the model a mis-shaped tensor.
     */
    fun activation(frame: BeatNetFrame): BeatActivation {
        if (failed) return SILENT
        if (frame.features.size != FEATURE_SIZE) {
            Log.e(TAG, "expected $FEATURE_SIZE features, got ${frame.features.size}; disabling the CRNN")
            failed = true
            close()
            return SILENT
        }
        if (!ensureLoaded()) return SILENT

        return try {
            runFrame(frame.features)
        } catch (e: Exception) {
            // One bad inference should not take the lighting session with it, but
            // it is almost always structural (wrong model, wrong shape), so stop
            // rather than repeating it fifty times a second.
            Log.e(TAG, "inference failed; disabling the CRNN", e)
            failed = true
            close()
            SILENT
        }
    }

    /**
     * Drops the recurrent state: the next frame is treated as the first of a new
     * session.
     *
     * Not optional. The LSTM's state encodes where in the bar the model thinks
     * it is; carrying a previous session's into a new one starts the tracker
     * confidently in the wrong place and it can take bars to recover.
     *
     * The *feature* history — the frame the positive difference is taken against
     * — lives in [BeatNetFeatureExtractor] and is cleared by resetting that.
     * `LightingEngine.reset` does both.
     */
    fun reset() {
        hiddenState.fill(0f)
        cellState.fill(0f)
    }

    /** Releases the ONNX session. The source is unusable afterwards unless reloaded. */
    fun close() {
        runCatching { session?.close() }
        session = null
        // The OrtEnvironment is process-wide and shared; closing it here would
        // pull it out from under anything else that later wants a session.
        environment = null
        loaded = false
    }

    private fun runFrame(features: FloatArray): BeatActivation {
        val env = environment!!
        val ortSession = session!!

        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), FEATURE_SHAPE).use { input ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(hiddenState), STATE_SHAPE).use { h0 ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(cellState), STATE_SHAPE).use { c0 ->
                    ortSession.run(
                        mapOf(INPUT_FEATURES to input, INPUT_H0 to h0, INPUT_C0 to c0)
                    ).use { result ->
                        val probs = (result.get(OUTPUT_PROBS).get() as OnnxTensor).floatBuffer
                        val beat = probs.get(BEAT_CLASS)
                        val downbeat = probs.get(DOWNBEAT_CLASS)
                        // Read the new state back *after* the probabilities, so a
                        // failure to read either one leaves the state untouched
                        // rather than half-updated.
                        (result.get(OUTPUT_HN).get() as OnnxTensor).floatBuffer.get(hiddenState)
                        (result.get(OUTPUT_CN).get() as OnnxTensor).floatBuffer.get(cellState)
                        return BeatActivation(beat, downbeat)
                    }
                }
            }
        }
    }

    // --- loading --------------------------------------------------------------

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (failed) return false

        return try {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // One frame of this graph is a 272-wide conv, two 150-cell LSTM
                // steps and a 3-way linear. Splitting that across threads costs
                // more in synchronisation than it saves, fifty times a second,
                // on a phone already running a render loop and a BLE stream.
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            val opened = env.createSession(store.crnn.absolutePath, options)

            val missingInputs = REQUIRED_INPUTS - opened.inputNames
            val missingOutputs = REQUIRED_OUTPUTS - opened.outputNames
            if (missingInputs.isNotEmpty() || missingOutputs.isNotEmpty()) {
                // Unlike the genre models there is no "use the only one" fallback
                // to fall back to: this graph has three inputs and three outputs
                // and guessing which is which would silently swap the hidden and
                // cell states.
                opened.close()
                throw IllegalStateException(
                    "not a streaming BeatNet export: missing inputs $missingInputs, " +
                        "outputs $missingOutputs (found ${opened.inputNames} / ${opened.outputNames}). " +
                        "Re-run tools/export_beatnet.py."
                )
            }

            environment = env
            session = opened
            loaded = true
            reset()
            Log.i(TAG, "BeatNet CRNN loaded from ${store.crnn}")
            true
        } catch (e: Throwable) {
            // Throwable, not Exception: a missing libonnxruntime.so surfaces as
            // UnsatisfiedLinkError, and that must degrade to "DSP activations"
            // rather than killing the analysis thread.
            Log.e(TAG, "could not load the BeatNet CRNN from ${store.crnn}", e)
            failed = true
            close()
            false
        }
    }

    companion object {
        private const val TAG = "CrnnActivationSource"

        // --- BeatNet's geometry, owned by BeatNetFeatureExtractor -------------

        /** Analysis rate the CRNN was trained at. */
        const val SAMPLE_RATE = BeatNetFeatureExtractor.SAMPLE_RATE

        /** 20 ms — 50 activation frames per second. */
        const val HOP_SIZE = BeatNetFeatureExtractor.HOP_SIZE

        /** 64 ms. Not a power of two, hence [com.tailapp.audio.dsp.BluesteinFft]. */
        const val WINDOW_SIZE = BeatNetFeatureExtractor.WINDOW_SIZE

        /** Filters madmom's `LogarithmicFilterbank` resolves to at that window. */
        const val BAND_COUNT = BeatNetFeatureExtractor.BAND_COUNT

        /** `bands ‖ positive difference`. */
        const val FEATURE_SIZE = BeatNetFeatureExtractor.FEATURE_SIZE

        /** `num_layers * batch * hidden_size` = 2 * 1 * 150. */
        const val HIDDEN_LAYERS = 2
        const val HIDDEN_SIZE = 150
        private const val STATE_SIZE = HIDDEN_LAYERS * HIDDEN_SIZE

        // Resolved from the exported graph by tools/export_beatnet.py, which
        // prints them; they are the names that script assigns, not PyTorch's.
        const val INPUT_FEATURES = "features"
        const val INPUT_H0 = "h0"
        const val INPUT_C0 = "c0"
        const val OUTPUT_PROBS = "probs"
        const val OUTPUT_HN = "hn"
        const val OUTPUT_CN = "cn"

        private val REQUIRED_INPUTS = setOf(INPUT_FEATURES, INPUT_H0, INPUT_C0)
        private val REQUIRED_OUTPUTS = setOf(OUTPUT_PROBS, OUTPUT_HN, OUTPUT_CN)

        /** `probs` is `[1, 3, frames]` with frames = 1; the class axis is `[beat, downbeat, non-beat]`. */
        private const val BEAT_CLASS = 0
        private const val DOWNBEAT_CLASS = 1

        private val FEATURE_SHAPE = longArrayOf(1, 1, FEATURE_SIZE.toLong())
        private val STATE_SHAPE = longArrayOf(HIDDEN_LAYERS.toLong(), 1, HIDDEN_SIZE.toLong())

        /** What an unavailable source reports: no beat, no downbeat, no opinion. */
        private val SILENT = BeatActivation(0f, 0f)

        /**
         * Builds a source, or returns null when the model is not installed or the
         * *shared* front-end it would run alongside cannot be paired with
         * BeatNet's.
         *
         * The CRNN brings its own front-end ([BeatNetFeatureExtractor]), so
         * [config] is not checked for band count or window — it is checked for
         * the three things that decide whether the two extractors can be driven
         * off one audio stream and their frames matched up one for one:
         *
         * - the same sample rate, since both are fed the same resampled samples;
         * - the same hop, or the two frame streams run at different rates and no
         *   fixed pairing exists;
         * - a shared window at least as long as BeatNet's first frame
         *   ([BeatNetFeatureExtractor.FIRST_FRAME_END]), so the BeatNet frame a
         *   shared frame pairs with has always already been produced. At the
         *   shipped 2048 it has, by three frames.
         *
         * Does not touch ONNX Runtime — no native library is loaded until the
         * first [activation] — so calling this during startup is cheap and cannot
         * throw.
         */
        fun create(store: BeatModelStore, config: FeatureConfig): CrnnActivationSource? {
            if (!store.isInstalled) {
                Log.i(TAG, "BeatNet CRNN not installed (missing ${store.missing}); staying inert")
                return null
            }

            val mismatches = mutableListOf<String>()
            if (config.sampleRate != SAMPLE_RATE) {
                mismatches += "sampleRate ${config.sampleRate} != $SAMPLE_RATE"
            }
            if (config.hopSize != HOP_SIZE) {
                mismatches += "hopSize ${config.hopSize} != $HOP_SIZE"
            }
            if (config.frameSize < BeatNetFeatureExtractor.FIRST_FRAME_END) {
                mismatches += "frameSize ${config.frameSize} is shorter than BeatNet's first frame " +
                    "(${BeatNetFeatureExtractor.FIRST_FRAME_END}), so its activation would arrive late"
            }
            if (mismatches.isNotEmpty()) {
                Log.w(
                    TAG,
                    "the CRNN is installed but cannot be paired with this front-end " +
                        "($mismatches); staying inert. See docs/beat-model.md."
                )
                return null
            }

            return CrnnActivationSource(store)
        }
    }
}
