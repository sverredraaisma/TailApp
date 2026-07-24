package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.model.BehaviorReason
import com.tailapp.model.BehaviorRuntime
import com.tailapp.model.BehaviorStateConfig
import com.tailapp.model.BehaviorTable
import com.tailapp.model.BehaviorTriggerConfig
import com.tailapp.model.BehaviorTriggerSource
import com.tailapp.model.DeviceState
import com.tailapp.repository.AckedWrite
import com.tailapp.repository.BehaviorTableStore
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One transition the engine made, as the app saw it happen.
 *
 * Reconstructed from the FF02 behavior block rather than from the FF07 event,
 * which carries no payload: the block is the only place the state and the reason
 * arrive together. It is sampled at the ~20 Hz notify rate, so two transitions
 * inside one notify period land as one — the reason shown is then the second
 * one's, which is the honest reading of what the device reported.
 */
data class BehaviorTransition(
    val fromState: Int?,
    val toState: Int?,
    val reason: BehaviorReason
)

/**
 * Backs the behavior-engine editor: the app's copy of the table, the writes that
 * push it to the device, and the live state the device reports back.
 *
 * The table is **write-only on the wire** — the firmware publishes no read for
 * it — so [table] is what this phone last sent, kept by [BehaviorTableStore] and
 * seeded from the firmware's factory table. Every edit is validated here before
 * it is encoded, because the alternative to catching an out-of-range field is
 * the device quietly repairing it (`BehaviorEngine::normalize`) into a rule that
 * never fires, with the editor still showing what the user typed.
 */
class BehaviorConfigViewModel(
    private val deviceRepository: DeviceRepository,
    private val store: BehaviorTableStore
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    private val _table = MutableStateFlow(store.load())
    val table: StateFlow<BehaviorTable> = _table.asStateFlow()

    /** The last rejection — a validation refusal here, or the device's on FF09. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _transitions = MutableStateFlow<List<BehaviorTransition>>(emptyList())

    /** Most recent first. Empty until the engine actually moves. */
    val transitions: StateFlow<List<BehaviorTransition>> = _transitions.asStateFlow()

    init {
        viewModelScope.launch {
            var previous: BehaviorRuntime? = null
            deviceRepository.deviceState
                .map { it.motionState?.behavior }
                .distinctUntilChanged()
                .collect { current ->
                    val before = previous
                    previous = current
                    if (current == null || before == null) return@collect
                    if (before.stateIndex == current.stateIndex &&
                        before.reason == current.reason
                    ) {
                        return@collect
                    }
                    val entry = BehaviorTransition(
                        fromState = before.stateIndex,
                        toState = current.stateIndex,
                        reason = current.reason
                    )
                    _transitions.value = (listOf(entry) + _transitions.value).take(HISTORY_DEPTH)
                }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    // --- engine ---

    fun setEngineEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!report("The engine switch", deviceRepository.setBehaviorEnabled(enabled))) return@launch
            update(_table.value.copy(engineEnabled = enabled))
        }
    }

    /**
     * Shows a state now, ignoring its minimum dwell. The engine keeps running
     * from there, so this previews the mood rather than pinning it.
     */
    fun previewState(index: Int) {
        viewModelScope.launch {
            report("Forcing state $index", deviceRepository.forceBehaviorState(index))
        }
    }

    // --- table edits ---

    fun editState(index: Int, transform: (BehaviorStateConfig) -> BehaviorStateConfig) {
        val current = _table.value.states.getOrNull(index) ?: return
        val edited = transform(current)
        val problems = edited.validate()
        if (problems.isNotEmpty()) {
            _message.value = "State $index: ${problems.first()}"
            return
        }
        update(_table.value.withState(index, edited))
        viewModelScope.launch {
            report("State $index", deviceRepository.setBehaviorStateConfig(index, edited))
        }
    }

    fun editTrigger(index: Int, transform: (BehaviorTriggerConfig) -> BehaviorTriggerConfig) {
        val current = _table.value.triggers.getOrNull(index) ?: return
        val edited = transform(current)
        val problems = edited.validate()
        if (problems.isNotEmpty()) {
            _message.value = "Trigger $index: ${problems.first()}"
            return
        }
        update(_table.value.withTrigger(index, edited))
        viewModelScope.launch {
            report("Trigger $index", deviceRepository.setBehaviorTrigger(index, edited))
        }
    }

    /** Turns a row off without losing what it was, by clearing only its source. */
    fun disableTrigger(index: Int) {
        editTrigger(index) { it.copy(source = BehaviorTriggerSource.NONE) }
    }

    /**
     * Writes the whole table to the device, record by record.
     *
     * The device cannot be asked what it holds, so this is how a table edited
     * against one tail is installed on another — and how a table edited while
     * disconnected gets there at all.
     */
    fun pushTable() {
        val current = _table.value
        viewModelScope.launch {
            current.states.forEachIndexed { i, state ->
                if (state.validate().isNotEmpty()) return@forEachIndexed
                if (!report("State $i", deviceRepository.setBehaviorStateConfig(i, state))) return@launch
            }
            current.triggers.forEachIndexed { i, trigger ->
                if (trigger.validate().isNotEmpty()) return@forEachIndexed
                if (!report("Trigger $i", deviceRepository.setBehaviorTrigger(i, trigger))) return@launch
            }
            report("The engine switch", deviceRepository.setBehaviorEnabled(current.engineEnabled))
        }
    }

    /**
     * Returns the editor to the firmware's factory table and installs it, so
     * "reset" means the same thing on both sides instead of only on the phone.
     */
    fun resetToFirmwareDefaults() {
        store.clear()
        _table.value = BehaviorTable.FIRMWARE_DEFAULT
        pushTable()
    }

    private fun update(table: BehaviorTable) {
        _table.value = table
        store.save(table)
    }

    /** @return true when the device accepted the write. */
    private fun report(what: String, outcome: AckedWrite): Boolean {
        if (outcome.accepted) return true
        _message.value = when {
            !outcome.written -> "$what: the write never left the phone"
            outcome.result == null -> "$what: the device did not answer"
            else -> "$what: ${outcome.result.result.message}"
        }
        return false
    }

    private companion object {
        /** Enough to see a mood cycle; a full log is the firmware's business. */
        const val HISTORY_DEPTH = 20
    }
}
