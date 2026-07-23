package com.tailapp.effects

import android.content.Context
import android.content.Intent
import android.util.Log
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
    private val scope: CoroutineScope
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

        transitionJob?.cancel()
        transitionJob = scope.launch {
            try {
                engine.start()
                // Started only after the engine holds the microphone: a service
                // whose session failed to start is a notification with nothing
                // behind it.
                context.startForegroundService(Intent(context, BeatLightService::class.java))
            } catch (e: SecurityException) {
                fail("Microphone permission is required", e)
            } catch (e: Exception) {
                fail(e.message ?: e::class.java.simpleName, e)
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

    private suspend fun fail(message: String, cause: Exception) {
        Log.e(TAG, "could not start the lighting session", cause)
        _error.value = message
        _isActive.value = false
        runCatching { engine.stop() }
    }

    private companion object {
        const val TAG = "BeatLightSession"
    }
}
