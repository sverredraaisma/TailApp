package com.tailapp.ble.protocol

import com.tailapp.model.MotionState
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MotionStateParser {

    fun parse(data: ByteArray): MotionState? {
        if (data.size < 77) return null
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

        return MotionState(
            activePatternId = patternId,
            params = params,
            encoderPositions = encoders,
            gravityX = gx, gravityY = gy, gravityZ = gz,
            xAxisMin = xMin, xAxisMax = xMax,
            yAxisMin = yMin, yAxisMax = yMax
        )
    }
}
