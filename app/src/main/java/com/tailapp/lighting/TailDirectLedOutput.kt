package com.tailapp.lighting

import android.util.Log
import com.tailapp.led.PixelBuffer
import com.tailapp.repository.DeviceRepository

/**
 * Streams rendered frames to the tail over FF0A.
 *
 * [open] puts the device in direct mode, which bypasses its effect stack
 * entirely; [close] hands rendering back to it. The firmware also reverts on
 * disconnect, so a dropped connection cannot leave the tail frozen on the last
 * frame we sent.
 *
 * Identical consecutive frames are skipped. FF0A writes are unacknowledged, so
 * the saving is real BLE airtime rather than just CPU — but for the same reason
 * a skipped frame can never be *confirmed* to have arrived, so an unchanged
 * frame is still resent every [keepaliveMillis] in case the last one was lost.
 *
 * @param repository the connected device.
 * @param keepaliveMillis how often an unchanged frame is resent anyway.
 */
class TailDirectLedOutput(
    private val repository: DeviceRepository,
    private val keepaliveMillis: Long = 1000L
) : LightingOutput {

    private var lastFrame: ByteArray? = null
    private var lastSentNanos = Long.MIN_VALUE

    override suspend fun open(ledCount: Int) {
        lastFrame = null
        lastSentNanos = Long.MIN_VALUE
        val ok = repository.setDirectMode(true)
        if (!ok) Log.w(TAG, "device did not accept direct mode; frames will be ignored")
    }

    override suspend fun close() {
        repository.setDirectMode(false)
        lastFrame = null
    }

    override fun onFrame(frame: PixelBuffer, timestampNanos: Long) {
        val elapsedMillis = if (lastSentNanos == Long.MIN_VALUE) {
            Long.MAX_VALUE
        } else {
            (timestampNanos - lastSentNanos) / NANOS_PER_MILLI
        }
        val previous = lastFrame
        if (previous != null && elapsedMillis < keepaliveMillis && previous.contentEquals(frame.bytes)) {
            return
        }

        repository.streamDirectFrame(frame.bytes, frame.ledCount)
        lastSentNanos = timestampNanos
        // Copy: the renderer reuses its buffer, so keeping the reference would
        // compare every future frame against itself and suppress everything.
        lastFrame = frame.bytes.copyOf()
    }

    private companion object {
        const val TAG = "TailDirectLedOutput"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
