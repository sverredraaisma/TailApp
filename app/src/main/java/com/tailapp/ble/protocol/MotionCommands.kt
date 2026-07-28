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

    // Command id `0x04` (Set PID gains) is retired as of protocol v6 and never
    // reused: the motors run open-loop, so the gains never shaped motion, and a
    // write of `0x04` now answers `UNKNOWN_CMD`. Motion is shaped by
    // [setMotionLimits] instead.

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
     * These are what shape motion now that the steppers run open-loop — the PID
     * gains they replaced are retired as of protocol v6. A limit of `0` means
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
     * `0x0A` Per-motor command scale: velocity-command units per deg/s (MOT-2).
     *
     * The calibration that turns a degrees-per-second profile into whatever the
     * driver actually counts. The device already *reports* this in the FF06
     * tuning block, so this is the write side of a number the app has been able
     * to display all along.
     *
     * The firmware rejects anything outside
     * [Protocol.MOTOR_SCALE_MIN]..[Protocol.MOTOR_SCALE_MAX] — and NaN with it —
     * rather than clamping: zero would round every velocity command to zero and
     * read as a dead motor. [motorScaleError] is what the UI calls first, so the
     * limit is explained where it can be instead of arriving as an FF09
     * `OUT_OF_RANGE`.
     */
    fun setMotorScale(servoId: Byte, unitsPerDegPerSec: Float): ByteArray {
        require(motorScaleError(unitsPerDegPerSec) == null) {
            "motor scale must be ${Protocol.MOTOR_SCALE_MIN}..${Protocol.MOTOR_SCALE_MAX}, " +
                "was $unitsPerDegPerSec"
        }
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x0A)
        buf.put(servoId)
        buf.putFloat(unitsPerDegPerSec)
        return buf.array()
    }

    /** Why the device would refuse [scale] for [setMotorScale], or null if it would take it. */
    fun motorScaleError(scale: Float): String? =
        rangeError(scale, Protocol.MOTOR_SCALE_MIN, Protocol.MOTOR_SCALE_MAX, "Motor scale")

    /**
     * `0x0B` Global speed multiplier on every motor's velocity and acceleration
     * limit (MOT-4). `1.0` is full speed; the floor is where the profile still
     * overcomes stiction.
     *
     * Reported in the FF06 tuning block as `gentleScale`. Above `1.0` this would
     * raise limits past what the app configured per motor, which is not what
     * "gentle" means, so the firmware refuses it.
     */
    fun setGentleScale(scale: Float): ByteArray {
        require(gentleScaleError(scale) == null) {
            "gentle scale must be ${Protocol.GENTLE_SCALE_MIN}..${Protocol.GENTLE_SCALE_MAX}, " +
                "was $scale"
        }
        val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x0B)
        buf.putFloat(scale)
        return buf.array()
    }

    /** Why the device would refuse [scale] for [setGentleScale], or null if it would take it. */
    fun gentleScaleError(scale: Float): String? =
        rangeError(scale, Protocol.GENTLE_SCALE_MIN, Protocol.GENTLE_SCALE_MAX, "Gentle scale")

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

    /**
     * `0x14` Tune one IMU's tap detector (MOT-10):
     * `[imu_id][threshold u8][sensitivity u8][quiet_time_ms u16 LE]`.
     *
     * [threshold] and [quietTimeMs] are both *0-means-default*, the way the
     * motion limits are, so an app that wants to change only one of them can
     * leave the other at 0 rather than having to know the firmware's number.
     * That is why 0 is the one value below the quiet-time floor that is not a
     * mistake.
     *
     * [sensitivity] is 0..[Protocol.TAP_SENSITIVITY_MAX], 0 being least
     * sensitive. Whether these land on the BMI270's own tap feature or the
     * software fallback is reported back in the FF06 tap block — a device that
     * had quietly degraded to the fallback would otherwise look like a tuning
     * problem forever.
     */
    fun setTapConfig(
        imuId: Byte,
        threshold: Int,
        sensitivity: Int,
        quietTimeMs: Int
    ): ByteArray {
        require(tapConfigError(threshold, sensitivity, quietTimeMs) == null) {
            "tap config out of range: threshold=$threshold sensitivity=$sensitivity " +
                "quiet=$quietTimeMs"
        }
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x14)
        buf.put(imuId)
        buf.put(threshold.toByte())
        buf.put(sensitivity.toByte())
        buf.putShort(quietTimeMs.toShort())
        return buf.array()
    }

    /**
     * Why the device would refuse a [setTapConfig], or null if it would take it.
     * The zero exemptions are the firmware's, not a convenience: `0` means "keep
     * the default", so it must pass even though it is below the quiet-time floor.
     */
    fun tapConfigError(threshold: Int, sensitivity: Int, quietTimeMs: Int): String? = when {
        threshold !in 0..255 -> "Tap threshold must be 0-255 (0 keeps the device default)."
        sensitivity !in 0..Protocol.TAP_SENSITIVITY_MAX ->
            "Tap sensitivity must be 0-${Protocol.TAP_SENSITIVITY_MAX}."
        quietTimeMs == 0 -> null
        quietTimeMs < Protocol.TAP_QUIET_TIME_MIN_MS || quietTimeMs > Protocol.TAP_QUIET_TIME_MAX_MS ->
            "Tap quiet time must be ${Protocol.TAP_QUIET_TIME_MIN_MS}-" +
                "${Protocol.TAP_QUIET_TIME_MAX_MS} ms, or 0 to keep the device default."
        else -> null
    }

    /**
     * `0x15` Set the axis mix (MOT-0):
     * `[rotation_deg f32][gain_x f32][gain_y f32][invert_x u8][invert_y u8]`.
     *
     * The map from the logical left/right and up/down every pattern works in to
     * the two physical axes a diagonal mechanism actually has. At 45° a pure +Y
     * drives both axes the same way and a pure +X drives them opposite ways.
     *
     * Since this landed, `MCMD_SET_AXIS_LIMITS` bounds *physical* travel, because
     * clamping happens after the mix — so a rotated mix changes what the limits
     * mean. The gains are bound away from zero in both directions
     * ([Protocol.AXIS_MIX_GAIN_MIN]): a zero gain collapses a logical axis onto
     * nothing and makes the mix non-invertible, and FF02 could then no longer
     * report a logical position at all. An invalid mix is rejected, never
     * repaired to identity — a silently-replaced mix would leave the app showing
     * a rotation the tail is not using, and the tail is the thing with end stops.
     */
    fun setAxisMix(
        rotationDeg: Float,
        gainX: Float,
        gainY: Float,
        invertX: Boolean,
        invertY: Boolean
    ): ByteArray {
        require(axisMixError(rotationDeg, gainX, gainY) == null) {
            "axis mix out of range: rotation=$rotationDeg gain=($gainX, $gainY)"
        }
        val buf = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x15)
        buf.putFloat(rotationDeg)
        buf.putFloat(gainX)
        buf.putFloat(gainY)
        buf.put(if (invertX) 1.toByte() else 0.toByte())
        buf.put(if (invertY) 1.toByte() else 0.toByte())
        return buf.array()
    }

    /** Why the device would refuse a [setAxisMix] — mirrors `AxisMixer::is_valid`. */
    fun axisMixError(rotationDeg: Float, gainX: Float, gainY: Float): String? =
        rangeError(
            rotationDeg, -Protocol.AXIS_MIX_ROTATION_MAX, Protocol.AXIS_MIX_ROTATION_MAX,
            "Mix rotation (degrees)"
        ) ?: rangeError(
            gainX, Protocol.AXIS_MIX_GAIN_MIN, Protocol.AXIS_MIX_GAIN_MAX, "X gain"
        ) ?: rangeError(
            gainY, Protocol.AXIS_MIX_GAIN_MIN, Protocol.AXIS_MIX_GAIN_MAX, "Y gain"
        )

    /**
     * `0x16` Configure encoder assist (MOT-1):
     * `[enabled u8][correction_rate f32][rehome_threshold_deg f32]`.
     *
     * The AS5600s *correct* the dead-reckoned position and re-home it; they do
     * not close a loop, and these bounds are where that distinction is enforced
     * on the wire. [correctionRate] is a first-order decay constant in 1/s, so
     * `1/rate` is how long a disagreement takes to shrink by 63 % — the ceiling
     * of 1.0 puts that at one second, past which correction starts competing with
     * the profile inside a single move. The firmware additionally caps the
     * correction in deg/s, and that cap is deliberately *not* configurable.
     *
     * [rehomeThresholdDeg] is the disagreement at which the firmware stops
     * correcting and simply adopts the encoder's reading.
     */
    fun setEncoderConfig(
        enabled: Boolean,
        correctionRate: Float,
        rehomeThresholdDeg: Float
    ): ByteArray {
        require(encoderConfigError(correctionRate, rehomeThresholdDeg) == null) {
            "encoder config out of range: rate=$correctionRate rehome=$rehomeThresholdDeg"
        }
        val buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x16)
        buf.put(if (enabled) 1.toByte() else 0.toByte())
        buf.putFloat(correctionRate)
        buf.putFloat(rehomeThresholdDeg)
        return buf.array()
    }

    /** Why the device would refuse a [setEncoderConfig], or null if it would take it. */
    fun encoderConfigError(correctionRate: Float, rehomeThresholdDeg: Float): String? =
        rangeError(
            correctionRate, Protocol.ENCODER_RATE_MIN, Protocol.ENCODER_RATE_MAX,
            "Encoder correction rate"
        ) ?: rangeError(
            rehomeThresholdDeg, Protocol.ENCODER_REHOME_MIN_DEG, Protocol.ENCODER_REHOME_MAX_DEG,
            "Encoder re-home threshold"
        )

    /**
     * The firmware's `!(x >= min) || x > max` test, which rejects NaN as well —
     * a plain `x < min` waves it through into every command the value shapes.
     */
    private fun rangeError(value: Float, min: Float, max: Float, label: String): String? =
        if (!(value >= min) || value > max) "$label must be $min-$max." else null
}
