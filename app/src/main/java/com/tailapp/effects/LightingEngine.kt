@file:OptIn(ExperimentalCoroutinesApi::class)

package com.tailapp.effects

import android.util.Log
import com.tailapp.audio.AudioSource
import com.tailapp.audio.AudioSources
import com.tailapp.audio.BeatNetFeatureExtractor
import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureExtractor
import com.tailapp.audio.OboeAudioSource
import com.tailapp.audio.dsp.Resampler
import com.tailapp.beat.BeatActivation
import com.tailapp.beat.BeatDecoder
import com.tailapp.beat.BeatEvent
import com.tailapp.beat.BeatModelStore
import com.tailapp.beat.BeatTracker
import com.tailapp.beat.CrnnActivationSource
import com.tailapp.beat.OctaveBias
import com.tailapp.beat.ParticleFilterBeatDecoder
import com.tailapp.composer.Composition
import com.tailapp.composer.CompositionScene
import com.tailapp.composer.TailEnd
import com.tailapp.composer.TailTelemetry
import com.tailapp.composer.TailTelemetryTracker
import com.tailapp.model.MotionState
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.drop.TransientAnalyzer
import com.tailapp.genre.GenreClassifier
import com.tailapp.genre.GenreState
import com.tailapp.genre.NoGenreClassifier
import com.tailapp.lighting.LightingOutput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Which beat decoder a session runs.
 *
 * Both are real options rather than old-and-new: measured head to head, the
 * phase-locked tracker is more precise about tempo and never drops a beat on
 * sparse material, while the particle filter is the one that keeps tracking
 * heavily syncopated music the other refuses to lock onto at all. Which matters
 * depends entirely on what is playing, so the choice is the user's.
 */
enum class BeatDecoderKind(val displayName: String, val description: String) {
    PHASE_LOCKED(
        "Phase-locked",
        "Precise tempo, cheap. Can fail to lock on heavily syncopated music."
    ),
    PARTICLE_FILTER(
        "Particle filter",
        "Tracks syncopation the other gives up on. Slightly looser tempo, ~100x the work."
    )
}

/**
 * Which activation function is producing the beat/downbeat curve a session is
 * decoding.
 *
 * Not a user choice — it is whatever is *available*. The CRNN needs its model
 * installed (`docs/beat-model.md` has the `adb push` recipe) and needs to load;
 * absent either, the DSP one runs and the session is otherwise identical. The
 * monitoring screen shows it because "the neural model is installed but silently
 * not running" is exactly the state that is impossible to diagnose from the
 * lighting.
 */
enum class BeatActivationKind(val displayName: String) {
    SPECTRAL_FLUX("Spectral flux"),
    CRNN("BeatNet CRNN")
}

/**
 * Everything the monitoring UI shows about a running session.
 */
data class BeatLightState(
    val isRunning: Boolean = false,
    val bpm: Float = 0f,
    val beatConfidence: Float = 0f,
    val lastBeat: BeatEvent? = null,
    val lastDrop: DropEvent? = null,
    val section: SectionState = SectionState.UNKNOWN,
    val sectionRamp: Float = 0f,
    val genre: GenreState = GenreState.unknown(),
    val compositionId: String = Composition.EMPTY.id,
    val compositionName: String = Composition.EMPTY.name,
    val inputLatencyMillis: Float = 0f,
    val droppedSamples: Long = 0L,
    val decoder: BeatDecoderKind = BeatDecoderKind.PHASE_LOCKED,
    val activationSource: BeatActivationKind = BeatActivationKind.SPECTRAL_FLUX,
    /**
     * The tail's own state, as the effects see it. Surfaced on the monitor so a
     * look that reacts to the body can be debugged the same way a beat-reactive
     * one can — otherwise "nothing happens when I tap it" has no visible cause
     * between the IMU and the pixels.
     */
    val tapCount: Int = 0,
    val lastTapEnd: TailEnd? = null,
    val tail: TailTelemetry = TailTelemetry.AT_REST,
    val error: String? = null
)

