package com.tailapp.ble.protocol

import com.tailapp.model.BehaviorStateConfig
import com.tailapp.model.BehaviorTriggerConfig
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

    /**
     * `0x0C` Begin a keyframe-sequence upload (MOT-8).
     *
     * Clears the device's staging buffer and arms the length + CRC-32 check that
     * [finalizeSequence] verifies, exactly as [LedCommands.beginImage] does for
     * an image — the same transfer shape deliberately, so there is one set of
     * failure modes to understand rather than two.
     *
     * Unlike an image chunk, a sequence chunk that arrives with nothing armed is
     * refused outright (`BAD_STATE`): there is no legacy chunks-only flow to
     * preserve, and unverified poses have no business reaching flash.
     */
    fun beginSequence(slot: Byte, totalLength: Int, crc32: Int): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x0C)
        buf.put(slot)
        buf.putShort(totalLength.toShort())
        buf.putInt(crc32)
        return buf.array()
    }

    /** `0x0D` One slice of the sequence blob at a u16 offset from its start. */
    fun uploadSequenceChunk(offset: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(3 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x0D)
        buf.putShort(offset.toShort())
        buf.put(data)
        return buf.array()
    }

    /**
     * `0x0E` Verify the uploaded blob against what [beginSequence] armed and
     * commit it to flash.
     *
     * The device also parses the blob before storing it, so a structurally
     * malformed sequence is refused here rather than at the next boot. Both
     * rejections arrive on FF09 as `BAD_STATE`.
     */
    fun finalizeSequence(slot: Byte): ByteArray = byteArrayOf(0x0E, slot)

    /**
     * `0x0F` Choose which stored sequence `PATTERN_KEYFRAME` replays.
     *
     * Selecting an empty or unplayable slot is refused with `BAD_STATE`, and
     * selecting a slot while the keyframe pattern is already running swaps the
     * poses under it.
     */
    fun selectSequence(slot: Byte): ByteArray = byteArrayOf(0x0F, slot)

    /**
     * `0x10` Arm or disarm the behavior engine (MOT-6).
     *
     * Enabling always enters the table's idle state, so "turn it on" lands
     * somewhere known rather than resuming whatever mood the tail was in when it
     * was last switched off. Unlike LED direct mode this setting is persisted on
     * the device: a mood is what the tail does when no app is there to enable
     * anything.
     */
    fun setBehaviorEnabled(enabled: Boolean): ByteArray =
        byteArrayOf(0x10, if (enabled) 1 else 0)

    /**
     * `0x11` Enter a state immediately, ignoring the current state's minimum
     * dwell — the editor's preview.
     *
     * Not a mode: the machine keeps running from there, so a forced state decays
     * through its own timeout and triggers like any other.
     */
    fun setBehaviorState(stateIndex: Byte): ByteArray =
        byteArrayOf(0x11, stateIndex)

    /** `0x12` Write one state record: `[index][40-byte state record]`. */
    fun setBehaviorConfig(stateIndex: Byte, state: BehaviorStateConfig): ByteArray =
        byteArrayOf(0x12, stateIndex) + BehaviorRecords.encodeState(state)

    /** `0x13` Write one trigger row: `[index][16-byte trigger record]`. */
    fun setBehaviorTrigger(triggerIndex: Byte, trigger: BehaviorTriggerConfig): ByteArray =
        byteArrayOf(0x13, triggerIndex) + BehaviorRecords.encodeTrigger(trigger)
}
