package com.tailapp.audio

import android.content.Context
import android.content.Intent
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

    private var streamJob: Job? = null

    fun start() {
        if (_isStreaming.value) return
        _isStreaming.value = true
        deviceRepository.setFftStreamActive(true)

        audioCaptureManager.start()

        val samplesPerFrame = AudioCaptureManager.SAMPLE_RATE / 30
        val buffer = ShortArray(samplesPerFrame)

        streamJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val read = audioCaptureManager.readFrame(buffer)
                    if (read > 0) {
                        val samples = if (read == buffer.size) buffer else buffer.copyOf(read)
                        val result = fftProcessor.process(samples, AudioCaptureManager.SAMPLE_RATE)
                        deviceRepository.sendFftFrame(result.loudness, result.bins)
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
}
