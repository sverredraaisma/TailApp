package com.tailapp.effects

import android.util.Log
import com.tailapp.audio.AudioSource
import com.tailapp.audio.AudioSources
import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FeatureExtractor
import com.tailapp.audio.OboeAudioSource
import com.tailapp.audio.dsp.Resampler
import com.tailapp.beat.BeatDecoder
import com.tailapp.beat.BeatEvent
import com.tailapp.beat.BeatTracker
import com.tailapp.beat.ParticleFilterBeatDecoder
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
import kotlinx.coroutines.Job
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
    val profileId: String = EffectProfiles.DEFAULT.id,
    val profileName: String = EffectProfiles.DEFAULT.displayName,
    val isProfileOverridden: Boolean = false,
    val inputLatencyMillis: Float = 0f,
    val droppedSamples: Long = 0L,
    val decoder: BeatDecoderKind = BeatDecoderKind.PHASE_LOCKED,
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
 * @param output where frames go — typically the tail and the on-screen preview.
 * @param ledLayout the device's ring configuration, followed live.
 * @param scope lifetime of the loops.
 * @param genreClassifier context tier; inert until the ONNX model lands.
 * @param workDispatcher where the two loops run. Defaults to [Dispatchers.Default]
 *   because neither loop may ever touch the main thread; tests substitute their
 *   own so the loops cannot race a hand-driven pipeline.
 * @param audioSourceFactory injection seam for tests.
 * @param clock injection seam for tests.
 */
class LightingEngine(
    private val output: LightingOutput,
    private val ledLayout: StateFlow<List<Int>>,
    private val scope: CoroutineScope,
    private val featureConfig: FeatureConfig = FeatureConfig(),
    private val genreClassifier: GenreClassifier = NoGenreClassifier,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val audioSourceFactory: (Int) -> AudioSource = { rate -> AudioSources.create(rate) },
    private val clock: () -> Long = System::nanoTime
) {
    private val extractor = FeatureExtractor(featureConfig)

    /**
     * Rebuilt rather than swapped: the analysis loop reads this on a worker
     * thread, so changing decoders while one is running would be a data race on
     * whatever internal state it carries. [decoderKind] therefore takes effect
     * at the next [start], and the session UI restarts the session to apply it.
     */
    private var beatTracker: BeatDecoder = BeatTracker(featureConfig)

    /** Decoder used by the *next* session. Changing it does not disturb a running one. */
    var decoderKind: BeatDecoderKind = BeatDecoderKind.PHASE_LOCKED
    private val transients = TransientAnalyzer(featureConfig = featureConfig)
    private val renderer = ReactiveRenderer()
    private val controller = EffectController(renderer, output)

    private val _state = MutableStateFlow(BeatLightState())
    val state: StateFlow<BeatLightState> = _state.asStateFlow()

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
        get() = controller.triggerOffsetMillis
        set(value) {
            controller.triggerOffsetMillis = value
        }

    /** Pins a profile regardless of what the classifier says; null returns to automatic. */
    var manualProfile: EffectProfile?
        get() = controller.manualProfile
        set(value) {
            controller.manualProfile = value
            publishProfile()
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

        controller.setLayout(ledLayout.value)
        output.open(renderer.ledCount)

        _state.update {
            it.copy(isRunning = true, error = null, inputLatencyMillis = audio.latencyMillis)
        }

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
        layoutJob = scope.launch {
            ledLayout.collect { controller.setLayout(it) }
        }
    }

    /** Stops both loops, closes the microphone and hands the LEDs back to the device. */
    suspend fun stop() {
        analysisJob?.cancel()
        renderJob?.cancel()
        layoutJob?.cancel()
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

            for (frame in extractor.push(samples, count, nowNanos)) {
                for (beat in beatTracker.process(frame)) {
                    controller.onBeat(beat)
                    latestBeat = beat
                }
                transients.process(frame)?.let { result ->
                    result.drop?.let {
                        controller.onDrop(it)
                        latestDrop = it
                    }
                    controller.onSection(result.section)
                    latestRamp = result.section.ramp
                }
            }
            // A short read means the buffer is drained; anything more would spin.
            if (read < readBuffer.size) break
        }

        publishAnalysisState(audio)
    }

    /** Renders and dispatches one frame. */
    internal fun renderFrame(nowNanos: Long) = controller.renderFrame(nowNanos)

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
                        controller.onGenre(prediction)
                        _state.update { it.copy(genre = prediction) }
                        publishProfile()
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
                lastBeat = latestBeat,
                lastDrop = latestDrop,
                section = transients.sectionState,
                sectionRamp = latestRamp,
                droppedSamples = audio.overrunCount,
                inputLatencyMillis = audio.latencyMillis
            )
        }
    }

    private fun publishProfile() {
        val profile = controller.activeProfile
        _state.update {
            it.copy(
                profileId = profile.id,
                profileName = profile.displayName,
                isProfileOverridden = controller.isOverridden
            )
        }
    }

    private fun reset() {
        extractor.reset()
        beatTracker = when (decoderKind) {
            BeatDecoderKind.PHASE_LOCKED -> BeatTracker(featureConfig)
            BeatDecoderKind.PARTICLE_FILTER -> ParticleFilterBeatDecoder(featureConfig)
        }
        beatTracker.reset()
        transients.reset()
        resampler?.reset()
        controller.reset()
        genreWindowFill = 0
        latestBeat = null
        latestDrop = null
        latestRamp = 0f
        _state.value = BeatLightState()
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
