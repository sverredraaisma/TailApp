package com.tailapp.effects

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tailapp.audio.FftStreamManager
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.composer.TailEnd
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Android-facing wrapper around [LightingEngine]: owns the session
 * lifecycle, the foreground service that keeps it alive, and the errors the UI
 * has to show.
 *
 * Split from the engine so the engine itself stays free of `Context` and can be
 * tested without one — the same split `FftStreamManager` uses for the FF05
 * stream.
 */
class BeatLightSession(
    private val context: Context,
    val engine: LightingEngine,
    private val scope: CoroutineScope,
    private val fftStreamManager: FftStreamManager? = null,
    /**
     * Source of the tail's own telemetry. Optional so the engine and its tests
     * stay independent of BLE; without it, effects that read the body simply
     * see a tail at rest.
     */
    private val deviceRepository: DeviceRepository? = null
) {
    val state: StateFlow<BeatLightState> get() = engine.state

    /**
     * Forwards the tail's taps and motion into the render pipeline for as long
     * as a session is running.
     *
     * Only while running: these feed a `ReactiveContext` that nothing is
     * building otherwise, and collecting FF02 at ~20 Hz to throw it away would
     * be pure battery cost.
     */
    private var telemetryJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)

    /** Set when a session could not be started; cleared by the next attempt. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isActive = MutableStateFlow(false)

    /**
     * Whether a session is meant to be running. Distinct from
     * [LightingEngine.isRunning], which only becomes true once the microphone is
     * actually open — the UI needs to reflect the intent immediately.
     */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var transitionJob: Job? = null

    fun start() {
        if (_isActive.value) return
        _isActive.value = true
        _error.value = null

        // The FF05 visualiser stream and this session both want the microphone,
        // and a second capture generally gets silence rather than an error. They
        // are mutually exclusive anyway: direct mode bypasses the effect stack
        // the FFT stream feeds, so leaving it running would only burn battery
        // sending frames nothing renders.
        val fftWasStreaming = fftStreamManager?.isStreaming?.value == true
        if (fftWasStreaming) {
            Log.i(TAG, "stopping the FF05 stream: it and this session cannot share the mic")
            fftStreamManager?.stop()
        }

        transitionJob?.cancel()
        transitionJob = scope.launch {
            try {
                engine.start()
                // engine.start() reports a non-fatal mic failure (mic busy,
                // capture refused) through its own state.error and returns
                // without running, rather than throwing. That is still a failure
                // here: without this guard the service below would be a
                // notification with nothing behind it, and no error would reach
                // the user.
                if (!engine.isRunning) {
                    fail(engine.state.value.error ?: "Could not start audio capture", fftWasStreaming)
                    return@launch
                }
                // Started only after the engine holds the microphone.
                context.startForegroundService(Intent(context, BeatLightService::class.java))
                startTelemetryForwarding()
            } catch (e: SecurityException) {
                fail("Microphone permission is required", fftWasStreaming, e)
            } catch (e: Exception) {
                fail(e.message ?: e::class.java.simpleName, fftWasStreaming, e)
            }
        }
    }

    fun stop() {
        if (!_isActive.value) return
        _isActive.value = false

        telemetryJob?.cancel()
        telemetryJob = null
        transitionJob?.cancel()
        transitionJob = scope.launch {
            // Stopping the engine hands the LEDs back to the device's own effect
            // stack; leaving direct mode on would freeze the tail on the last
            // frame we rendered.
            engine.stop()
            context.stopService(Intent(context, BeatLightService::class.java))
        }
    }

    fun toggle() {
        if (_isActive.value) stop() else start()
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Subscribes the tail's own state into the effect pipeline: FF07 taps
     * become tap events, and the FF02 motion state becomes deflection and wag
     * speed. This is what lets an effect react to the device's body rather than
     * only to the microphone.
     */
    private fun startTelemetryForwarding() {
        val repository = deviceRepository ?: return
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            launch {
                repository.systemEvents.collect { event ->
                    when (event) {
                        SystemEvent.TAP_BASE -> engine.onTailTap(TailEnd.BASE)
                        SystemEvent.TAP_TIP -> engine.onTailTap(TailEnd.TIP)
                        // Config reloads and stalls say nothing about where the
                        // tail is; they are the overview screen's business.
                        else -> Unit
                    }
                }
            }
            launch {
                repository.deviceState
                    .map { it.motionState }
                    .filterNotNull()
                    // FF02 notifies at ~20 Hz whether or not anything moved.
                    .distinctUntilChanged()
                    .collect { engine.onTailMotion(it) }
            }
        }
    }

    private suspend fun fail(message: String, restoreFftStream: Boolean, cause: Exception? = null) {
        if (cause != null) Log.e(TAG, "could not start the lighting session", cause)
        else Log.e(TAG, "could not start the lighting session: $message")
        _error.value = message
        _isActive.value = false
        runCatching { engine.stop() }
        // The mic this session pre-emptively freed the FF05 stream for is free
        // again now the session did not take it, so put the visualiser back
        // rather than leaving the user with neither.
        if (restoreFftStream) runCatching { fftStreamManager?.start() }
    }

    private companion object {
        const val TAG = "BeatLightSession"
    }
}
