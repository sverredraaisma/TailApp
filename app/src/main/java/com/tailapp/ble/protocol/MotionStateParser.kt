package com.tailapp.ble.protocol

import com.tailapp.model.BehaviorReason
import com.tailapp.model.BehaviorRuntime
import com.tailapp.model.MotionState
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MotionStateParser {

    fun parse(data: ByteArray): MotionState? {
        if (data.size < Protocol.MOTION_STATE_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val patternId = buf.get()
        val params = List(8) { buf.float }
        val encoders = List(4) { buf.float }
        val gx = buf.float
        val gy = buf.float
        val gz = buf.float
        val xMin = buf.float
        val xMax = buf.float
        val yMin = buf.float
        val yMax = buf.float

        // The MOT-6 behavior block is appended, so its absence is a firmware
        // that predates the engine rather than a truncated payload — and
        // "unknown" has to stay distinguishable from "the engine is off".
        val behavior = if (data.size >= Protocol.MOTION_STATE_WITH_BEHAVIOR_SIZE) {
            val stateIndex = buf.get().toInt() and 0xFF
            val reason = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val drivingPattern = buf.get()
            BehaviorRuntime(
                stateIndex = stateIndex.takeIf { it != BehaviorRuntime.ABSENT },
                reason = BehaviorReason(reason),
                flags = flags,
                drivingPatternId = drivingPattern.takeIf {
                    it.toInt() and 0xFF != BehaviorRuntime.ABSENT
                }
            )
        } else {
            null
        }

        return MotionState(
            activePatternId = patternId,
            params = params,
            encoderPositions = encoders,
            gravityX = gx, gravityY = gy, gravityZ = gz,
            xAxisMin = xMin, xAxisMax = xMax,
            yAxisMin = yMin, yAxisMax = yMax,
            behavior = behavior
        )
    }
}
