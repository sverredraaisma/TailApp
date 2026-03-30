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

    fun setServoConfig(servoId: Byte, axis: Byte, half: Byte, invert: Byte) {
        viewModelScope.launch { deviceRepository.setServoConfig(servoId, axis, half, invert) }
    }

    fun setPidGains(servoId: Byte, kp: Float, ki: Float, kd: Float) {
        viewModelScope.launch { deviceRepository.setPidGains(servoId, kp, ki, kd) }
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
