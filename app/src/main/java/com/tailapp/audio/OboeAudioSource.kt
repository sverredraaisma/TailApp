package com.tailapp.audio

import android.util.Log

/**
 * Mic capture through Oboe (`app/src/main/cpp/oboe_capture.cpp`).
 *
 * Oboe selects AAudio or OpenSL ES per device and gives the lowest input latency
 * the platform can manage. The native audio callback only copies its burst into
 * a lock-free ring buffer, so nothing on this side can stall it; [read] drains
 * that buffer over JNI at whatever pace the analysis loop wants.
 *
 * The device may refuse the requested rate, in which case Oboe resamples for us —
 * [sampleRate] always reports what the stream actually runs at, and the pipeline
 * resamples again if that is not the analysis rate.
 *
 * @param requestedSampleRate rate to ask the stream for.
 * @param ringCapacitySamples ring-buffer depth. The default holds ~1.5 s at
 *   22050 Hz, enough to ride out a scheduling hiccup without dropping audio.
 */
class OboeAudioSource(
    private val requestedSampleRate: Int = FeatureConfig().sampleRate,
    private val ringCapacitySamples: Int = 1 shl 15
) : AudioSource {

    private var handle: Long = 0L

    @Volatile
    private var running = false

    @Volatile
    private var actualSampleRate = requestedSampleRate

    override val sampleRate: Int get() = actualSampleRate

    override val isRunning: Boolean get() = running

    override val overrunCount: Long
        get() = if (handle != 0L) nativeOverruns(handle) else 0L

    override val latencyMillis: Float
        get() = if (handle != 0L) nativeLatencyMillis(handle) else 0f

    /**
     * True once since the last call if the stream was torn down underneath us —
     * a route change (headset plugged in, Bluetooth audio connecting) closes the
     * stream. Callers poll this and [restart] to recover.
     */
    fun consumeDisconnected(): Boolean =
        handle != 0L && nativeConsumeDisconnected(handle)

    override fun start() {
        if (running) return
        check(isAvailable) { "libtailapp_audio.so is not loaded" }

        if (handle == 0L) {
            handle = nativeCreate(requestedSampleRate, ringCapacitySamples)
            check(handle != 0L) { "could not allocate the native capture engine" }
        }

        val result = nativeStart(handle)
        if (result != 0) {
            // Oboe returns negative result codes; the common one is ErrorInvalidState
            // when RECORD_AUDIO was revoked or another app holds the mic exclusively.
            nativeDestroy(handle)
            handle = 0L
            throw IllegalStateException("Oboe failed to open the input stream (code $result)")
        }

        actualSampleRate = nativeSampleRate(handle)
        running = true
        Log.i(TAG, "capture started at ${actualSampleRate}Hz, latency ${latencyMillis}ms")
    }

    override fun stop() {
        if (handle == 0L) return
        running = false
        nativeStop(handle)
        nativeDestroy(handle)
        handle = 0L
    }

    /** Closes and reopens the stream, e.g. after [consumeDisconnected] reported a route change. */
    fun restart() {
        stop()
        start()
    }

    override fun read(out: FloatArray, count: Int): Int {
        if (!running || handle == 0L) return 0
        val n = count.coerceAtMost(out.size)
        if (n <= 0) return 0
        return nativeRead(handle, out, n)
    }

    private external fun nativeCreate(sampleRate: Int, ringCapacity: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeRead(handle: Long, out: FloatArray, count: Int): Int
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeLatencyMillis(handle: Long): Float
    private external fun nativeOverruns(handle: Long): Long
    private external fun nativeConsumeDisconnected(handle: Long): Boolean

    companion object {
        private const val TAG = "OboeAudioSource"

        /**
         * Whether the native capture library loaded. False on an ABI we have no
         * build for, which is why [AudioSources] keeps an `AudioRecord` fallback
         * rather than making Oboe a hard requirement.
         */
        val isAvailable: Boolean by lazy {
            runCatching { System.loadLibrary("tailapp_audio") }
                .onFailure { Log.w(TAG, "native capture unavailable: ${it.message}") }
                .isSuccess
        }
    }
}
