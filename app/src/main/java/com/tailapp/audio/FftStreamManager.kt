package com.tailapp.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FftStreamManager(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val scope: CoroutineScope
) {
    private val audioCaptureManager = AudioCaptureManager()
    val fftProcessor = FftProcessor()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** Set when [start] could not open the microphone; cleared on the next attempt. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    fun start() {
        if (_isStreaming.value) return
        _error.value = null

        // Opening the mic can fail (permission revoked, mic in use). Report it
        // instead of leaving the UI showing a stream that never started.
        try {
            audioCaptureManager.start()
        } catch (e: Exception) {
            Log.e(TAG, "start: could not open microphone", e)
            _error.value = "Could not start audio capture: ${e.message ?: e::class.java.simpleName}"
            return
        }

        fftProcessor.reset()
        _isStreaming.value = true
        deviceRepository.setFftStreamActive(true)

        val samplesPerFrame = AudioCaptureManager.SAMPLE_RATE / FRAMES_PER_SECOND
        val buffer = ShortArray(samplesPerFrame)

        streamJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val read = audioCaptureManager.readFrame(buffer)
                    if (read > 0) {
                        val samples = if (read == buffer.size) buffer else buffer.copyOf(read)
                        val result = fftProcessor.process(samples, AudioCaptureManager.SAMPLE_RATE)
                        deviceRepository.sendFftFrame(result.loudness, result.bins)
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read returned $read, stopping capture")
                        break
                    }
                }
            } finally {
                // Ensure AudioRecord is released on the same dispatcher where read was happening
                audioCaptureManager.stop()
            }
        }

        context.startForegroundService(Intent(context, AudioStreamService::class.java))
    }

    fun stop() {
        if (!_isStreaming.value) return
        _isStreaming.value = false
        deviceRepository.setFftStreamActive(false)
        streamJob?.cancel()
        streamJob = null
        // AudioRecord cleanup happens in the job's finally block
        context.stopService(Intent(context, AudioStreamService::class.java))
    }

    fun toggle() {
        if (_isStreaming.value) stop() else start()
    }

    fun clearError() {
        _error.value = null
    }

    private companion object {
        const val TAG = "FftStreamManager"
        const val FRAMES_PER_SECOND = 30
    }
}
