package com.tailapp.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread

/**
 * `AudioRecord`-backed [AudioSource], used when Oboe is unavailable (an ABI with
 * no native build, or a device that refuses to open a low-latency input stream).
 *
 * Higher latency than [OboeAudioSource] and no exclusive-mode path, but it keeps
 * the analysis pipeline running everywhere — including emulators, where a
 * low-latency input stream usually cannot be opened at all.
 *
 * Reads run on a dedicated `THREAD_PRIORITY_URGENT_AUDIO` thread that does
 * nothing but convert to float and hand samples to a [FloatRingBuffer].
 *
 * @param requestedSampleRate rate to open the recorder at. Unlike Oboe this does
 *   not resample, so [sampleRate] is whatever `AudioRecord` accepted.
 */
class AudioRecordAudioSource(
    private val requestedSampleRate: Int = FeatureConfig().sampleRate,
    ringCapacitySamples: Int = 1 shl 15
) : AudioSource {

    private val ring = FloatRingBuffer(ringCapacitySamples)

    private var record: AudioRecord? = null
    private var readerThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var actualSampleRate = requestedSampleRate

    override val sampleRate: Int get() = actualSampleRate

    override val isRunning: Boolean get() = running

    override val overrunCount: Long get() = ring.overrunCount

    /** `AudioRecord` reports no latency figure; the buffer depth is the honest floor. */
    override val latencyMillis: Float
        get() = 1000f * bufferSamples / actualSampleRate

    private var bufferSamples = 0

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running) return

        val minBytes = AudioRecord.getMinBufferSize(
            requestedSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBytes > 0) { "AudioRecord rejected ${requestedSampleRate}Hz mono 16-bit" }

        // Two minimum buffers: one being filled while we drain the other.
        val bufferBytes = minBytes * 2
        bufferSamples = bufferBytes / 2

        // UNPROCESSED is not implemented on every device and fails to initialise
        // there rather than degrading, so fall back to plain MIC.
        val recorder = openRecorder(preferredSource(), bufferBytes)
            ?: openRecorder(MediaRecorder.AudioSource.MIC, bufferBytes)
            ?: throw IllegalStateException("AudioRecord failed to initialise")

        actualSampleRate = recorder.sampleRate
        ring.clear()
        recorder.startRecording()
        record = recorder
        running = true

        val chunk = ShortArray(bufferSamples / 2)
        val floats = FloatArray(chunk.size)
        readerThread = thread(name = "TailApp-AudioRecord", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    for (i in 0 until read) floats[i] = chunk[i] / 32768f
                    ring.write(floats, 0, read)
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read, stopping capture")
                    running = false
                }
            }
            runCatching { recorder.stop() }
            recorder.release()
        }
        Log.i(TAG, "capture started at ${actualSampleRate}Hz, buffer ${bufferSamples} samples")
    }

    override fun stop() {
        if (!running) return
        running = false
        // The reader thread owns stop()/release() so it never touches a released
        // recorder mid-read; just cut it loose and forget our reference.
        readerThread = null
        record = null
    }

    override fun read(out: FloatArray, count: Int): Int =
        ring.read(out, 0, count.coerceAtMost(out.size))

    @SuppressLint("MissingPermission")
    private fun openRecorder(source: Int, bufferBytes: Int): AudioRecord? {
        val recorder = runCatching {
            AudioRecord(
                source,
                requestedSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        }.getOrElse {
            // A revoked RECORD_AUDIO permission surfaces here as a SecurityException;
            // let it through, since retrying another source cannot help.
            if (it is SecurityException) throw it
            Log.w(TAG, "AudioRecord(source=$source) threw: ${it.message}")
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.w(TAG, "AudioRecord(source=$source) did not initialise")
            return null
        }
        return recorder
    }

    private fun preferredSource(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // UNPROCESSED skips AGC/noise suppression, which otherwise flattens
            // exactly the dynamics the beat and drop detectors key off.
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }

    private companion object {
        const val TAG = "AudioRecordSource"
    }
}