/**
 * Runs the whole pipeline: microphone in, LED frames out.
 *
 * Two loops, deliberately separate. The **analysis** loop drains the audio
 * source as fast as audio arrives and pushes feature frames through all three
 * tiers. The **render** loop runs at a fixed frame rate and does not care
 * whether analysis produced anything — the renderer rebuilds each frame from
 * event timestamps, so the lighting stays smooth even when the analysis thread
 * stutters.
 *
 * Both loops call plain synchronous methods ([pumpAnalysis], [renderFrame]) that
 * tests drive directly, so the pipeline can be exercised end to end without a
 * dispatcher, a microphone or a device.
 *
 * ## Two front-ends, and how their frames are paired
 *
 * When [beatModelStore] holds an installed BeatNet CRNN, the same resampled
 * audio is pushed through **two** extractors: the shared [FeatureExtractor],
 * which feeds the tempo estimator, the transient tier and every timestamp, and a
 * [BeatNetFeatureExtractor], whose 272-float frames feed
 * [CrnnActivationSource]. Only the *activation* comes from the model; everything
 * else downstream is unchanged, which is why
 * [BeatDecoder.process] has an overload taking an explicit activation.
 *
 * Both run at the same 441-sample hop, so after start-up they emit frames one
 * for one — but they emit them at different *points*, because their windows are
 * aligned differently. The shared extractor emits frame `i` once
 * `frameSize + i*hop` samples have arrived; the BeatNet extractor's frames are
 * centred, so it emits frame `t` after only `706 + t*hop`. It therefore runs
 * ahead, by
 * ```
 * lag = (frameSize - 706) / hop = (2048 - 706) / 441 = 3 frames
 * ```
 * at the shipped configuration. The pairing is that constant: **shared frame `i`
 * takes the activation of BeatNet frame `i + 3`**, which is exactly the newest
 * BeatNet frame in existence at the instant shared frame `i` closes. It is
 * implemented by discarding the first `lag` activations and then consuming one
 * per shared frame, so it holds regardless of how the audio was chunked.
 *
 * **Residual offset: 19 samples, 0.86 ms.** Window *ends* are the right thing to
 * align here, not window centres: both activation functions respond to an onset
 * on the first frame whose window contains it, so an onset at sample `s` shows up
 * in the first frame whose window closes at or after `s` on either side. Shared
 * frame `i`'s window closes at sample `i*hop + 2047`; BeatNet frame `i + 3`'s
 * closes at `i*hop + 2028`. The activation is stamped with the shared frame's
 * timestamp, so the model's opinion is applied 0.86 ms later than the audio it
 * was formed from — two orders of magnitude inside the ±70 ms window the beat
 * tests assert against, and far inside one hop. (Aligning window *centres*
 * instead would pick frame `i + 2` and a −6.4 ms offset; that is the wrong
 * criterion for a positive-difference feature, and it would also throw away the
 * freshest frame for no gain.)
 *
 * If the model is absent, or fails to load, or a frame is mis-shaped, the CRNN
 * disables itself and every frame from then on runs
 * [BeatDecoder.process] with the decoder's own
 * [com.tailapp.beat.SpectralFluxActivationSource] — the behaviour with no model
 * installed, which is the normal case. [BeatLightState.activationSource] reports
 * which is live. A failure *mid*-session costs about a second: the DSP source's
 * adaptive statistics have seen no frames yet and report nothing until they have
 * a second of history, by design.
 *
 * @param output where frames go — typically the tail and the on-screen preview.
 * @param ledLayout the device's ring configuration, followed live.
 * @param scope lifetime of the loops.
 * @param genreClassifier context tier; inert until the ONNX model lands.
 * @param beatModelStore where the BeatNet CRNN lives, or null to never run it.
 *   Absent or unloadable is the normal case and costs nothing: the second
 *   extractor is not even constructed.
 * @param workDispatcher where the loops run. Defaults to a **single-threaded**
 *   view of [Dispatchers.Default] (`limitedParallelism(1)`): neither loop may
 *   touch the main thread, and — just as importantly — the analysis, render and
 *   layout loops all mutate the shared renderer/controller, so they must be
 *   serialized rather than merely off-main. A multi-threaded pool would let them
 *   run concurrently and corrupt that shared state. Tests substitute their own
 *   single test dispatcher, which is confined for the same reason.
 * @param audioSourceFactory injection seam for tests.
 * @param clock injection seam for tests.
 */
