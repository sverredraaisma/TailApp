@file:OptIn(ExperimentalCoroutinesApi::class)

package com.tailapp.effects

import android.util.Log
import com.tailapp.audio.AudioSource
import com.tailapp.audio.AudioSources
import com.tailapp.audio.BeatNetFeatureExtractor
import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureFrameFftEncoder
import com.tailapp.audio.FftSettings
import com.tailapp.ble.protocol.FftFrameBuilder
import com.tailapp.audio.FeatureExtractor
import com.tailapp.audio.FeatureFrame
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
import com.tailapp.composer.MotionChoreography
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
/**
 * The seam through which a session feeds the device's own audio effects.
 *
 * Narrow on purpose: [LightingEngine] should not know what BLE is, and this
 * keeps the engine testable with a recording stand-in.
 * [com.tailapp.repository.DeviceRepository] is the real implementation.
 */
interface DeviceAudioStream {
    fun sendFftFrameWithBeat(
        loudness: Byte,
        bins: ByteArray,
        beatPhase: Float,
        bpm: Float,
        flags: Int
    )
}

/**
 * The seam through which a session drives the tail's motors.
 *
 * Separate from [DeviceAudioStream] because they are genuinely different
 * features: a user may want the lights to react without the tail moving, and
 * the motion stream is the one that can physically hurt if it misbehaves.
 */
