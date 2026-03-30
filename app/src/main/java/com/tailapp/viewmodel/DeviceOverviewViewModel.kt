package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.audio.FftStreamManager
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DeviceOverviewViewModel(
    private val deviceRepository: DeviceRepository,
    private val fftStreamManager: FftStreamManager? = null
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    val isStreaming: StateFlow<Boolean> =
        fftStreamManager?.isStreaming ?: MutableStateFlow(false)

    fun toggleFftStream() {
        fftStreamManager?.toggle()
    }

    fun disconnect() {
        fftStreamManager?.stop()
        deviceRepository.disconnect()
    }

    fun saveProfile(slot: Byte) {
        viewModelScope.launch { deviceRepository.saveProfile(slot) }
    }

    fun loadProfile(slot: Byte) {
        viewModelScope.launch { deviceRepository.loadProfile(slot) }
    }

    fun deleteProfile(slot: Byte) {
        viewModelScope.launch { deviceRepository.deleteProfile(slot) }
    }
}
