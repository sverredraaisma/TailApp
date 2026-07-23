package com.tailapp.audio

import android.util.Log

/**
 * Picks a capture backend.
 *
 * [OboeAudioSource] is the intended path — it is what gets us a low-latency
 * input stream. [AudioRecordAudioSource] exists so a device (or an emulator)
 * that cannot open one still runs the pipeline instead of failing outright.
 */
object AudioSources {
    private const val TAG = "AudioSources"

    /** Creates the best available source without opening it. */
    fun create(sampleRate: Int = FeatureConfig().sampleRate): AudioSource =
        if (OboeAudioSource.isAvailable) {
            OboeAudioSource(sampleRate)
        } else {
            AudioRecordAudioSource(sampleRate)
        }

    /**
     * Creates *and starts* a source, falling back to `AudioRecord` if Oboe cannot
     * open a stream on this device.
     *
     * @throws SecurityException when `RECORD_AUDIO` has not been granted — a
     *   missing permission fails both backends, so it is rethrown rather than
     *   swallowed into a pointless retry.
     */
    fun createStarted(sampleRate: Int = FeatureConfig().sampleRate): AudioSource {
        if (OboeAudioSource.isAvailable) {
            val oboe = OboeAudioSource(sampleRate)
            try {
                oboe.start()
                return oboe
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Oboe capture unavailable, falling back to AudioRecord", e)
                oboe.stop()
            }
        }
        return AudioRecordAudioSource(sampleRate).apply { start() }
    }
}
