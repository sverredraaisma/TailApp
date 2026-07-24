package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.audio.FftStreamManager
import com.tailapp.ble.protocol.SystemCommands
import com.tailapp.ble.protocol.SystemEvent
import com.tailapp.model.DeviceState
import com.tailapp.repository.AckedWrite
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the last device-administration command did, for the UI to show. */
data class DeviceAdminStatus(
    val accepted: Boolean,
    val message: String
)

class DeviceOverviewViewModel(
    private val deviceRepository: DeviceRepository,
    private val fftStreamManager: FftStreamManager? = null
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    /** FF07 taps and config-changed notifications, for transient UI feedback. */
    val systemEvents: SharedFlow<SystemEvent> = deviceRepository.systemEvents

    private val _adminStatus = MutableStateFlow<DeviceAdminStatus?>(null)

    /**
     * Outcome of the last rename or forget-bond. These are the two commands the
     * device can refuse for a reason the user has to act on — a stale bond index,
     * a name it will not take — so unlike the optimistic settings elsewhere they
     * report what actually happened rather than assuming.
     */
    val adminStatus: StateFlow<DeviceAdminStatus?> = _adminStatus.asStateFlow()

    val isStreaming: StateFlow<Boolean> =
        fftStreamManager?.isStreaming ?: MutableStateFlow(false)

    fun toggleFftStream() {
        fftStreamManager?.toggle()
    }

    fun disconnect() {
        fftStreamManager?.stop()
        deviceRepository.disconnect()
    }

    /** Clears a stall latch and re-energizes, or forces the motors to freewheel. */
    fun setMotorsEnabled(enabled: Boolean) {
        viewModelScope.launch { deviceRepository.setMotorsEnabled(enabled) }
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

    fun renameProfile(slot: Byte, name: String) {
        viewModelScope.launch { deviceRepository.renameProfile(slot, name) }
    }

    fun refreshProfiles() {
        viewModelScope.launch { deviceRepository.refreshProfiles() }
    }

    // --- Device identity and bonds (SYS-6) ---

    /**
     * Renames the tail, refusing an out-of-range name here rather than sending it.
     *
     * The device rejects an empty or over-long name instead of trimming it, so
     * the only difference between checking first and not is whether the user is
     * told *why* — an FF09 `OUT_OF_RANGE` a second later cannot say which rule
     * was broken.
     */
    fun renameDevice(name: String) {
        val trimmed = name.trim()
        val error = SystemCommands.deviceNameError(trimmed)
        if (error != null) {
            _adminStatus.value = DeviceAdminStatus(accepted = false, message = error.message)
            return
        }
        viewModelScope.launch {
            report(deviceRepository.setDeviceName(trimmed), "Renamed to \"$trimmed\"", "Rename")
        }
    }

    /** Forgets one bonded peer by its index in the device's list. */
    fun forgetBond(index: Int) {
        viewModelScope.launch {
            report(
                deviceRepository.forgetBond(index.toByte()),
                "Bond $index forgotten",
                "Forget bond $index"
            )
        }
    }

    /** Forgets every bond. The next phone to connect has to pair again. */
    fun forgetAllBonds() {
        viewModelScope.launch {
            report(deviceRepository.forgetAllBonds(), "All bonds forgotten", "Forget all bonds")
        }
    }

    fun clearAdminStatus() {
        _adminStatus.value = null
    }

    /**
     * A rejection and an unanswered write are different things and are worded as
     * such: the first is the device saying no — an index it does not have, a name
     * it will not take — and the second is a notification that went astray, where
     * the command may well have been applied.
     */
    private fun report(outcome: AckedWrite, success: String, action: String) {
        _adminStatus.value = when {
            outcome.accepted -> DeviceAdminStatus(accepted = true, message = success)
            outcome.rejected -> DeviceAdminStatus(
                accepted = false,
                message = "$action refused by the device: ${outcome.result?.result?.message}"
            )
            !outcome.written -> DeviceAdminStatus(
                accepted = false,
                message = "$action could not be sent — the connection is gone."
            )
            else -> DeviceAdminStatus(
                accepted = false,
                message = "$action was not acknowledged; re-read the device to see if it took."
            )
        }
    }
}
