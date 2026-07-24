package com.tailapp.testutil

import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.led.PixelBuffer
import com.tailapp.lighting.LightingOutput

/**
 * A [LightingOutput] that records everything it is handed.
 *
 * Frames are copied on arrival — the renderer reuses its buffer, so keeping the
 * references would leave every recorded frame pointing at the same pixels and
 * make the assertions meaningless.
 */
class RecordingLightingOutput : LightingOutput {
    val frames = mutableListOf<PixelBuffer>()
    val frameTimestamps = mutableListOf<Long>()
    val beats = mutableListOf<BeatEvent>()
    val drops = mutableListOf<DropEvent>()

    var openedWithLedCount: Int? = null
    var closed = false

    override suspend fun open(ledCount: Int) {
        openedWithLedCount = ledCount
    }

    override suspend fun close() {
        closed = true
    }

    override fun onFrame(frame: PixelBuffer, timestampNanos: Long) {
        frames.add(frame.copy())
        frameTimestamps.add(timestampNanos)
    }

    override fun onBeat(event: BeatEvent) {
        beats.add(event)
    }

    override fun onDrop(event: DropEvent) {
        drops.add(event)
    }

    fun clear() {
        frames.clear()
        frameTimestamps.clear()
        beats.clear()
        drops.clear()
    }
}
