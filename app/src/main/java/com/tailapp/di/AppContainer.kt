package com.tailapp.di

import android.content.Context
import android.content.SharedPreferences
import com.tailapp.audio.FftStreamManager
import com.tailapp.ble.BleConnectionManager
import com.tailapp.ble.BleScanner
import com.tailapp.effects.BeatLightSession
import com.tailapp.effects.LightingEngine
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

    val lightingEngine = LightingEngine(
        output = lightingOutput,
        ledLayout = ledLayout,
        scope = applicationScope
    )

    val beatLightSession = BeatLightSession(context, lightingEngine, applicationScope)

    val beatLightPrefs: SharedPreferences =
        context.getSharedPreferences("beatlight_config", Context.MODE_PRIVATE)
}
