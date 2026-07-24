package com.tailapp.effects

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tailapp.audio.FftStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val fftStreamManager: FftStreamManager? = null
) {
    val state: StateFlow<BeatLightState> get() = engine.state

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
