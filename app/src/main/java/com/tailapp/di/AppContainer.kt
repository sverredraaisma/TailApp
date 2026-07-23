package com.tailapp.di

import android.content.Context
import android.content.SharedPreferences
import com.tailapp.audio.FeatureConfig
import com.tailapp.audio.FftStreamManager
import com.tailapp.beat.ActivationSource
import com.tailapp.beat.BeatModelStore
import com.tailapp.beat.CrnnActivationSource
import com.tailapp.beat.SpectralFluxActivationSource
import com.tailapp.ble.BleConnectionManager
import com.tailapp.ble.BleScanner
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
    val deviceRepository = DeviceRepository(bleConnectionManager, applicationScope)
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
     * The neural activation function when its model is on disk *and* the
     * front-end is the one it was trained on, the DSP one otherwise.
     *
     * On the shipped [FeatureConfig] the second condition does not hold — our
     * frames are 205 bands from a 2048-sample window, BeatNet's are 136 from a
     * 1411-sample one — so this is [SpectralFluxActivationSource] today and
     * `CrnnActivationSource.create` says why in logcat. `docs/beat-model.md`
     * has the measurements.
     *
     * **Not yet reachable.** `LightingEngine` constructs `BeatTracker(featureConfig)`
     * and takes no activation source, so wiring this in needs one parameter added
     * there — deliberately not done here, because `effects/` is not this change's
     * to touch. Until then this is the selection, ready and tested, one line from
     * being used.
     */
    val beatActivationSource: ActivationSource =
        CrnnActivationSource.create(beatModelStore, FeatureConfig())
            ?: SpectralFluxActivationSource(FeatureConfig())

    val lightingEngine = LightingEngine(
        output = lightingOutput,
        ledLayout = ledLayout,
        scope = applicationScope,
        genreClassifier = genreClassifier
    )

    /**
     * The FFT stream is handed in so a starting session can stop it: both want
     * the microphone, and a second capture generally gets silence rather than an
     * error.
     */
    val beatLightSession =
        BeatLightSession(context, lightingEngine, applicationScope, fftStreamManager)

    val beatLightPrefs: SharedPreferences =
        context.getSharedPreferences("beatlight_config", Context.MODE_PRIVATE)
}
