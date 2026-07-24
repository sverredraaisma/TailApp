package com.tailapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailapp.ble.protocol.KeyframeSequenceCodec
import com.tailapp.ble.protocol.Protocol
import com.tailapp.model.DeviceState
import com.tailapp.model.Keyframe
import com.tailapp.model.KeyframeSequence
import com.tailapp.model.MotionPattern
import com.tailapp.model.SequenceProblem
import com.tailapp.model.TailPose
import com.tailapp.repository.DeviceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Backs the keyframe-sequence editor: the authored sequence, the scrubbed
 * preview of it, and the upload to a device slot.
 *
 * The sequence lives here rather than on the device while it is being edited —
 * there is no partial upload and no read-back (`MCMD_*_SEQUENCE` is write-only),
 * so authoring is entirely local until the user commits a whole sequence to a
 * slot.
 */
class KeyframeEditorViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = deviceRepository.deviceState

    private val _sequence = MutableStateFlow(KeyframeSequence())

    /** The sequence being authored; always sorted by time. */
    val sequence: StateFlow<KeyframeSequence> = _sequence.asStateFlow()

    private val _problems = MutableStateFlow(_sequence.value.validate())

    /** Everything the device would refuse this sequence for, live. */
    val problems: StateFlow<List<SequenceProblem>> = _problems.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    private val _previewTimeMs = MutableStateFlow(0)
    val previewTimeMs: StateFlow<Int> = _previewTimeMs.asStateFlow()

    private val _previewPose = MutableStateFlow(_sequence.value.sampleAtMillis(0))

    /**
     * The pose at [previewTimeMs], interpolated exactly as the device's
     * `PATTERN_KEYFRAME` interpolates it.
     */
    val previewPose: StateFlow<TailPose> = _previewPose.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _driveTail = MutableStateFlow(false)
    val driveTail: StateFlow<Boolean> = _driveTail.asStateFlow()

    private val _slot = MutableStateFlow(0)

    /** Which stored sequence slot an upload targets. */
    val slot: StateFlow<Int> = _slot.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress: StateFlow<Float?> = _uploadProgress.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** One line about the last thing that happened; null once dismissed. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private var playJob: Job? = null
    private var driveJob: Job? = null

    // --- editing ---

    /**
     * Adds a keyframe holding the pose currently on screen.
     *
     * It lands at the scrubbed time when nothing else is there, and after the
     * end otherwise: two keyframes at one instant give the device's interpolator
     * a zero-length span, so the editor never creates that state rather than
     * creating it and reporting it.
     */
    fun addKeyframe() {
        val current = _sequence.value
        if (current.keyframes.size >= Protocol.MAX_SEQUENCE_KEYFRAMES) {
            _status.value =
                "The device stores at most ${Protocol.MAX_SEQUENCE_KEYFRAMES} keyframes."
            return
        }
        val at = _previewTimeMs.value
        val timeMs = if (current.keyframes.none { it.timeMs == at }) {
            at
        } else {
            current.durationMs + DEFAULT_GAP_MS
        }
        val added = Keyframe(timeMs, _previewPose.value)
        update { it.copy(keyframes = it.keyframes + added) }
        _selectedIndex.value = _sequence.value.keyframes.indexOfFirst { it === added }
        scrubTo(timeMs)
    }

    /**
     * Removes one keyframe.
     *
     * Deleting the first one shifts the rest back so the sequence still starts
     * at zero, which the device requires; the alternative is a sequence that
     * validates only after the user works out why.
     */
    fun deleteKeyframe(index: Int) {
        val current = _sequence.value
        if (index !in current.keyframes.indices) return
        if (current.keyframes.size == 1) {
            _status.value = "A sequence needs at least one keyframe."
            return
        }
        update {
            val kept = it.keyframes.toMutableList().apply { removeAt(index) }
            val shift = kept.first().timeMs
            it.copy(keyframes = kept.map { k -> k.copy(timeMs = k.timeMs - shift) })
        }
        _selectedIndex.value = index.coerceAtMost(_sequence.value.keyframes.lastIndex)
    }

    /**
     * Moves a keyframe [offset] places through the sequence.
     *
     * The poses trade places and the time grid stays put: timestamps have to
     * stay strictly increasing, so reordering by carrying the times along would
     * mean rewriting every one of them.
     */
    fun moveKeyframe(index: Int, offset: Int) {
        val current = _sequence.value
        val target = index + offset
        if (index !in current.keyframes.indices || target !in current.keyframes.indices) return
        update {
            val keyframes = it.keyframes.toMutableList()
            val a = keyframes[index]
            val b = keyframes[target]
            keyframes[index] = a.copy(pose = b.pose)
            keyframes[target] = b.copy(pose = a.pose)
            it.copy(keyframes = keyframes)
        }
        _selectedIndex.value = target
    }

    /**
     * Retimes a keyframe. The first one is pinned: the device rejects a sequence
     * whose first keyframe is not at zero.
     */
    fun setKeyframeTime(index: Int, timeMs: Int) {
        val current = _sequence.value
        if (index !in current.keyframes.indices) return
        if (index == 0) {
            _status.value = "The first keyframe stays at 0 s."
            return
        }
        val moved = current.keyframes[index].copy(timeMs = timeMs.coerceAtLeast(0))
        update {
            val keyframes = it.keyframes.toMutableList()
            keyframes[index] = moved
            it.copy(keyframes = keyframes)
        }
        _selectedIndex.value = _sequence.value.keyframes.indexOfFirst { it === moved }
        scrubTo(moved.timeMs)
    }

    fun setPose(index: Int, pose: TailPose) {
        val current = _sequence.value
        if (index !in current.keyframes.indices) return
        update {
            val keyframes = it.keyframes.toMutableList()
            keyframes[index] = keyframes[index].copy(pose = pose)
            it.copy(keyframes = keyframes)
        }
    }

    fun selectKeyframe(index: Int) {
        val current = _sequence.value
        if (index !in current.keyframes.indices) return
        _selectedIndex.value = index
        scrubTo(current.keyframes[index].timeMs)
    }

    fun setLoop(loop: Boolean) {
        update { it.copy(loop = loop) }
    }

    /** Replaces the whole sequence, e.g. when starting over. */
    fun resetSequence() {
        stopPlayback()
        _selectedIndex.value = 0
        _previewTimeMs.value = 0
        update { KeyframeSequence() }
    }

    // --- preview ---

    fun scrubTo(timeMs: Int) {
        _previewTimeMs.value = timeMs.coerceIn(0, _sequence.value.durationMs)
        refreshPreview()
    }

    /**
     * Runs the preview clock. It advances in fixed steps rather than from wall
     * time so the poses shown are the ones the device would hold — the device
     * samples on its own motion tick, and a preview that interpolated on a
     * different grid would show intermediate poses that never occur.
     */
    fun togglePlay() {
        if (_playing.value) {
            stopPlayback()
            return
        }
        val duration = _sequence.value.durationMs
        if (duration <= 0) return
        if (_previewTimeMs.value >= duration) scrubTo(0)
        _playing.value = true
        playJob?.cancel()
        playJob = viewModelScope.launch {
            while (isActive) {
                delay(FRAME_MILLIS)
                val end = _sequence.value.durationMs
                val next = _previewTimeMs.value + FRAME_MILLIS.toInt()
                if (next >= end) {
                    if (_sequence.value.loop && end > 0) {
                        scrubTo(next % end)
                    } else {
                        scrubTo(end)
                        _playing.value = false
                        return@launch
                    }
                } else {
                    scrubTo(next)
                }
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        _playing.value = false
    }

    /**
     * Mirrors the previewed pose onto the real tail over FF0B.
     *
     * Re-sent on a timer rather than only when the pose changes: the device ages
     * streamed targets out after 500 ms and hands the tail back to its own
     * pattern, so a held pose has to keep saying so.
     */
    fun setDriveTail(enabled: Boolean) {
        _driveTail.value = enabled
        driveJob?.cancel()
        driveJob = null
        if (!enabled) return
        driveJob = viewModelScope.launch {
            while (isActive) {
                deviceRepository.streamMotionTargets(_previewPose.value.motorTargets)
                delay(DRIVE_RESEND_MILLIS)
            }
        }
    }

    // --- upload ---

    fun selectSlot(slot: Int) {
        _slot.value = slot.coerceIn(0, Protocol.MAX_SEQUENCE_SLOTS - 1)
    }

    fun clearStatus() {
        _status.value = null
    }

    /**
     * Encodes the sequence and sends it to the selected slot.
     *
     * Refuses locally on anything the device would refuse: the transfer is
     * dozens of writes, and finding out at finalize that a timestamp ran
     * backwards tells the user nothing about which one.
     */
    fun upload() {
        val current = _sequence.value
        val problems = current.validate()
        if (problems.isNotEmpty()) {
            _status.value = problems.first().message
            return
        }
        viewModelScope.launch {
            _status.value = null
            _uploadProgress.value = 0f
            try {
                val blob = KeyframeSequenceCodec.encode(current)
                val mtu = deviceRepository.negotiatedMtu.value.let { if (it > 23) it else DEFAULT_MTU }
                val result = deviceRepository.uploadSequence(
                    blob = blob,
                    slot = _slot.value.toByte(),
                    chunkSize = KeyframeSequenceCodec.chunkSizeForMtu(mtu),
                    onProgress = { _uploadProgress.value = it }
                )
                _status.value = result.message
            } finally {
                _uploadProgress.value = null
            }
        }
    }

    /**
     * Points the device at the uploaded slot and switches it to the keyframe
     * pattern, in that order — selecting a sequence while the pattern is already
     * running swaps the poses under it, but selecting the pattern first would
     * play whatever slot was previously chosen for a moment.
     */
    fun playOnTail() {
        viewModelScope.launch {
            val selected = deviceRepository.selectSequence(_slot.value.toByte())
            if (!selected.accepted) {
                _status.value = if (selected.rejected) {
                    "Slot ${_slot.value} holds no playable sequence — upload one first."
                } else {
                    "The tail did not answer; it may not be connected."
                }
                return@launch
            }
            deviceRepository.selectPattern(MotionPattern.KEYFRAME.id)
            _status.value = "Playing slot ${_slot.value} on the tail."
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        driveJob?.cancel()
        driveJob = null
    }

    /** Applies an edit, re-sorts, and brings the derived state back in step. */
    private fun update(transform: (KeyframeSequence) -> KeyframeSequence) {
        val next = transform(_sequence.value)
        _sequence.value = next.copy(keyframes = next.keyframes.sortedBy { it.timeMs })
        _problems.value = _sequence.value.validate()
        _selectedIndex.value =
            _selectedIndex.value.coerceIn(0, _sequence.value.keyframes.lastIndex.coerceAtLeast(0))
        _previewTimeMs.value = _previewTimeMs.value.coerceIn(0, _sequence.value.durationMs)
        refreshPreview()
    }

    private fun refreshPreview() {
        _previewPose.value = _sequence.value.sampleAtMillis(_previewTimeMs.value)
    }

    private companion object {
        /** Where a keyframe appended past the end lands, in milliseconds. */
        const val DEFAULT_GAP_MS = 500

        /** Preview clock step — 20 ms, the device's own motion tick. */
        const val FRAME_MILLIS = 20L

        /** Comfortably inside the device's 500 ms streamed-target timeout. */
        const val DRIVE_RESEND_MILLIS = 100L

        /** What to assume before a connection reports its MTU. */
        const val DEFAULT_MTU = 247
    }
}
