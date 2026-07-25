package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.model.DeviceState
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the diagnostics screen (SYS-3). It observes [DeviceState.diagnostics],
 * which the repository reads on connect and keeps live off the FF0C notify, so
 * the screen has data the moment it opens without a handshake of its own.
 */
class DiagnosticsViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    /** Re-reads FF0C now, for a manual refresh between the once-a-second notifies. */
    fun refresh() {
        viewModelScope.launch { deviceRepository.refreshDiagnostics() }
    }
}
