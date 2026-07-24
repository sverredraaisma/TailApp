package com.tailapp.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.tailapp.audio.FftProcessor
import com.tailapp.audio.FftSettings
import com.tailapp.audio.FftStreamManager
import com.tailapp.effects.LightingEngine
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioConfigViewModel(
    private val deviceRepository: DeviceRepository,
    private val fftStreamManager: FftStreamManager? = null,
    private val prefs: SharedPreferences? = null,
    /**
     * The BeatLight engine, which derives the device's FF05 frames from its own
     * analysis while a session runs. These settings describe the frame the
     * device receives, so they have to reach both producers or the spectrum
     * would change shape depending on which one happened to be streaming.
     */
    private val lightingEngine: LightingEngine? = null
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    private val _numBins: MutableStateFlow<Int>
    val numBins: StateFlow<Int>

    private val _normalizationSpeed: MutableStateFlow<Float>
    val normalizationSpeed: StateFlow<Float>

    private val _freqStart: MutableStateFlow<Float>
    val freqStart: StateFlow<Float>

    private val _freqEnd: MutableStateFlow<Float>
    val freqEnd: StateFlow<Float>

    val isStreaming: StateFlow<Boolean> =
        fftStreamManager?.isStreaming ?: MutableStateFlow(false)

    /** Non-null when the microphone could not be opened. */
    val streamError: StateFlow<String?> =
        fftStreamManager?.error ?: MutableStateFlow(null)

    init {
        // Initialize from persisted prefs, falling back to processor state, falling back to defaults
        val processor = fftStreamManager?.fftProcessor

        val initBins = prefs?.getInt(KEY_NUM_BINS, processor?.numBins ?: 64) ?: processor?.numBins ?: 64
        val initNorm = prefs?.getFloat(KEY_NORM_SPEED, processor?.normalizationSpeed ?: 0.1f) ?: processor?.normalizationSpeed ?: 0.1f
        val initStart = prefs?.getFloat(KEY_FREQ_START, processor?.freqRangeStart ?: 20f) ?: processor?.freqRangeStart ?: 20f
        val initEnd = prefs?.getFloat(KEY_FREQ_END, processor?.freqRangeEnd ?: 20000f) ?: processor?.freqRangeEnd ?: 20000f

        _numBins = MutableStateFlow(initBins)
        numBins = _numBins.asStateFlow()
        _normalizationSpeed = MutableStateFlow(initNorm)
        normalizationSpeed = _normalizationSpeed.asStateFlow()
        _freqStart = MutableStateFlow(initStart)
        freqStart = _freqStart.asStateFlow()
        _freqEnd = MutableStateFlow(initEnd)
        freqEnd = _freqEnd.asStateFlow()

        // Apply restored settings to the processor
        processor?.numBins = initBins
        processor?.normalizationSpeed = initNorm
        processor?.freqRangeStart = initStart
        processor?.freqRangeEnd = initEnd
        applyToEngine()
    }

    /**
     * Mirrors the settings onto the engine's encoder, so the device sees the
     * same frame shape whichever producer is streaming.
     */
    private fun applyToEngine() {
        lightingEngine?.fftSettings = FftSettings(
            binCount = _numBins.value,
            frequencyStartHz = _freqStart.value,
            frequencyEndHz = _freqEnd.value
        )
    }

    fun setNumBins(value: Int) {
        val clamped = value.coerceIn(FftProcessor.MIN_BINS, FftProcessor.MAX_BINS)
        _numBins.value = clamped
        fftStreamManager?.fftProcessor?.numBins = clamped
        applyToEngine()
        prefs?.edit()?.putInt(KEY_NUM_BINS, clamped)?.apply()
    }

    fun setNormalizationSpeed(value: Float) {
        _normalizationSpeed.value = value
        fftStreamManager?.fftProcessor?.normalizationSpeed = value
        prefs?.edit()?.putFloat(KEY_NORM_SPEED, value)?.apply()
    }

    fun setFreqStart(value: Float) {
        _freqStart.value = value
        fftStreamManager?.fftProcessor?.freqRangeStart = value
        applyToEngine()
        prefs?.edit()?.putFloat(KEY_FREQ_START, value)?.apply()
    }

    fun setFreqEnd(value: Float) {
        _freqEnd.value = value
        fftStreamManager?.fftProcessor?.freqRangeEnd = value
        applyToEngine()
        prefs?.edit()?.putFloat(KEY_FREQ_END, value)?.apply()
    }

    fun toggleStream() {
        fftStreamManager?.toggle()
    }

    fun clearStreamError() {
        fftStreamManager?.clearError()
    }

    companion object {
        private const val KEY_NUM_BINS = "fft_num_bins"
        private const val KEY_NORM_SPEED = "fft_norm_speed"
        private const val KEY_FREQ_START = "fft_freq_start"
        private const val KEY_FREQ_END = "fft_freq_end"
    }
}
