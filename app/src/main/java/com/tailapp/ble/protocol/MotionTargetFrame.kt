package com.tailapp.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the FF0B live-motion-target frame: four little-endian floats, in the
 * same order FF02 reports positions (axis 0 first/second half, then axis 1).
 *
 * No command id — every byte is payload, like FF0A. The device clamps the
 * targets to its own axis limits and shapes them through its jerk-limited
 * profiles, so the app does not need to know the mechanism's travel or speed.
 */
object MotionTargetFrame {

    /** Bytes on the wire; the device rejects anything shorter. */
    const val SIZE = 16

    fun build(targets: FloatArray): ByteArray {
        require(targets.size >= 4) { "need four half-axis targets, got ${targets.size}" }
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) buf.putFloat(targets[i])
        return buf.array()
    }
}