class LightingEngine(
    private val output: LightingOutput,
    private val ledLayout: StateFlow<List<Int>>,
    private val scope: CoroutineScope,
    private val featureConfig: FeatureConfig = FeatureConfig(),
    private val genreClassifier: GenreClassifier = NoGenreClassifier,
    beatModelStore: BeatModelStore? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val audioSourceFactory: (Int) -> AudioSource = { rate -> AudioSources.create(rate) },
    private val clock: () -> Long = System::nanoTime
) {
    private val extractor = FeatureExtractor(featureConfig)

    /**
     * The CRNN, when its model is installed and its front-end can be paired with
     * ours. `create` never touches ONNX Runtime, so resolving this at
     * construction cannot throw and costs nothing on a fresh install.
     */
    private val crnn: CrnnActivationSource? =
        beatModelStore?.let { CrnnActivationSource.create(it, featureConfig) }

    /** BeatNet's own front-end; built only when there is a model to feed. */
    private val beatNetExtractor: BeatNetFeatureExtractor? =
        crnn?.let { BeatNetFeatureExtractor() }

    /** How far ahead the centred BeatNet frames run; see the class doc. */
    private val beatNetLag: Int =
        (featureConfig.frameSize - BeatNetFeatureExtractor.FIRST_FRAME_END) / featureConfig.hopSize

    /** Activations awaiting the shared frame they pair with — one, in steady state. */
    private val pendingActivations = ArrayDeque<BeatActivation>()
    private var beatNetActivationsDiscarded = 0

    /** True while the CRNN is the live activation function. */
    private val crnnLive: Boolean get() = crnn != null && crnn.isAvailable

    /**
     * Rebuilt rather than swapped: the analysis loop reads this on a worker
     * thread, so changing decoders while one is running would be a data race on
     * whatever internal state it carries. [decoderKind] therefore takes effect
     * at the next [start], and the session UI restarts the session to apply it.
     */
    private var beatTracker: BeatDecoder = BeatTracker(featureConfig)

    /** Decoder used by the *next* session. Changing it does not disturb a running one. */
    var decoderKind: BeatDecoderKind = BeatDecoderKind.PHASE_LOCKED

    /**
     * The tempo-octave preference, shared with whichever decoder is running. A
     * mutable holder read every frame, so [OctaveBias.copyFrom] applies a UI
     * change live without restarting the session.
     */
    val octaveBias = OctaveBias()
    private val transients = TransientAnalyzer(featureConfig = featureConfig)

    /**
     * The whole render path: builds a `ReactiveContext` from the analysis and
     * draws the active composition's layer/folder tree through it.
     */
    private val scene = CompositionScene(output, featureConfig)

    /**
     * Derives normalised deflection and wag speed from the FF02 motion state.
     * Lives here rather than in the repository because it needs the render
     * clock: wag speed is a derivative, and the notify interval jitters.
     */
    private val telemetryTracker = TailTelemetryTracker()

    private val _state = MutableStateFlow(BeatLightState(activationSource = activationKind()))
    val state: StateFlow<BeatLightState> = _state.asStateFlow()

    /**
     * Feeds a tap from the tail's IMU into the render pipeline.
     *
     * This is the seam that makes the device's own body an effect input: a tap
     * on the tail can now spawn a ripple, exactly as a beat does. Safe to call
     * from any thread — [CompositionScene] does the hand-off.
     */
    fun onTailTap(end: TailEnd) {
        scene.onTap(end, clock())
        _state.update { it.copy(tapCount = it.tapCount + 1, lastTapEnd = end) }
    }

    /** Feeds the latest FF02 motion state in. Safe to call from any thread. */
    fun onTailMotion(state: MotionState) {
        val telemetry = telemetryTracker.update(state, clock())
        scene.onTailTelemetry(telemetry)
        _state.update { it.copy(tail = telemetry) }
    }

    private var source: AudioSource? = null
    private var resampler: Resampler? = null

    private val readBuffer = FloatArray(READ_BUFFER_SAMPLES)
    private var resampleBuffer = FloatArray(READ_BUFFER_SAMPLES)

    /** Rolling window of analysis-rate audio for the context tier. */
    private var genreWindow = FloatArray(0)
    private var genreWindowFill = 0

    private var latestBeat: BeatEvent? = null
    private var latestDrop: DropEvent? = null
    private var latestRamp = 0f

    private var analysisJob: Job? = null
    private var renderJob: Job? = null
    private var layoutJob: Job? = null

    /** User calibration in milliseconds; negative fires earlier. */
    var triggerOffsetMillis: Float
        get() = scene.triggerOffsetMillis
        set(value) {
            scene.triggerOffsetMillis = value
        }

    /** Applies a tempo-octave preference live, without restarting the session. */
    fun setOctaveBias(enabled: Boolean, targetBpm: Float, strength: Float) {
        octaveBias.enabled = enabled
        octaveBias.targetBpm = targetBpm
        octaveBias.strength = strength
    }

    /**
     * The layer/folder stack being rendered.
     *
     * Safe to set from the UI thread while a session runs: the tree is immutable
     * and the scene swaps it in at the next frame boundary, so an edit can never
     * be seen half-applied.
     */
    var composition: Composition
        get() = scene.composition
        set(value) {
            scene.setComposition(value)
            publishComposition()
        }

    val isRunning: Boolean get() = analysisJob?.isActive == true

    /**
     * Opens the microphone and starts both loops.
     *
     * @throws SecurityException when `RECORD_AUDIO` has not been granted — the
     *   caller is the one that can ask for it.
     */
    suspend fun start() {
        if (isRunning) return

        reset()
        val audio = try {
            audioSourceFactory(featureConfig.sampleRate).also { it.start() }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "could not open the microphone", e)
            _state.update { it.copy(error = e.message ?: e::class.java.simpleName) }
            return
        }
        source = audio
        resampler = if (audio.sampleRate != featureConfig.sampleRate) {
            Resampler(audio.sampleRate, featureConfig.sampleRate)
        } else {
            null
        }

        scene.setLayout(ledLayout.value)
        output.open(scene.ledCount)

        _state.update {
            it.copy(isRunning = true, error = null, inputLatencyMillis = audio.latencyMillis)
        }
        // reset() overwrote _state with the EMPTY composition's fields; publish
        // the one actually loaded so the monitor names it immediately rather than
        // only after the next edit.
        publishComposition()

        analysisJob = scope.launch(workDispatcher) {
            while (isActive) {
                pumpAnalysis(clock())
                // The source is non-blocking, so poll — well under one feature
                // hop, so no frame ever waits on the poll interval.
                delay(POLL_INTERVAL_MILLIS)
            }
        }
        renderJob = scope.launch(workDispatcher) {
            while (isActive) {
                renderFrame(clock())
                delay(FRAME_INTERVAL_MILLIS)
            }
        }
        // On workDispatcher too: setLayout mutates the renderer's arrays, which
        // the render loop reads. Off the shared single thread it would race them.
        layoutJob = scope.launch(workDispatcher) {
            ledLayout.collect { scene.setLayout(it) }
        }
    }

    /** Stops both loops, closes the microphone and hands the LEDs back to the device. */
    suspend fun stop() {
        // Join, not just cancel: an in-flight iteration keeps running on the work
        // thread until it reaches a suspension point, so closing the source or the
        // output first would tear resources out from under a live pump/render —
        // e.g. `audio.read()` on a stopped source, or the native capture handle
        // freed mid-read. Joining guarantees no loop is running before teardown.
        analysisJob?.cancelAndJoin()
        renderJob?.cancelAndJoin()
        layoutJob?.cancelAndJoin()
        analysisJob = null
        renderJob = null
        layoutJob = null

        source?.stop()
        source = null
        output.close()
        _state.update { it.copy(isRunning = false) }
    }

    /**
     * Drains whatever audio is pending and pushes it through all three tiers.
     *
     * Synchronous and self-contained so tests can step the pipeline by hand.
     */
    internal fun pumpAnalysis(nowNanos: Long) {
        val audio = source ?: return

        // A route change (headset in or out) tears the stream down underneath us;
        // reopening is the only recovery, and it has to happen off the callback.
        if (audio is OboeAudioSource && audio.consumeDisconnected()) {
            Log.i(TAG, "input stream disconnected, restarting")
            runCatching { audio.restart() }
                .onFailure { _state.update { s -> s.copy(error = "Audio input lost: ${it.message}") } }
            return
        }

        while (true) {
            val read = audio.read(readBuffer)
            if (read <= 0) break

            val (samples, count) = resample(read)
            accumulateGenreWindow(samples, count, nowNanos)
            // Before the shared extractor, deliberately: the activation shared
            // frame i pairs with comes from a BeatNet frame produced by this same
            // chunk (see the class doc), so it has to exist by the time the loop
            // below reaches that frame.
            pumpBeatNet(samples, count, nowNanos)

            for (frame in extractor.push(samples, count, nowNanos)) {
                // Every frame, beat or not: this is what puts loudness and the
                // FFT spectrum in front of every effect in the stack.
                scene.onAudioFrame(frame)

                val activation = if (crnnLive) pendingActivations.removeFirstOrNull() else null
                val beats =
                    if (activation != null) beatTracker.process(frame, activation)
                    else beatTracker.process(frame)
                for (beat in beats) {
                    scene.onBeat(beat)
                    latestBeat = beat
                }
                transients.process(frame)?.let { result ->
                    result.drop?.let {
                        scene.onDrop(it)
                        latestDrop = it
                    }
                    scene.onSection(result.section)
                    latestRamp = result.section.ramp
                }
            }
            // A short read means the buffer is drained; anything more would spin.
            if (read < readBuffer.size) break
        }

        publishAnalysisState(audio)
    }

    /** Renders and dispatches one frame. */
    internal fun renderFrame(nowNanos: Long) {
        scene.render(nowNanos)
    }

    /**
     * Runs the BeatNet front-end and the CRNN over the same chunk, queueing one
     * activation per shared frame that is about to be produced.
     *
     * The model is run on *every* BeatNet frame, including the first
     * [beatNetLag] whose activations are then thrown away: those frames are real
     * audio and the LSTM's state has to have seen them, or the session starts
     * with a 60 ms hole in the model's memory. Only the results are discarded,
     * and only to line the two streams up.
     */
    private fun pumpBeatNet(samples: FloatArray, count: Int, nowNanos: Long) {
        val frontEnd = beatNetExtractor ?: return
        val model = crnn ?: return
        if (!model.isAvailable) {
            // Failed mid-session: drop whatever was queued so nothing stale can
            // be paired with a later frame, and leave the DSP path to it.
            pendingActivations.clear()
            return
        }

        for (frame in frontEnd.push(samples, count, nowNanos)) {
            val activation = model.activation(frame)
            if (beatNetActivationsDiscarded < beatNetLag) {
                beatNetActivationsDiscarded++
            } else {
                pendingActivations.addLast(activation)
            }
        }
        // The failure may have happened partway through this chunk, in which case
        // the tail of the queue is SILENT rather than an opinion.
        if (!model.isAvailable) pendingActivations.clear()
    }

    private fun resample(read: Int): Pair<FloatArray, Int> {
        val converter = resampler ?: return readBuffer to read

        // Linear resampling can emit at most ceil(count * ratio) + 1 samples.
        val needed = (read.toLong() * featureConfig.sampleRate / maxOf(1, source?.sampleRate ?: 1)).toInt() + 2
        if (resampleBuffer.size < needed) resampleBuffer = FloatArray(needed)
        val written = converter.resample(readBuffer, read, resampleBuffer)
        return resampleBuffer to written
    }

    /**
     * Fills the context tier's window and classifies once it is full.
     *
     * Runs on the analysis thread: inference of a few tens of milliseconds once
     * every couple of seconds is cheaper than the thread hop, and the beat tier
     * has a whole hop of slack to absorb it.
     */
    private fun accumulateGenreWindow(samples: FloatArray, count: Int, nowNanos: Long) {
        if (genreClassifier === NoGenreClassifier) return

        val windowSize = (genreClassifier.windowSeconds * featureConfig.sampleRate).toInt()
        if (genreWindow.size != windowSize) {
            genreWindow = FloatArray(windowSize)
            genreWindowFill = 0
        }

        var offset = 0
        while (offset < count) {
            val chunk = minOf(count - offset, windowSize - genreWindowFill)
            samples.copyInto(genreWindow, genreWindowFill, offset, offset + chunk)
            genreWindowFill += chunk
            offset += chunk

            if (genreWindowFill == windowSize) {
                genreWindowFill = 0
                runCatching { genreClassifier.classify(genreWindow.copyOf(), nowNanos) }
                    .onFailure { Log.w(TAG, "genre classification failed", it) }
                    .getOrNull()
                    ?.let { prediction ->
                        // Genre no longer selects anything — it is one more input
                        // effects may read, and a label for the monitor.
                        scene.onGenre(prediction)
                        _state.update { it.copy(genre = prediction) }
                    }
            }
        }
    }

    private fun publishAnalysisState(audio: AudioSource) {
        _state.update {
            it.copy(
                bpm = beatTracker.bpm,
                beatConfidence = beatTracker.confidence,
                decoder = decoderKind,
                activationSource = activationKind(),
                lastBeat = latestBeat,
                lastDrop = latestDrop,
                section = transients.sectionState,
                sectionRamp = latestRamp,
                droppedSamples = audio.overrunCount,
                inputLatencyMillis = audio.latencyMillis
            )
        }
    }

    private fun publishComposition() {
        val active = scene.composition
        _state.update {
            it.copy(compositionId = active.id, compositionName = active.name)
        }
    }

    private fun activationKind(): BeatActivationKind =
        if (crnnLive) BeatActivationKind.CRNN else BeatActivationKind.SPECTRAL_FLUX

    private fun reset() {
        extractor.reset()
        // Both halves of the CRNN's memory: the extractor holds the frame its
        // positive difference is taken against, the source holds the LSTM's
        // hidden *and* cell state. Carrying either into a new session starts the
        // model confidently in the wrong place.
        beatNetExtractor?.reset()
        crnn?.reset()
        pendingActivations.clear()
        beatNetActivationsDiscarded = 0
        beatTracker = when (decoderKind) {
            BeatDecoderKind.PHASE_LOCKED -> BeatTracker(featureConfig, octaveBias = octaveBias)
            BeatDecoderKind.PARTICLE_FILTER -> ParticleFilterBeatDecoder(featureConfig, octaveBias = octaveBias)
        }
        beatTracker.reset()
        transients.reset()
        resampler?.reset()
        scene.reset()
        telemetryTracker.reset()
        genreWindowFill = 0
        latestBeat = null
        latestDrop = null
        latestRamp = 0f
        _state.value = BeatLightState(activationSource = activationKind())
    }

    private companion object {
        const val TAG = "LightingEngine"

        /** Roughly a tenth of a second of audio at the analysis rate. */
        const val READ_BUFFER_SAMPLES = 2048

        const val POLL_INTERVAL_MILLIS = 5L

        /** ~30 fps, matching the device's own LED refresh. */
        const val FRAME_INTERVAL_MILLIS = 33L
    }
}
