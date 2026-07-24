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

    /**
     * FF02 motion state — 77 bytes, or 81 with the MOT-6 behavior block.
     *
     * `behavior = null` is firmware that predates the engine: the block is
     * appended, so those devices send exactly what they always did.
     */
    fun motionState(
        patternId: Byte = 0x01,
        params: List<Float> = List(8) { it.toFloat() },
        encoders: List<Float> = listOf(10f, 20f, 30f, 40f),
        gravity: Triple<Float, Float, Float> = Triple(0f, 0f, 1f),
        xLimits: Pair<Float, Float> = -90f to 90f,
        yLimits: Pair<Float, Float> = -45f to 45f,
        behavior: BehaviorBlock? = null
    ): ByteArray {
        val w = Writer()
        w.u8(patternId.toInt())
        repeat(8) { w.f32(params.getOrElse(it) { 0f }) }
        repeat(4) { w.f32(encoders.getOrElse(it) { 0f }) }
        w.f32(gravity.first).f32(gravity.second).f32(gravity.third)
        w.f32(xLimits.first).f32(xLimits.second)
        w.f32(yLimits.first).f32(yLimits.second)
        if (behavior != null) {
            w.u8(behavior.stateIndex)
            w.u8(behavior.reason)
            w.u8(behavior.flags)
            w.u8(behavior.drivingPatternId)
        }
        return w.toByteArray()
    }

    /**
     * The four appended behavior bytes, as raw values: `0xFF` is the device's
     * "engine off" / "not driving" sentinel and has to stay expressible.
     */
    data class BehaviorBlock(
        val stateIndex: Int = 0xFF,
        val reason: Int = 0x00,
        val flags: Int = 0x00,
        val drivingPatternId: Int = 0xFF
    )

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
     * The FF06 OTA version/rollback block (SYS-2): running version, whether the
     * running image is unconfirmed, and the other slot's version. The defaults
     * reproduce the bytes a freshly-flashed, confirmed device publishes.
     */
    data class OtaBlock(
        val running: Triple<Int, Int, Int> = Triple(1, 0, 0),
        val pendingVerify: Boolean = false,
        val other: Triple<Int, Int, Int>? = null
    )

    /** One `[addr_type][addr 6 B]` bond record, as the FF06 identity block carries it. */
    data class BondRecord(
        val addressType: Int = 1,
        /** Wire order — NimBLE hands out `ble_addr_t.val` least-significant byte first. */
        val address: List<Int> = listOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
    )

    /**
     * FF06 system info + capabilities + motion block, and optionally the SYS-6
     * identity block.
     *
     * Pass `capabilities = null` for pre-capability firmware, or `motion = null`
     * for firmware older than protocol v4. The motion block is only emitted when
     * the capability block is, matching the device's own layout.
     *
     * `deviceName` non-null emits the identity block — and with it the four
     * blocks the device puts in front of it (motion tuning, OTA version/rollback,
     * per-IMU tap config, axis mix), because on a real device the identity block
     * only exists on firmware that already publishes all of them, and it is only
     * findable by walking their exact lengths.
     */
    fun systemInfo(
        protocolVersion: Int = Protocol.SUPPORTED_PROTOCOL_VERSION,
        firmwareMajor: Int = 1,
        firmwareMinor: Int = 0,
        firmwarePatch: Int = 0,
        servos: List<ServoConfig> = DEFAULT_SERVOS,
        imus: List<ImuConfig>? = DEFAULT_IMUS,
        capabilities: Capabilities? = Capabilities.DEFAULT,
        motion: MotionSystemState? = DEFAULT_MOTION,
        deviceName: String? = null,
        bonds: List<BondRecord> = emptyList(),
        ota: OtaBlock = OtaBlock()
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
        if (deviceName == null) return w.toByteArray()
        w.unmodelledBlocks(servos.size, imus.size, ota)
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        w.u8(nameBytes.size)
        w.bytes(nameBytes)
        w.u8(bonds.size)
        bonds.forEach { bond ->
            w.u8(bond.addressType)
            bond.address.forEach { w.u8(it) }
        }
        return w.toByteArray()
    }

    /**
     * The blocks the device emits between the motion block and the identity
     * block, at their real lengths: motion tuning, the OTA version/rollback
     * block, per-IMU tap config, and the axis mix.
     *
     * Nothing in the app reads them. They are here because the identity block
     * after them cannot be located without walking them, so a length that
     * disagreed with the firmware would silently move the bond list.
     */
    private fun Writer.unmodelledBlocks(numServos: Int, numImus: Int, ota: OtaBlock) {
        repeat(numServos) { f32(1f) }                  // units per deg/s
        f32(0.5f)                                      // gentle scale
        u8(0).u8(0)                                    // keyframe slot, sequence occupancy
        // OTA version/rollback block (OTA_INFO_BLOCK_SIZE = 8).
        u8(ota.running.first).u8(ota.running.second).u8(ota.running.third)
        bool(ota.pendingVerify)
        val other = ota.other
        bool(other != null)
        u8(other?.first ?: 0).u8(other?.second ?: 0).u8(other?.third ?: 0)
        repeat(numImus) { u8(1).u8(40).u8(2).u8(50).u8(0) }
        f32(0f).f32(1f).f32(1f).u8(0).u8(0)            // axis mix: identity
    }

    /**
     * The standard Battery Level read (0x2A19). `percent = null` builds the
     * device's `BATTERY_PERCENT_UNKNOWN` sentinel, which has to stay
     * distinguishable from a flat pack.
     */
    fun batteryLevel(percent: Int?): ByteArray =
        byteArrayOf((percent ?: Protocol.BATTERY_PERCENT_UNKNOWN).toByte())

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
     * A byte buffer shaped like the head of an ESP-IDF application image, so both
     * `FirmwareImage.describe` and the simulated tail's OTA path read a real
     * project name and version out of it.
     *
     * The offsets mirror `OtaManager`'s: magic at 0, the app-descriptor magic at
     * 32, `version[32]` at 48 and `project_name[32]` at 80. Padded to [sizeBytes]
     * with a repeating pattern so a whole-image CRC is a function of the size.
     * The defaults produce an image the device accepts; the [magic],
     * [descriptorMagic], [projectName] and [version] parameters each let one check
     * be failed on purpose.
     */
    fun firmwareImage(
        version: String = "1.2.3",
        projectName: String = "TailFirmware",
        sizeBytes: Int = 1024,
        magic: Int = 0xE9,
        descriptorMagic: Long = 0xABCD5432L
    ): ByteArray {
        val bytes = ByteArray(maxOf(sizeBytes, Protocol.OTA_IMAGE_HEADER_BYTES))
        // A non-zero fill so padding bytes vary; the header fields are stamped over it.
        for (i in bytes.indices) bytes[i] = (i and 0x7F).toByte()

        bytes[0] = magic.toByte()
        bytes[1] = 0x00; bytes[2] = 0x00; bytes[3] = 0x00
        writeU32(bytes, APP_DESC_OFFSET, descriptorMagic)
        writeFixedString(bytes, DESC_VERSION_OFFSET, version)
        writeFixedString(bytes, DESC_PROJECT_OFFSET, projectName)
        return bytes
    }

    private const val APP_DESC_OFFSET = 32
    private const val DESC_VERSION_OFFSET = APP_DESC_OFFSET + 16
    private const val DESC_PROJECT_OFFSET = APP_DESC_OFFSET + 48
    private const val DESC_FIELD_LEN = 32

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /** Fixed-width descriptor string: written then NUL-padded to the field width. */
    private fun writeFixedString(bytes: ByteArray, offset: Int, value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        for (i in 0 until DESC_FIELD_LEN) {
            bytes[offset + i] = if (i < encoded.size) encoded[i] else 0
        }
    }

    /** FF0E status echo — `[accepted u32 LE][state u8][result u8]`. */
    fun otaStatus(accepted: Int, state: Int, result: Int = 0x00): ByteArray {
        val w = Writer()
        w.u8(accepted and 0xFF)
        w.u8((accepted shr 8) and 0xFF)
        w.u8((accepted shr 16) and 0xFF)
        w.u8((accepted shr 24) and 0xFF)
        w.u8(state)
        w.u8(result)
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
