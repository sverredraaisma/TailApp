package com.tailapp.lighting

import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.led.PixelBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors rendered frames into state the UI can observe, so the monitoring
 * screen shows exactly what is being sent to the tail — including when no tail
 * is connected, which is what makes the effect profiles tunable at a desk.
 *
 * Frames are copied on the way in: the renderer reuses its buffer, and Compose
 * reads it on another frame entirely.
 */
class PreviewLightingOutput : LightingOutput {

    private val _frame = MutableStateFlow<PixelBuffer?>(null)
    val frame: StateFlow<PixelBuffer?> = _frame.asStateFlow()

    private val _lastBeat = MutableStateFlow<BeatEvent?>(null)
    val lastBeat: StateFlow<BeatEvent?> = _lastBeat.asStateFlow()

    private val _lastDrop = MutableStateFlow<DropEvent?>(null)
    val lastDrop: StateFlow<DropEvent?> = _lastDrop.asStateFlow()

    override fun onFrame(frame: PixelBuffer, timestampNanos: Long) {
        _frame.value = frame.copy()
    }

    override fun onBeat(event: BeatEvent) {
        _lastBeat.value = event
    }

    override fun onDrop(event: DropEvent) {
        _lastDrop.value = event
    }
}
