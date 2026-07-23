package com.tailapp.beat

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrame
import com.tailapp.audio.dsp.LogFilterbank
import java.nio.FloatBuffer

/**
 * BeatNet's CRNN as an [ActivationSource]: one frame in, beat and downbeat
 * probabilities out, entirely on-device.
 *
 * ```
 *   FeatureFrame.bands (136 log-filtered bands)
 *        -> [bands ‖ max(0, bands - previous bands)]   -> 272 floats
 *        -> beatnet-crnn.onnx  (conv -> 2x LSTM(150) -> linear -> softmax)
 *        -> [beat, downbeat, non-beat]
 *        -> BeatActivation(beat, downbeat)
 * ```
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
 * [create] returns null then and `AppContainer` falls back to
 * [SpectralFluxActivationSource]. A model that is present but unloadable
 * degrades the same way, once, at first use: the failure is logged,
 * [isAvailable] goes false, and every later frame short-circuits to a zero
 * activation. A broken install must not cost a 50 Hz stream of exceptions on the
 * analysis thread.
 *
 * ## The front-end this needs, and why nothing produces it yet
 *
 * The CRNN was trained on madmom's `LogarithmicFilteredSpectrogram`, measured
 * out of BeatNet's own `log_spect.py` and dumped by
 * `tools/dump_beat_reference.py`:
 *
 * | | BeatNet | `FeatureConfig` defaults |
 * |---|---|---|
 * | sample rate | 22050 | 22050 ✓ |
 * | hop | 441 (50 fps) | 441 ✓ |
 * | window | **1411** (64 ms) | 2048 ✗ |
 * | bands | **136** | 205 ✗ |
 * | band centres | 46.85 – 10853 Hz | 30 – 10861 Hz ✗ |
 * | filter scaling | unit **area** (`norm_filters=True`) | unit **peak** ✗ |
 * | frame alignment | centred on `t * hop` | window *ends* at `frameSize + t * hop` ✗ |
 * | model input | `bands ‖ positive diff` (272) | `bands` (205) ✗ |
 *
 * Only the first two match. `FeatureConfig`'s KDoc used to claim the whole row
 * matched; `BeatNetFrontEndParityTest` now measures what actually does. See
 * `docs/beat-model.md` for the numbers and for why the defaults were left alone
 * (every other tier and its tests are calibrated against them).
 *
 * So [create] **validates the geometry of the frames it will be fed and refuses
 * to run on the wrong ones.** Feeding a model a differently-computed
 * spectrogram does not produce slightly worse beats, it produces confident
 * nonsense: measured on the reference signal, a best-effort rebanding of our
 * 205-band frames onto BeatNet's 136 makes the CRNN report half-time and
 * collapses the downbeat channel from twelve clean peaks to one. Silently
 * accepting that would be worse than not running at all.
 *
 * Today nothing in the app produces 136-band frames, so this source reports
 * unavailable on the default configuration and the DSP tracker keeps running.
 * What is missing is a BeatNet-geometry front-end (a 1411-point DFT, madmom's
 * unique-bin filterbank, centred frames) *and* a way to reach it: the
 * [ActivationSource] seam receives a [FeatureFrame], not audio, so the extractor
 * would have to be chosen where `LightingEngine` builds it.
 *
 * @param store where the `.onnx` lives.
 * @param bandCount how many bands the incoming frames carry; validated on every
 *   frame, because a mismatch here is silent corruption rather than a crash.
 */
