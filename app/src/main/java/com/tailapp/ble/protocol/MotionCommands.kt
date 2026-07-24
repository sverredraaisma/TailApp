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

    /**
     * `0x03` Set servo configuration.
     *
     * [muxChannel] is the optional 6th byte added in protocol v1 — it sets the
     * encoder's I2C mux channel. Omit it to leave the channel untouched.
     */
    fun setServoConfig(
        servoId: Byte,
        axis: Byte,
        half: Byte,
        invert: Byte,
        muxChannel: Byte? = null
    ): ByteArray = if (muxChannel == null) {
        byteArrayOf(0x03, servoId, axis, half, invert)
    } else {
        byteArrayOf(0x03, servoId, axis, half, invert, muxChannel)
    }

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

    /**
     * `0x08` Set the open-loop motion limits and StallGuard threshold for one
     * motor (protocol v4).
     *
     * These are what actually shape motion now that the steppers run open-loop —
     * [setPidGains] is retained only for wire compatibility. A limit of `0` means
     * "keep the firmware default" rather than "don't move".
     *
     * [stallThreshold] is the TMC2209 SGTHRS value; `0` disables stall detection
     * for this motor. It has to be tuned against the real mechanics — too high
     * and the tail freewheels at rest, too low and a real jam is never caught.
     */
    fun setMotionLimits(
        servoId: Byte,
        maxVelocity: Float,
        maxAcceleration: Float,
        maxJerk: Float,
        stallThreshold: Byte
    ): ByteArray {
        val buf = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x08)
        buf.put(servoId)
        buf.putFloat(maxVelocity)
        buf.putFloat(maxAcceleration)
        buf.putFloat(maxJerk)
        buf.put(stallThreshold)
        return buf.array()
    }

    /**
     * `0x09` Re-energize the motors and clear a stall latch, or force freewheel
     * (protocol v4).
     *
     * After a stall the firmware latches every motor off; nothing moves again
     * until this is sent (or a calibrate/pattern-select, which also clear it).
     */
    fun enableMotors(enabled: Boolean): ByteArray =
        byteArrayOf(0x09, if (enabled) 1 else 0)
}
