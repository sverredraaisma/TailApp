package com.tailapp.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

    /**
     * The most recent frame computed while streaming - additive to the FF05
     * send path below, for local consumers (the LED preview) that want the
     * same loudness/bins without re-deriving them from raw PCM. Cleared on
     * [stop] so a consumer that only forwards frames while this is non-null
     * naturally stops as soon as the mic does, rather than replaying a stale
     * frame from the last session.
     */
    private val _latestResult = MutableStateFlow<FftResult?>(null)
    val latestResult: StateFlow<FftResult?> = _latestResult.asStateFlow()

    /** Set when [start] could not open the microphone; cleared on the next attempt. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Only ever touched from inside a lifecycle step, which is serialised. */
    private var streamJob: Job? = null

    /**
     * Tail of the start/stop chain. Every lifecycle step joins its predecessor
     * before running, so a stop→start double tap can never interleave: without
     * that, `stop()`'s cancellation was asynchronous and the old job's teardown
     * ran *after* the new `start()` had opened its recorder — releasing the new
     * one, leaving the loop reading 0 forever with `isStreaming` still true.
     */
    private var lifecycleJob: Job? = null

    fun start() {
        if (_isStreaming.value) return
        _error.value = null
        // Optimistic, like the command path: rolled back below if the mic or the
        // service refuses, so `toggle()` cannot double-start in the meantime.
        _isStreaming.value = true
        enqueue { doStart() }
    }

    fun stop() {
        if (!_isStreaming.value) return
        _isStreaming.value = false
        _latestResult.value = null
        enqueue { doStop() }
    }

    fun toggle() {
        if (_isStreaming.value) stop() else start()
    }

    fun clearError() {
        _error.value = null
    }

    @Synchronized
    private fun enqueue(step: suspend () -> Unit) {
        val previous = lifecycleJob
        lifecycleJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            step()
        }
    }

    private suspend fun doStart() {
        // Opening the mic can fail (permission revoked, mic in use). Report it
        // instead of leaving the UI showing a stream that never started.
        try {
            audioCaptureManager.start()
        } catch (e: Exception) {
            Log.e(TAG, "start: could not open microphone", e)
            fail("Could not start audio capture: ${e.message ?: e::class.java.simpleName}")
            return
        }

        // API 31+ throws ForegroundServiceStartNotAllowedException from the
        // background. Unwrapped, that left the mic open and isStreaming true
        // with nothing keeping the process alive.
        val service = runCatching {
            context.startForegroundService(Intent(context, AudioStreamService::class.java))
        }
        if (service.isFailure) {
            val e = service.exceptionOrNull()
            Log.e(TAG, "start: could not start the foreground service", e)
            audioCaptureManager.stop()
            fail("Could not start audio service: ${e?.message ?: "unknown error"}")
            return
        }

        fftProcessor.reset()
        deviceRepository.setFftStreamActive(true)

        val samplesPerFrame = AudioCaptureManager.SAMPLE_RATE / FRAMES_PER_SECOND
        val buffer = ShortArray(samplesPerFrame)

        streamJob = scope.launch(Dispatchers.IO) {
            var idleReads = 0
            try {
                while (isActive) {
                    val read = audioCaptureManager.readFrame(buffer)
                    if (read > 0) {
                        idleReads = 0
                        val samples = if (read == buffer.size) buffer else buffer.copyOf(read)
                        val result =
                            fftProcessor.process(samples, AudioCaptureManager.SAMPLE_RATE)
                        _latestResult.value = result
                        deviceRepository.sendFftFrame(result.loudness, result.bins)
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read returned $read, stopping capture")
                        break
                    } else {
                        // Zero is neither data nor an error, and a blocking read
                        // only returns it when the recorder is gone. Backing off
                        // rather than spinning keeps a lost recorder from pegging
                        // a core; a run of them ends the session.
                        if (++idleReads > MAX_IDLE_READS) {
                            Log.w(TAG, "AudioRecord.read returned 0 $idleReads times, stopping")
                            break
                        }
                        delay(IDLE_BACKOFF_MS)
                    }
                }
            } finally {
                // Release on the same dispatcher the reads happened on.
                audioCaptureManager.stop()
            }
            // Only reached when the loop broke on its own; a cancelled job never
            // gets here, so this cannot re-enter the stop we are being torn down by.
            stop()
        }
    }

    private suspend fun doStop() {
        // Join, don't just cancel: the job's teardown has to be finished before
        // the next start() is allowed to open a recorder.
        streamJob?.cancelAndJoin()
        streamJob = null
        audioCaptureManager.stop() // idempotent; covers a start that never launched
        runCatching { context.stopService(Intent(context, AudioStreamService::class.java)) }
        deviceRepository.setFftStreamActive(false)
    }

    private fun fail(message: String) {
        _error.value = message
        _isStreaming.value = false
        _latestResult.value = null
    }

    private companion object {
        const val TAG = "FftStreamManager"
        const val FRAMES_PER_SECOND = 30

        /** ~half a frame; long enough that a dead recorder costs nothing. */
        const val IDLE_BACKOFF_MS = 15L

        /** ~1 s of nothing but empty reads before the session is declared dead. */
        const val MAX_IDLE_READS = 64
    }
}