class CrnnActivationSource internal constructor(
    private val store: BeatModelStore,
    private val bandCount: Int
) : ActivationSource {

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loaded = false
    private var failed = false

    /** False once loading or inference has failed; there is no retry. */
    val isAvailable: Boolean get() = !failed

    // Reused across frames: at 50 Hz the hot path should not be allocating
    // arrays. The ONNX tensors themselves are per-frame — ORT copies into native
    // memory on creation and owns the result — but the Java-side buffers are not.
    private val features = FloatArray(FEATURE_SIZE)

    /**
     * The 272-vector the next inference would submit, for the test that diffs
     * [buildInput] against madmom's own stacked difference. Live, not a copy —
     * the array is reused every frame, which is the point of it existing.
     */
    internal val featureVector: FloatArray get() = features

    private val previousBands = FloatArray(bandCount)
    private val hiddenState = FloatArray(STATE_SIZE)
    private val cellState = FloatArray(STATE_SIZE)
    private var hasPreviousFrame = false

    /**
     * The LSTM's hidden and cell state, for the test that [reset] clears *both*.
     * Live arrays, in that order. Inference cannot run on the JVM, so poking
     * these is the only way to assert the thing that actually goes wrong —
     * clearing the hidden state and forgetting the cell state.
     */
    internal val recurrentState: List<FloatArray> get() = listOf(hiddenState, cellState)

    override fun activation(frame: FeatureFrame): BeatActivation {
        if (failed) return SILENT
        if (frame.bands.size != bandCount) {
            // Structural: the extractor feeding us is not the one create()
            // inspected. Stop rather than feed the model a mis-shaped vector.
            Log.e(TAG, "expected $bandCount bands, got ${frame.bands.size}; disabling the CRNN")
            failed = true
            close()
            return SILENT
        }
        if (!ensureLoaded()) return SILENT

        buildInput(frame.bands)

        return try {
            runFrame()
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
     * Drops the recurrent state and the difference history: the next frame is
     * treated as the first of a new session.
     *
     * Not optional. The LSTM's state encodes where in the bar the model thinks
     * it is; carrying a previous session's into a new one starts the tracker
     * confidently in the wrong place and it can take bars to recover.
     */
    override fun reset() {
        hiddenState.fill(0f)
        cellState.fill(0f)
        previousBands.fill(0f)
        hasPreviousFrame = false
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

    // --- input ----------------------------------------------------------------

    /**
     * `[bands ‖ max(0, bands - previous)]`, which is what madmom's
     * `SpectrogramDifferenceProcessor(diff_ratio=0.5, positive_diffs=True,
     * stack_diffs=np.hstack)` produces at this window and hop: the dump records
     * `diffFrames = 1`, so "the previous frame" is the whole of it.
     *
     * The first frame has no predecessor. madmom pads the difference with zeros
     * there and so does this — an all-zero difference reads as "nothing changed",
     * which is the right thing for the first frame of a session, whereas
     * differencing against silence would stamp a phantom onset onto it.
     */
    internal fun buildInput(bands: FloatArray) {
        System.arraycopy(bands, 0, features, 0, bandCount)
        if (hasPreviousFrame) {
            for (i in 0 until bandCount) {
                val delta = bands[i] - previousBands[i]
                features[bandCount + i] = if (delta > 0f) delta else 0f
            }
        } else {
            features.fill(0f, bandCount, FEATURE_SIZE)
            hasPreviousFrame = true
        }
        System.arraycopy(bands, 0, previousBands, 0, bandCount)
    }

    private fun runFrame(): BeatActivation {
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

        // --- BeatNet's geometry, from tools/dump_beat_reference.py -------------

        /** Analysis rate the CRNN was trained at. */
        const val SAMPLE_RATE = 22050

        /** 20 ms — 50 activation frames per second. */
        const val HOP_SIZE = 441

        /** 64 ms. Not a power of two, which is why [com.tailapp.audio.dsp.Fft] cannot produce it. */
        const val WINDOW_SIZE = 1411

        /** Filters madmom's `LogarithmicFilterbank` resolves to at that window. */
        const val BAND_COUNT = 136

        /** `bands ‖ positive difference`. */
        const val FEATURE_SIZE = 2 * BAND_COUNT

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
         * frames it would be fed are not the ones BeatNet was trained on.
         *
         * Does not touch ONNX Runtime — no native library is loaded until the
         * first [activation] — so calling this during startup is cheap and cannot
         * throw.
         *
         * @param config the front-end the caller will drive this with. Its sample
         *   rate, hop and resulting band count must be BeatNet's; see the class
         *   doc for what happens when they are not.
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
            // The band count is a property of the filterbank, not of the config,
            // so ask the filterbank. This is the check that fails on the shipped
            // defaults (205 bands, not 136) — see the class doc.
            val bands = LogFilterbank(
                sampleRate = config.sampleRate,
                frameSize = config.frameSize,
                bandsPerOctave = config.bandsPerOctave,
                fMin = config.fMin,
                fMax = config.fMax
            ).bandCount
            if (bands != BAND_COUNT) {
                mismatches += "band count $bands != $BAND_COUNT (window ${config.frameSize}, " +
                    "BeatNet uses $WINDOW_SIZE with madmom's unique-bin filterbank)"
            }
            if (mismatches.isNotEmpty()) {
                Log.w(
                    TAG,
                    "the CRNN is installed but this front-end is not the one it was trained on " +
                        "($mismatches); staying inert. See docs/beat-model.md."
                )
                return null
            }

            return CrnnActivationSource(store, bands)
        }
    }
}
