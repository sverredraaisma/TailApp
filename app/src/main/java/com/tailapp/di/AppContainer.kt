package com.tailapp.di

import android.content.Context
import android.content.SharedPreferences
import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FftStreamManager
import com.tailapp.beat.BeatModelStore
import com.tailapp.ble.BleConnectionManager
import com.tailapp.ble.BleScanner
import com.tailapp.ble.RoutingBleTransport
import com.tailapp.ble.VirtualTailTransport
import com.tailapp.composer.CompositionLibrary
import com.tailapp.effects.BeatLightSession
import com.tailapp.effects.LightingEngine
import com.tailapp.genre.GenreClassifier
import com.tailapp.genre.GenreModelStore
import com.tailapp.genre.NoGenreClassifier
import com.tailapp.genre.OnnxGenreClassifier
import com.tailapp.lighting.CompositeLightingOutput
import com.tailapp.lighting.PreviewLightingOutput
import com.tailapp.lighting.TailDirectLedOutput
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val bleScanner = BleScanner(context)
    val bleConnectionManager = BleConnectionManager(context)

    /**
     * The repository talks to one transport; the router picks the real radio or
     * the in-app [VirtualTailTransport] per connection, from the address. The
     * virtual tail is a testing aid — it lets the previews and the whole
     * analysis pipeline run with no device present.
     */
    private val bleTransport = RoutingBleTransport(
        real = bleConnectionManager,
        virtual = VirtualTailTransport(),
        scope = applicationScope
    )
    val deviceRepository = DeviceRepository(bleTransport, applicationScope)
    val fftStreamManager = FftStreamManager(context, deviceRepository, applicationScope)
    val audioPrefs: SharedPreferences = context.getSharedPreferences("audio_config", Context.MODE_PRIVATE)

    // --- BeatLight: beat/drop-reactive lighting ---

    /** Rendered frames, mirrored for the monitoring screen. */
    val lightingPreview = PreviewLightingOutput()

    /**
     * The device's ring layout, followed live so a matrix change reaches the
     * renderer without restarting the session.
     */
    private val ledLayout: StateFlow<List<Int>> = deviceRepository.deviceState
        .map { it.ledState?.ledsPerRing ?: emptyList() }
        .stateIn(applicationScope, SharingStarted.Eagerly, emptyList())

    /**
     * The tail first, the preview second: the hardware should not wait behind UI
     * work for its frame.
     */
    private val lightingOutput = CompositeLightingOutput(
        TailDirectLedOutput(deviceRepository),
        lightingPreview
    )

    /**
     * Where the Discogs-EffNet artifacts live once installed. They are not in the
     * APK — the weights are CC BY-NC-ND, see `docs/genre-model.md`.
     */
    val genreModelStore = GenreModelStore(File(context.filesDir, GenreModelStore.DIRECTORY_NAME))

    /**
     * The real classifier when its models are on disk, the inert stand-in
     * otherwise. `LightingEngine` sizes the context-tier window from its own
     * `FeatureConfig.sampleRate`, so that is the rate the classifier is told to
     * expect; it resamples to the model's 16 kHz itself.
     */
    private val genreClassifier: GenreClassifier =
        OnnxGenreClassifier.create(genreModelStore, inputSampleRate = FeatureConfig().sampleRate)
            ?: NoGenreClassifier

    /**
     * Where the BeatNet CRNN lives once installed. Like the genre models it is
     * not in the APK, but for a different reason: BeatNet is CC BY 4.0, so the
     * weights *could* ship — the `.onnx` simply does not exist upstream, because
     * `tools/export_beatnet.py` rewraps the model so its LSTM state crosses the
     * graph boundary. See `docs/beat-model.md`.
     */
    val beatModelStore = BeatModelStore(File(context.filesDir, BeatModelStore.DIRECTORY_NAME))

    /**
     * The store is handed to the engine rather than a resolved activation source:
     * running the CRNN means running a *second front-end* alongside the shared
     * one ([com.tailapp.audio.BeatNetFeatureExtractor]), and only the engine is
     * in a position to drive both off one audio stream and pair their frames.
     * With no model installed — or the CRNN disabled below — nothing is
     * constructed and the DSP activation runs exactly as before.
     */
    val lightingEngine = LightingEngine(
        output = lightingOutput,
        ledLayout = ledLayout,
        scope = applicationScope,
        genreClassifier = genreClassifier,
        beatModelStore = beatModelStore.takeIf { USE_CRNN_BEAT_ACTIVATION }
    )

    /**
     * The FFT stream is handed in so a starting session can stop it: both want
     * the microphone, and a second capture generally gets silence rather than an
     * error. The repository comes in so the tail's own taps and motion reach the
     * effect pipeline — without it, effects can only react to the microphone.
     */
    val beatLightSession = BeatLightSession(
        context = context,
        engine = lightingEngine,
        scope = applicationScope,
        fftStreamManager = fftStreamManager,
        deviceRepository = deviceRepository
    )

    val beatLightPrefs: SharedPreferences =
        context.getSharedPreferences("beatlight_config", Context.MODE_PRIVATE)

    /**
     * The user's effect stacks, and which one is active.
     *
     * One instance for the whole app on purpose: the BeatLight screen selects a
     * stack and the composer edits it, and they have to be looking at the same
     * list. Its own preferences file, separate from `beatlight_config`, because
     * saved compositions are user content rather than calibration.
     */
    val compositionLibrary = CompositionLibrary(
        context.getSharedPreferences("composer_config", Context.MODE_PRIVATE)
    )

    init {
        // The engine renders black until it is given a tree. The view models set
        // this too, but only once their screen is opened — without this a session
        // started from anywhere else would light nothing.
        lightingEngine.composition = compositionLibrary.active()
    }

    private companion object {
        /**
         * Whether the installed BeatNet CRNN drives the beat activation. **Off**,
         * deliberately: measured on a real phone mic (quiet, reverberant,
         * out-of-distribution for a model trained on produced tracks) the CRNN's
         * beat activation is weak and *temporally smeared* — beats only ~2x the
         * baseline and spread across many frames — so the decoders never lock
         * cleanly and the tempo drifts. No amount of amplitude normalisation
         * sharpens a signal that is not sharp in time. The DSP spectral-flux
         * activation is z-scored onset detection, robust to a low, noisy level,
         * and gives a stable, correct BPM on exactly this input (verified end to
         * end on mic-like audio). The CRNN stays installed, wired and tested for
         * a cleaner source — line-in, or a mic-trained model — where its output
         * is worth having; flip this to true there.
         */
        const val USE_CRNN_BEAT_ACTIVATION = false
    }
}
