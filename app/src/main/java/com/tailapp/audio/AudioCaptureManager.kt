package com.tailapp.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AudioCaptureManager {
    companion object {
        const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    val bufferSize: Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        .coerceAtLeast(SAMPLE_RATE / 30 * 2) // at least one frame at 30fps

    @SuppressLint("MissingPermission")
    fun start() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        ).also { it.startRecording() }
    }

    fun stop() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    /**
     * Reads one frame of PCM data. Blocks until data is available.
     */
    fun readFrame(buffer: ShortArray): Int {
        return audioRecord?.read(buffer, 0, buffer.size) ?: 0
    }
}
