package com.tailapp.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MotionCommands {

    fun selectPattern(patternId: Byte): ByteArray =
        byteArrayOf(0x01, patternId)

    fun setPatternParam(paramId: Byte, value: Float): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x02)
        buf.put(paramId)
        buf.putFloat(value)
        return buf.array()
    }

    fun setServoConfig(servoId: Byte, axis: Byte, half: Byte, invert: Byte): ByteArray =
        byteArrayOf(0x03, servoId, axis, half, invert)

    fun setPidGains(servoId: Byte, kp: Float, ki: Float, kd: Float): ByteArray {
        val buf = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x04)
        buf.put(servoId)
        buf.putFloat(kp)
        buf.putFloat(ki)
        buf.putFloat(kd)
        return buf.array()
    }

    fun calibrateZero(): ByteArray = byteArrayOf(0x05)

    fun setAxisLimits(axis: Byte, min: Float, max: Float): ByteArray {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x06)
        buf.put(axis)
        buf.putFloat(min)
        buf.putFloat(max)
        return buf.array()
    }

    fun setImuTap(imuId: Byte, enabled: Boolean): ByteArray =
        byteArrayOf(0x07, imuId, if (enabled) 1 else 0)
}