interface DeviceMotionStream {
    fun streamMotionTargets(targets: FloatArray)
}

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
    /**
     * Where genre inference runs. **Not** [workDispatcher], deliberately: an
     * EffNet-class forward pass is 50-150 ms on a mid-range phone, and running it
     * inline on the single-threaded work dispatcher blocked the render loop for
     * two to five frames every window — a visible, periodic hitch. It is already
     * a pure function of a copied window, so it is trivially separable; the
     * result is published back through [scope] on [workDispatcher], since the
     * scene's genre field is otherwise only touched from there.
     *
     * Its own `limitedParallelism(1)` rather than the bare pool: two overlapping
     * inferences on one ONNX session are pointless contention, and the classifier
     * serialises them anyway.
     */
    private val genreDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val audioSourceFactory: (Int) -> AudioSource = { rate -> AudioSources.create(rate) },
    private val clock: () -> Long = System::nanoTime,
    /**
     * Where the device's own FF05 audio frames go. Optional so the engine and
     * its tests stay independent of BLE; without it the session simply does not
     * feed the device's built-in effects.
     */
    private val deviceStream: DeviceAudioStream? = null,
    /**
     * Where live motion targets go. Optional and **off by default**: moving the
     * motors is opt-in, because a stack that only changes colour should never
     * start the tail swinging on its own.
     */
    private val motionStream: DeviceMotionStream? = null
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

    /**
     * Derives the device's FF05 frame from the same analysis this session
     * already runs, so the device's own audio effects work while a session is
     * live instead of being starved of the microphone by it.
     */
    private val fftEncoder = FeatureFrameFftEncoder(featureConfig)

    /**
     * Turns the analysis into tail movement. Null config means "do not move the
     * tail", which is the default: lighting and motion are separate features,
     * and one should not silently imply the other.
     */
    private val choreography = MotionChoreography()

    /** Set to start driving the motors from the analysis; null stops it. */
    @Volatile
    var motionChoreography: MotionChoreography.Config? = null

    // Motion targets stream at the render rate; the device ages them out after
    // 500 ms, so this has to stay comfortably faster than that.
    private var motionFrameCounter = 0

    /** How the FF05 frame is built; mirrors the Audio Config screen. */
    var fftSettings: FftSettings
        get() = fftEncoder.settings
        set(value) { fftEncoder.settings = value }

    // Analysis runs at ~50 fps; the device's staleness window and render rate
    // are built for ~30, so forward roughly every other frame.
    private val streamDecimation: Int =
        (featureConfig.framesPerSecond / DEVICE_STREAM_FPS).toInt().coerceAtLeast(1)
    private var streamFrameCounter = 0
    private var pendingStreamBeat = false
    private var pendingStreamDrop = false

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

    /**
     * Rolling window of analysis-rate audio for the context tier, plus the buffer
     * the in-flight classification is reading.
     *
     * Double-buffered rather than copied: the window is 66150 floats and handing
     * it to another thread means the analysis loop must not overwrite it, but
     * allocating a quarter-megabyte copy every window to say so is a waste when
     * two buffers swap for free.
     */
    private var genreWindow = FloatArray(0)
    private var genreSpare = FloatArray(0)
    private var genreWindowFill = 0

    /** Volatile: written by the analysis loop, read by [stop] on another thread. */
    @Volatile
    private var genreJob: Job? = null

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
        // One write, so a decoder reading it mid-frame cannot see a new target
        // against an old strength.
        octaveBias.set(enabled, targetBpm, strength)
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

    /**
     * Serialises [start] against [stop] and against itself.
     *
     * The guard used to be `isRunning`, which is derived from the analysis job —
     * and the jobs are not launched until *after* the suspending microphone open.
     * Two overlapping starts therefore both passed it, both opened an
     * `AudioSource`, and the first was overwritten and never stopped: a leaked
     * microphone, plus two analysis loops sharing one [FeatureExtractor], which
     * is exactly the corruption `limitedParallelism(1)` exists to prevent.
     */
    private val lifecycle = Mutex()

    val isRunning: Boolean get() = analysisJob?.isActive == true

    /**
     * Opens the microphone and starts both loops.
     *
     * @throws SecurityException when `RECORD_AUDIO` has not been granted — the
     *   caller is the one that can ask for it.
     */
    suspend fun start() = lifecycle.withLock { startLocked() }

    private suspend fun startLocked() {
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
    suspend fun stop() = lifecycle.withLock { stopLocked() }

    private suspend fun stopLocked() {
        // Join, not just cancel: an in-flight iteration keeps running on the work
        // thread until it reaches a suspension point, so closing the source or the
        // output first would tear resources out from under a live pump/render —
        // e.g. `audio.read()` on a stopped source, or the native capture handle
        // freed mid-read. Joining guarantees no loop is running before teardown.
        analysisJob?.cancelAndJoin()
        renderJob?.cancelAndJoin()
        layoutJob?.cancelAndJoin()
        // The genre job holds a window buffer and may be mid-inference; joining it
        // is what makes the close() below safe to issue.
        genreJob?.cancelAndJoin()
        analysisJob = null
        renderJob = null
        layoutJob = null
        genreJob = null

        source?.stop()
        source = null
        // Both ONNX graphs are large — EffNet brings its own arena — and neither
        // had a production caller for close(), so a single session's models were
        // retained for the rest of the process's life. Both reload lazily on the
        // next session, so releasing here costs one model load per session and
        // nothing while one is not running.
        runCatching { genreClassifier.close() }
            .onFailure { Log.w(TAG, "could not release the genre models", it) }
        runCatching { crnn?.close() }
            .onFailure { Log.w(TAG, "could not release the beat model", it) }
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

        // Capped, not drained to exhaustion. The loop below has no suspension
        // point and only exits on a short read, so a backlog — after a restart, a
        // route change, or the work dispatcher having been busy — could hold the
        // shared single thread for as long as it took to catch up, starving
        // renderFrame. Past two seconds of that the device ages the FF0A pixel
        // stream out and reverts to its own rendering mid-session. The poll
        // interval is 5 ms and each read is ~93 ms of audio, so the cap still
        // drains far faster than real time; the remainder waits for the next pump.
        var reads = 0
        while (reads < MAX_READS_PER_PUMP) {
            reads++
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
                var dropThisFrame = false
                transients.process(frame)?.let { result ->
                    result.drop?.let {
                        scene.onDrop(it)
                        latestDrop = it
                        dropThisFrame = true
                    }
                    scene.onSection(result.section)
                    latestRamp = result.section.ramp
                }

                forwardToDeviceStream(frame, beats.isNotEmpty(), dropThisFrame)
            }
            // A short read means the buffer is drained; anything more would spin.
            if (read < readBuffer.size) break
        }

        publishAnalysisState(audio)
    }

    /** Renders and dispatches one frame. */
    internal fun renderFrame(nowNanos: Long) {
        scene.render(nowNanos)
        streamMotion(nowNanos)
    }

    /**
     * Drives the tail's motors from the same analysis that just drew the frame.
     *
     * Only while [motionChoreography] is set: lighting and motion are separate
     * features, and a stack that only changes colour must never start the tail
     * swinging on its own.
     *
     * Streamed at the full render rate rather than decimated — the device ages
     * targets out after 500 ms, and unlike the pixel stream this is small
     * (16 bytes) and a live control surface, so latency matters more than the
     * radio time saved.
     */
    private fun streamMotion(nowNanos: Long) {
        val sink = motionStream ?: return
        val config = motionChoreography ?: return

        choreography.config = config
        sink.streamMotionTargets(choreography.targetsFor(scene.buildContext(nowNanos, 0f)))
        motionFrameCounter++
    }

    /** Frames of motion streamed this session; surfaced for diagnostics. */
    val motionFramesStreamed: Int get() = motionFrameCounter

    /**
     * Sends the device its own FF05 audio frame, derived from this analysis
     * frame rather than from a second microphone capture.
     *
     * This is what lets the device's built-in effects run *while* a BeatLight
     * session does: the two used to fight over the mic, and the session won by
     * stopping the FF05 stream outright. Nothing here opens a capture, so there
     * is nothing left to conflict.
     *
     * Decimated to roughly 30 fps because that is what the firmware's staleness
     * window and render rate are built around; forwarding all 50 analysis
     * frames a second would just burn radio time.
     */
    private fun forwardToDeviceStream(frame: FeatureFrame, onBeat: Boolean, onDrop: Boolean) {
        val sink = deviceStream ?: return

        // Latch the events so a beat landing on a skipped frame is still
        // reported on the next one that goes out, rather than being lost to
        // decimation.
        if (onBeat) pendingStreamBeat = true
        if (onDrop) pendingStreamDrop = true

        streamFrameCounter++
        if (streamFrameCounter < streamDecimation) return
        streamFrameCounter = 0

        val encoded = fftEncoder.encode(frame)
        val beat = latestBeat
        val bpm = beat?.bpm ?: 0f
        val phase = if (beat != null && bpm > 0f) {
            val since = (frame.timestampNanos - beat.timestampNanos) / 1_000_000_000f
            if (since < 0f) 0f else (since / (60f / bpm)) % 1f
        } else {
            0f
        }

        var flags = 0
        if (pendingStreamBeat) {
            flags = flags or FftFrameBuilder.FLAG_BEAT
            if (beat?.isDownbeat == true) flags = flags or FftFrameBuilder.FLAG_DOWNBEAT
        }
        if (pendingStreamDrop) flags = flags or FftFrameBuilder.FLAG_DROP
        pendingStreamBeat = false
        pendingStreamDrop = false

        sink.sendFftFrameWithBeat(encoded.loudness, encoded.bins, phase, bpm, flags)
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
     * Fills the context tier's window and hands it off once it is full.
     *
     * The filling runs on the analysis thread; the inference does not. It used to
     * — on the theory that a few tens of milliseconds every couple of seconds was
     * cheaper than a thread hop — but the analysis and render loops share one
     * single-threaded dispatcher on purpose, so those tens of milliseconds came
     * straight out of the render loop as a periodic run of dropped frames. See
     * [genreDispatcher].
     */
    private fun accumulateGenreWindow(samples: FloatArray, count: Int, nowNanos: Long) {
        if (genreClassifier === NoGenreClassifier) return

        val windowSize = (genreClassifier.windowSeconds * featureConfig.sampleRate).toInt()
        if (genreWindow.size != windowSize) {
            genreWindow = FloatArray(windowSize)
            genreSpare = FloatArray(windowSize)
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
                classifyGenre(nowNanos)
            }
        }
    }

    /**
     * Hands the finished window to the classifier, off the analysis thread.
     *
     * A window is skipped rather than queued when the previous inference has not
     * finished: the whole point of a genre is that it is slowly varying, so the
     * useful thing to do when the model cannot keep up is to classify less often,
     * not to build a backlog whose answers describe audio that has already gone.
     * (It should never happen — inference is a tenth of the window — but "never
     * happens" is how a buffer ends up being read while it is written.)
     */
    private fun classifyGenre(nowNanos: Long) {
        if (genreJob?.isActive == true) return

        val window = genreWindow
        genreWindow = genreSpare
        genreSpare = window

        genreJob = scope.launch(genreDispatcher) {
            val prediction = runCatching { genreClassifier.classify(window, nowNanos) }
                .onFailure { Log.w(TAG, "genre classification failed", it) }
                .getOrNull() ?: return@launch

            // Genre no longer selects anything — it is one more input effects may
            // read, and a label for the monitor. The scene's genre field is
            // written from the work dispatcher and nowhere else, so hop back
            // rather than publishing it from here.
            withContext(workDispatcher) { scene.onGenre(prediction) }
            _state.update { it.copy(genre = prediction) }
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
        fftEncoder.reset()
        streamFrameCounter = 0
        motionFrameCounter = 0
        pendingStreamBeat = false
        pendingStreamDrop = false
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

        /**
         * Full-buffer reads one pump may consume before yielding the shared
         * dispatcher back. Four is ~370 ms of audio against a 5 ms poll, so this
         * never throttles a healthy stream and bounds the worst case to something
         * the render loop can absorb.
         */
        const val MAX_READS_PER_PUMP = 4

        /** ~30 fps, matching the device's own LED refresh. */
        const val FRAME_INTERVAL_MILLIS = 33L

        /**
         * Rate at which FF05 audio frames are forwarded to the device. Its
         * staleness window and render loop are built for this; sending all ~50
         * analysis frames a second would only burn radio time.
         */
        const val DEVICE_STREAM_FPS = 30f
    }
}
