package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.StateFlow
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

    fun setPidGains(servoId: Byte, kp: Float, ki: Float, kd: Float) {
        viewModelScope.launch { deviceRepository.setPidGains(servoId, kp, ki, kd) }
    }

    /**
     * Sets the open-loop motion limits and stall sensitivity for one motor.
     * These shape motion on the current firmware; [setPidGains] no longer does.
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

    fun calibrateZero() {
        viewModelScope.launch { deviceRepository.calibrateZero() }
    }

    fun setAxisLimits(axis: Byte, min: Float, max: Float) {
        viewModelScope.launch { deviceRepository.setAxisLimits(axis, min, max) }
    }

    fun setImuTap(imuId: Byte, enabled: Boolean) {
        viewModelScope.launch { deviceRepository.setImuTap(imuId, enabled) }
    }
}
