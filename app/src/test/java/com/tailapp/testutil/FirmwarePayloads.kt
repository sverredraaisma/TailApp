package com.tailapp.testutil

import com.tailapp.ble.protocol.Protocol
import com.tailapp.model.Capabilities
import com.tailapp.model.ImuConfig
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedOutputState
import com.tailapp.model.MotionLimits
import com.tailapp.model.MotionSystemState
import com.tailapp.model.PidGains
import com.tailapp.model.ServoConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the exact byte payloads TailFirmware publishes, mirroring
 * `main/app_bridge.cpp::app_update_ble_state`. Encoding the wire format here
 * means a firmware layout change breaks the tests rather than the app at runtime.
 */
object FirmwarePayloads {

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(value: Int) = apply { out.write(value and 0xFF) }
        fun bool(value: Boolean) = u8(if (value) 1 else 0)
        fun f32(value: Float) = apply {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }
        fun bytes(value: ByteArray) = apply { out.write(value) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    val DEFAULT_SERVOS: List<ServoConfig> = listOf(
        ServoConfig(axis = 0, half = 0, invert = false, muxChannel = 0, pid = PidGains(1.0f, 0f, 0f)),
        ServoConfig(axis = 0, half = 1, invert = true, muxChannel = 1, pid = PidGains(2.0f, 0.1f, 0.5f)),
        ServoConfig(axis = 1, half = 0, invert = false, muxChannel = 2, pid = PidGains(3.0f, 0.2f, 1.5f)),
        ServoConfig(axis = 1, half = 1, invert = true, muxChannel = 3, pid = PidGains(4.0f, 0.3f, 2.5f))
    )

    val DEFAULT_IMUS: List<ImuConfig> = listOf(
        ImuConfig(muxChannel = 4, tapEnabled = true),
        ImuConfig(muxChannel = 5, tapEnabled = false)
    )

    /** FF02 motion state — 77 bytes. */
    fun motionState(
        patternId: Byte = 0x01,
        params: List<Float> = List(8) { it.toFloat() },
        encoders: List<Float> = listOf(10f, 20f, 30f, 40f),
        gravity: Triple<Float, Float, Float> = Triple(0f, 0f, 1f),
        xLimits: Pair<Float, Float> = -90f to 90f,
        yLimits: Pair<Float, Float> = -45f to 45f
    ): ByteArray {
        val w = Writer()
        w.u8(patternId.toInt())
        repeat(8) { w.f32(params.getOrElse(it) { 0f }) }
        repeat(4) { w.f32(encoders.getOrElse(it) { 0f }) }
        w.f32(gravity.first).f32(gravity.second).f32(gravity.third)
        w.f32(xLimits.first).f32(xLimits.second)
        w.f32(yLimits.first).f32(yLimits.second)
        return w.toByteArray()
    }

    val DEFAULT_OUTPUT: LedOutputState = LedOutputState(
        brightness = 255,
        gammaEnabled = true,
        currentLimitMa = 0,
        lastPowerScale = 255
    )

    /**
     * FF04 LED state — matrix header, 40 bytes per layer, then the output block.
     * Pass `output = null` for firmware older than protocol v5.
     */
    fun ledState(
        ledsPerRing: List<Int> = listOf(8, 10, 12, 10, 8),
        layers: List<LayerConfig> = emptyList(),
        output: LedOutputState? = DEFAULT_OUTPUT
    ): ByteArray {
        val w = Writer()
        w.u8(ledsPerRing.size)
        ledsPerRing.forEach { w.u8(it) }
        w.u8(layers.size)
        layers.forEach { layer ->
            w.u8(layer.effectId.toInt())
            w.u8(layer.blendMode.toInt())
            w.bool(layer.enabled)
            w.bool(layer.flipX)
            w.bool(layer.flipY)
            w.bool(layer.mirrorX)
            w.bool(layer.mirrorY)
            w.u8(layer.opacity)
            repeat(8) { w.f32(layer.params.getOrElse(it) { 0f }) }
        }
        if (output != null) {
            w.u8(output.brightness)
            w.bool(output.gammaEnabled)
            w.u8(output.currentLimitMa and 0xFF)
            w.u8((output.currentLimitMa shr 8) and 0xFF)
            w.u8(output.lastPowerScale)
        }
        return w.toByteArray()
    }

    fun layer(
        effectId: Byte,
        blendMode: Byte = 0x05,
        enabled: Boolean = true,
        flipX: Boolean = false,
        flipY: Boolean = false,
        mirrorX: Boolean = false,
        mirrorY: Boolean = false,
        params: List<Float> = List(8) { 0f },
        opacity: Int = 255
    ) = LayerConfig(effectId, blendMode, enabled, flipX, flipY, mirrorX, mirrorY, params, opacity)

    val DEFAULT_MOTION: MotionSystemState = MotionSystemState(
        motorsEnabled = true,
        limits = listOf(
            MotionLimits(720f, 3600f, 36000f, stallThreshold = 0),
            MotionLimits(720f, 3600f, 36000f, stallThreshold = 0),
            MotionLimits(360f, 1800f, 18000f, stallThreshold = 60),
            MotionLimits(360f, 1800f, 18000f, stallThreshold = 60)
        )
    )

    /**
     * FF06 system info + capabilities + motion block.
     *
     * Pass `capabilities = null` for pre-capability firmware, or `motion = null`
     * for firmware older than protocol v4. The motion block is only emitted when
     * the capability block is, matching the device's own layout.
     */
    fun systemInfo(
        protocolVersion: Int = Protocol.SUPPORTED_PROTOCOL_VERSION,
        firmwareMajor: Int = 1,
        firmwareMinor: Int = 0,
        firmwarePatch: Int = 0,
        servos: List<ServoConfig> = DEFAULT_SERVOS,
        imus: List<ImuConfig>? = DEFAULT_IMUS,
        capabilities: Capabilities? = Capabilities.DEFAULT,
        motion: MotionSystemState? = DEFAULT_MOTION
    ): ByteArray {
        val w = Writer()
        w.u8(protocolVersion)
        w.u8(firmwareMajor).u8(firmwareMinor).u8(firmwarePatch)
        w.u8(servos.size)
        servos.forEach { servo ->
            w.u8(servo.axis)
            w.u8(servo.half)
            w.bool(servo.invert)
            w.u8(servo.muxChannel)
            w.f32(servo.pid.kp).f32(servo.pid.ki).f32(servo.pid.kd)
        }
        if (imus == null) return w.toByteArray()
        w.u8(imus.size)
        imus.forEach { imu ->
            w.u8(imu.muxChannel)
            w.bool(imu.tapEnabled)
        }
        if (capabilities == null) return w.toByteArray()
        w.u8(capabilities.patternIds.size)
        capabilities.patternIds.forEach { w.u8(it.toInt()) }
        w.u8(capabilities.effectIds.size)
        capabilities.effectIds.forEach { w.u8(it.toInt()) }
        w.u8(capabilities.blendModeIds.size)
        capabilities.blendModeIds.forEach { w.u8(it.toInt()) }
        w.u8(capabilities.maxLayers)
        w.u8(capabilities.maxServos)
        w.u8(capabilities.maxImus)
        w.u8(capabilities.maxLedRings)
        w.u8(capabilities.imageMaxDim)
        if (motion == null) return w.toByteArray()
        w.bool(motion.motorsEnabled)
        repeat(servos.size) { i ->
            val lim = motion.limits.getOrElse(i) { MotionLimits.FIRMWARE_DEFAULT }
            w.f32(lim.maxVelocity).f32(lim.maxAcceleration).f32(lim.maxJerk)
            w.u8(lim.stallThreshold)
        }
        return w.toByteArray()
    }

    /** FF07 read payload — the device's recent-event ring, `[count][event]...`. */
    fun eventLog(events: List<Int>): ByteArray {
        val w = Writer()
        w.u8(events.size)
        events.forEach { w.u8(it) }
        return w.toByteArray()
    }

    /** FF08 profile list — `[occupied][name_len][name]` per slot, variable stride. */
    fun profileList(entries: List<Pair<Boolean, String?>>): ByteArray {
        val w = Writer()
        entries.forEach { (occupied, name) ->
            val nameBytes = name?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            w.bool(occupied)
            w.u8(nameBytes.size)
            w.bytes(nameBytes)
        }
        return w.toByteArray()
    }

    /**
     * FF09 command result. Pass `sequence = null` to build the 3-byte payload
     * pre-v5 firmware sends, so the parser's tolerance of it stays covered.
     */
    fun commandResult(
        characteristicLowByte: Int,
        commandId: Int,
        result: Int,
        sequence: Int? = 0
    ): ByteArray {
        val w = Writer()
        w.u8(characteristicLowByte).u8(commandId).u8(result)
        if (sequence != null) w.u8(sequence)
        return w.toByteArray()
    }
}
