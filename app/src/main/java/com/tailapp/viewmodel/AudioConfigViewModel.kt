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

        val rawBins = prefs?.getInt(KEY_NUM_BINS, processor?.numBins ?: DEFAULT_BINS)
            ?: processor?.numBins ?: DEFAULT_BINS
        val rawNorm = prefs?.getFloat(KEY_NORM_SPEED, processor?.normalizationSpeed ?: DEFAULT_NORM_SPEED)
            ?: processor?.normalizationSpeed ?: DEFAULT_NORM_SPEED
        val rawStart = prefs?.getFloat(KEY_FREQ_START, processor?.freqRangeStart ?: MIN_FREQ_HZ)
            ?: processor?.freqRangeStart ?: MIN_FREQ_HZ
        val rawEnd = prefs?.getFloat(KEY_FREQ_END, processor?.freqRangeEnd ?: MAX_FREQ_HZ)
            ?: processor?.freqRangeEnd ?: MAX_FREQ_HZ

        // Sanitised on the way in, not just on the way out. A pref written by an
        // older build (or a processor default of 0) would otherwise reach the
        // screen, where `ln(0)` is -Infinity and an inverted range is an empty
        // spectrum with nothing saying so.
        val initBins = rawBins.coerceIn(FftProcessor.MIN_BINS, FftProcessor.MAX_BINS)
        val initNorm = sanitizeNormSpeed(rawNorm)
        val initStart = sanitizeFreq(rawStart, MIN_FREQ_HZ)
            .coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ - MIN_FREQ_SPAN_HZ)
        val initEnd = sanitizeFreq(rawEnd, MAX_FREQ_HZ)
            .coerceIn(initStart + MIN_FREQ_SPAN_HZ, MAX_FREQ_HZ)

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
        val clamped = sanitizeNormSpeed(value)
        _normalizationSpeed.value = clamped
        fftStreamManager?.fftProcessor?.normalizationSpeed = clamped
        prefs?.edit()?.putFloat(KEY_NORM_SPEED, clamped)?.apply()
    }

    /**
     * The bottom of the analysed band. Kept at least [MIN_FREQ_SPAN_HZ] below the
     * top: an inverted or zero-width range produces an empty spectrum, and the
     * only sign of it would be a preview that went dark for no stated reason —
     * so the two ends push each other rather than crossing.
     */
    fun setFreqStart(value: Float) {
        val clamped = sanitizeFreq(value, MIN_FREQ_HZ)
            .coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ - MIN_FREQ_SPAN_HZ)
        _freqStart.value = clamped
        fftStreamManager?.fftProcessor?.freqRangeStart = clamped
        prefs?.edit()?.putFloat(KEY_FREQ_START, clamped)?.apply()
        if (_freqEnd.value < clamped + MIN_FREQ_SPAN_HZ) {
            setFreqEnd(clamped + MIN_FREQ_SPAN_HZ)
        } else {
            applyToEngine()
        }
    }

    /** The top of the analysed band; see [setFreqStart] for the span rule. */
    fun setFreqEnd(value: Float) {
        val clamped = sanitizeFreq(value, MAX_FREQ_HZ)
            .coerceIn(MIN_FREQ_HZ + MIN_FREQ_SPAN_HZ, MAX_FREQ_HZ)
        _freqEnd.value = clamped
        fftStreamManager?.fftProcessor?.freqRangeEnd = clamped
        prefs?.edit()?.putFloat(KEY_FREQ_END, clamped)?.apply()
        if (_freqStart.value > clamped - MIN_FREQ_SPAN_HZ) {
            setFreqStart(clamped - MIN_FREQ_SPAN_HZ)
        } else {
            applyToEngine()
        }
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

        const val DEFAULT_BINS = 64
        const val DEFAULT_NORM_SPEED = 0.1f

        /** Human hearing, and the range the log-scaled sliders span. */
        const val MIN_FREQ_HZ = 20f
        const val MAX_FREQ_HZ = 20000f

        /** Narrowest band the two ends may be squeezed to before they push apart. */
        const val MIN_FREQ_SPAN_HZ = 50f

        const val MIN_NORM_SPEED = 0.01f
        const val MAX_NORM_SPEED = 1f

        /**
         * A frequency that can be logged and sliced. A non-finite or
         * non-positive value (a stale pref, an uninitialised processor) is
         * replaced by [fallback] rather than propagated: `ln(0f)` is
         * `-Infinity`, which puts the slider thumb at NaN.
         */
        fun sanitizeFreq(value: Float, fallback: Float): Float =
            if (!value.isFinite() || value <= 0f) fallback
            else value.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)

        fun sanitizeNormSpeed(value: Float): Float =
            if (!value.isFinite()) DEFAULT_NORM_SPEED
            else value.coerceIn(MIN_NORM_SPEED, MAX_NORM_SPEED)
    }
}
