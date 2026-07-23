package com.tailapp.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioCaptureManager {
    companion object {
        const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    val bufferSize: Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        .coerceAtLeast(SAMPLE_RATE / 30 * 2) // at least one frame at 30fps

    /**
     * Opens the microphone. Throws [SecurityException] when RECORD_AUDIO has not
     * been granted, and [IllegalStateException] when the recorder cannot start.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialise")
        }
        record.startRecording()
        audioRecord = record
    }

    fun stop() {
        val record = audioRecord ?: return
        audioRecord = null
        runCatching { record.stop() }
        record.release()
    }

    /**
     * Reads one frame of PCM data. Blocks until data is available.
     * Returns 0 when capture is not running.
     */
    fun readFrame(buffer: ShortArray): Int {
        return audioRecord?.read(buffer, 0, buffer.size) ?: 0
    }
}
