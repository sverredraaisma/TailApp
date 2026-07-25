package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MotionConfigViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    fun selectPattern(patternId: Byte) {
        viewModelScope.launch { deviceRepository.selectPattern(patternId) }
    }

    fun setPatternParam(paramId: Byte, value: Float) {
        viewModelScope.launch { deviceRepository.setPatternParam(paramId, value) }
    }

    /** [muxChannel] is optional — pass null to leave the encoder channel untouched. */
    fun setServoConfig(servoId: Byte, axis: Byte, half: Byte, invert: Byte, muxChannel: Byte? = null) {
        viewModelScope.launch { deviceRepository.setServoConfig(servoId, axis, half, invert, muxChannel) }
    }

    /**
     * Sets the open-loop motion limits and stall sensitivity for one motor.
     * These shape motion on the current firmware; the PID gains they replaced
     * were retired in protocol v6.
     */
    fun setMotionLimits(
        servoId: Byte,
        maxVelocity: Float,
        maxAcceleration: Float,
        maxJerk: Float,
        stallThreshold: Byte
    ) {
        viewModelScope.launch {
            deviceRepository.setMotionLimits(
                servoId, maxVelocity, maxAcceleration, maxJerk, stallThreshold
            )
        }
    }

    private var puppetJob: Job? = null

    /**
     * Drives the tail directly from a drag, as a fraction of each axis's travel.
     *
     * Keeps re-sending while held rather than sending once: the device ages
     * streamed targets out after 500 ms and hands control back to its own
     * pattern, which is what stops an abandoned stream leaving the tail holding
     * a pose. A single write would therefore be a twitch, not a hold.
     *
     * @param x left/right, `-1..1`. @param y up/down, `-1..1`.
     */
    fun startPuppet(x: Float, y: Float) {
        val state = deviceRepository.deviceState.value.motionState ?: return
        // Fractions of the *configured* travel, so the pad means the same thing
        // whatever limits the user has set.
        fun scale(f: Float, min: Float, max: Float): Float {
            val centre = (max + min) / 2f
            val half = (max - min) / 2f
            return centre + f.coerceIn(-1f, 1f) * half
        }
        val tx = scale(x, state.xAxisMin, state.xAxisMax)
        val ty = scale(y, state.yAxisMin, state.yAxisMax)
        val targets = floatArrayOf(tx, tx, ty, ty)

        puppetJob?.cancel()
        puppetJob = viewModelScope.launch {
            while (isActive) {
                deviceRepository.streamMotionTargets(targets)
                delay(PUPPET_RESEND_MILLIS)
            }
        }
    }

    /**
     * Stops driving. The device's own timeout then hands the tail back to its
     * pattern — deliberately not an explicit "release" command, because the
     * timeout has to work for the disconnect case anyway and one mechanism is
     * better than two.
     */
    fun stopPuppet() {
        puppetJob?.cancel()
        puppetJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPuppet()
    }

    fun calibrateZero() {
        viewModelScope.launch { deviceRepository.calibrateZero() }
    }

    fun setAxisLimits(axis: Byte, min: Float, max: Float) {
        viewModelScope.launch { deviceRepository.setAxisLimits(axis, min, max) }
    }

    fun setImuTap(imuId: Byte, enabled: Boolean) {
        viewModelScope.launch { deviceRepository.setImuTap(imuId, enabled) }
    }

    private companion object {
        /**
         * Comfortably inside the device's 500 ms target timeout, so a held pose
         * stays held even if a couple of writes are lost.
         */
        const val PUPPET_RESEND_MILLIS = 100L
    }
}
