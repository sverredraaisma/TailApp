package com.tailapp.testutil

import com.tailapp.ble.protocol.Protocol
import com.tailapp.model.Capabilities
import com.tailapp.model.ImuConfig
import com.tailapp.model.LayerConfig
import com.tailapp.model.LedOutputState
import com.tailapp.model.MotionLimits
import com.tailapp.model.MotionSystemState
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
        fun u16(value: Int) = apply { u8(value); u8(value shr 8) }
        fun u32(value: Long) = apply {
            u8((value and 0xFF).toInt()); u8((value shr 8).toInt())
            u8((value shr 16).toInt()); u8((value shr 24).toInt())
        }
        fun bool(value: Boolean) = u8(if (value) 1 else 0)
        fun f32(value: Float) = apply {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }
        fun bytes(value: ByteArray) = apply { out.write(value) }

        /**
         * Writes a v6 FF06 framed block: `[tag][len u16 LE][payload]`. The
         * payload is built into its own buffer so its length is known before the
         * prefix — the same shape `app_bridge.cpp` writes, and what lets the
         * parser locate a block by tag and skip one it does not model by `len`.
         */
        fun block(tag: Int, build: Writer.() -> Unit) = apply {
            val payload = Writer().apply(build).toByteArray()
            u8(tag).u16(payload.size).bytes(payload)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    val DEFAULT_SERVOS: List<ServoConfig> = listOf(
        ServoConfig(axis = 0, half = 0, invert = false, muxChannel = 0),
        ServoConfig(axis = 0, half = 1, invert = true, muxChannel = 1),
        ServoConfig(axis = 1, half = 0, invert = false, muxChannel = 2),
        ServoConfig(axis = 1, half = 1, invert = true, muxChannel = 3)
    )

    // FF06_BLK_* tags — the v6 framing prefix for each trailing block, from
    // TailFirmware `main/ble/ble_protocol.h`.
    private const val FF06_BLK_CAPABILITIES = 0x01
    private const val FF06_BLK_MOTION = 0x02
    private const val FF06_BLK_TUNING = 0x03
    private const val FF06_BLK_OTA = 0x04
    private const val FF06_BLK_TAP = 0x05
    private const val FF06_BLK_AXIS_MIX = 0x06
    private const val FF06_BLK_IDENTITY = 0x07

    val DEFAULT_IMUS: List<ImuConfig> = listOf(
        ImuConfig(muxChannel = 4, tapEnabled = true),
        ImuConfig(muxChannel = 5, tapEnabled = false)
    )

    /**
     * FF02 motion state. The current firmware's `MOTION_STATE_SIZE` is **97
     * bytes**: the 77-byte v5 core, the 4-byte MOT-6 behavior block, then the
     * 16-byte MOT-0 logical-position block.
     *
     * Both trailing blocks are appended rather than inserted, which is why the
     * shorter forms are still worth building here: `behavior = null` is firmware
     * that predates the engine and sends the 77 bytes it always did, and
     * `logical = null` with a behavior block is the 81-byte intermediate.
     */
    fun motionState(
        patternId: Byte = 0x01,
        params: List<Float> = List(8) { it.toFloat() },
        encoders: List<Float> = listOf(10f, 20f, 30f, 40f),
        gravity: Triple<Float, Float, Float> = Triple(0f, 0f, 1f),
        xLimits: Pair<Float, Float> = -90f to 90f,
        yLimits: Pair<Float, Float> = -45f to 45f,
        behavior: BehaviorBlock? = null,
        logical: List<Float>? = null
    ): ByteArray {
        val w = Writer()
        w.u8(patternId.toInt())
        repeat(8) { w.f32(params.getOrElse(it) { 0f }) }
        repeat(4) { w.f32(encoders.getOrElse(it) { 0f }) }
        w.f32(gravity.first).f32(gravity.second).f32(gravity.third)
        w.f32(xLimits.first).f32(xLimits.second)
        w.f32(yLimits.first).f32(yLimits.second)
        // The logical block (MOT-0) sits after the behavior block (MOT-6), so a
        // device that reports logical positions also reports the behavior block.
        val beh = behavior ?: if (logical != null) BehaviorBlock() else null
        if (beh != null) {
            w.u8(beh.stateIndex)
            w.u8(beh.reason)
            w.u8(beh.flags)
            w.u8(beh.drivingPatternId)
        }
        if (logical != null) {
            repeat(4) { w.f32(logical.getOrElse(it) { 0f }) }
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
        val other: Triple<Int, Int, Int>? = null,
        /**
         * Bit 1 of the flags byte: the running triplet is a zero placeholder the
         * device could not read from a real descriptor. Set it *without*
         * [pendingVerify] to reproduce the payload that used to be misread as an
         * unconfirmed image, since the old parser took the whole byte as a bool.
         */
        val runningVersionUnknown: Boolean = false
    ) {
        /** The packed `flags` byte, as `OtaManager::build_info_block` writes it. */
        val flags: Int
            get() = (if (pendingVerify) 0x01 else 0) or (if (runningVersionUnknown) 0x02 else 0)
    }

    /** One `[addr_type][addr 6 B]` bond record, as the FF06 identity block carries it. */
    data class BondRecord(
        val addressType: Int = 1,
        /** Wire order — NimBLE hands out `ble_addr_t.val` least-significant byte first. */
        val address: List<Int> = listOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
    )

    /**
     * FF06 system info: the fixed preamble, then the v6 framed blocks.
     *
     * The preamble carries the 4-byte servo record (assignment only — the PID
     * gains are retired as of v6). Each trailing block is emitted with its
     * `[tag][len]` prefix, so a reader finds it by tag and skips one it does not
     * model by `len`.
     *
     * The gates mirror what an older device would omit rather than the wire
     * order (which v6 no longer fixes): `capabilities = null` stops after the
     * preamble; `motion = null` stops after the capability block; and
     * `deviceName` non-null is what adds the tuning, OTA, tap, axis-mix and
     * identity blocks, because those arrived together on the firmware that first
     * published a name.
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
        ota: OtaBlock = OtaBlock(),
        tuning: TuningBlock = TuningBlock()
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
        }
        if (imus == null) return w.toByteArray()
        w.u8(imus.size)
        imus.forEach { imu ->
            w.u8(imu.muxChannel)
            w.bool(imu.tapEnabled)
        }
        if (capabilities == null) return w.toByteArray()
        w.block(FF06_BLK_CAPABILITIES) {
            u8(capabilities.patternIds.size)
            capabilities.patternIds.forEach { u8(it.toInt()) }
            u8(capabilities.effectIds.size)
            capabilities.effectIds.forEach { u8(it.toInt()) }
            u8(capabilities.blendModeIds.size)
            capabilities.blendModeIds.forEach { u8(it.toInt()) }
            u8(capabilities.maxLayers)
            u8(capabilities.maxServos)
            u8(capabilities.maxImus)
            u8(capabilities.maxLedRings)
            u8(capabilities.imageMaxDim)
        }
        if (motion == null) return w.toByteArray()
        w.block(FF06_BLK_MOTION) {
            bool(motion.motorsEnabled)
            repeat(servos.size) { i ->
                val lim = motion.limits.getOrElse(i) { MotionLimits.FIRMWARE_DEFAULT }
                f32(lim.maxVelocity).f32(lim.maxAcceleration).f32(lim.maxJerk)
                u8(lim.stallThreshold)
            }
        }
        if (deviceName == null) return w.toByteArray()
        w.block(FF06_BLK_TUNING) {
            repeat(servos.size) { i -> f32(tuning.motorScales.getOrElse(i) { 1f }) }
            f32(tuning.gentleScale)
            u8(tuning.keyframeSlot).u8(tuning.sequenceOccupancy)
        }
        w.block(FF06_BLK_OTA) {
            u8(ota.running.first).u8(ota.running.second).u8(ota.running.third)
            u8(ota.flags)
            val other = ota.other
            bool(other != null)
            u8(other?.first ?: 0).u8(other?.second ?: 0).u8(other?.third ?: 0)
        }
        w.block(FF06_BLK_TAP) {
            repeat(imus.size) { u8(1).u8(40).u8(2).u8(50).u8(0) }
        }
        w.block(FF06_BLK_AXIS_MIX) {
            f32(0f).f32(1f).f32(1f).u8(0).u8(0)        // identity mix
        }
        w.block(FF06_BLK_IDENTITY) {
            val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
            u8(nameBytes.size)
            bytes(nameBytes)
            u8(bonds.size)
            bonds.forEach { bond ->
                u8(bond.addressType)
                bond.address.forEach { u8(it) }
            }
        }
        return w.toByteArray()
    }

    /** The motion-tuning block's values; defaults reproduce the original bytes. */
    data class TuningBlock(
        val motorScales: List<Float> = listOf(1f, 1f, 1f, 1f),
        val gentleScale: Float = 0.5f,
        val keyframeSlot: Int = 0,
        val sequenceOccupancy: Int = 0
    )

    /**
     * The standard Battery Level read (0x2A19). `percent = null` builds the
     * device's `BATTERY_PERCENT_UNKNOWN` sentinel, which has to stay
     * distinguishable from a flat pack.
     */
    fun batteryLevel(percent: Int?): ByteArray =
        byteArrayOf((percent ?: Protocol.BATTERY_PERCENT_UNKNOWN).toByte())

    /** One `[failures u16][disabled u8]` health record, for IMUs and encoders alike. */
    data class DiagSensor(val failures: Int = 0, val disabled: Boolean = false)

    /** One motor's `DRV_STATUS` fault mask (`STEPPER_FAULT_*`). */
    data class DiagMotor(val faults: Int = 0)

    /**
     * The FF0C diagnostics block, mirroring `app_bridge.cpp::build_diagnostics`
     * byte for byte (little-endian). The current firmware always writes
     * `format_version` 3 (96 bytes); pass a lower [formatVersion] to reproduce the
     * shorter block an older firmware sends — 2 omits the driver block (83 bytes),
     * 1 omits both the encoder and driver blocks (68-byte version-1 core) — so the
     * parser's version-tolerance is exercised against the real truncation, not a
     * hand-sliced one.
     */
    fun diagnostics(
        formatVersion: Int = 3,
        uptimeSeconds: Long = 1000L,
        freeHeapBytes: Long = 100_000L,
        minFreeHeapBytes: Long = 80_000L,
        stallCount: Long = 0,
        commandQueueDropped: Long = 0,
        motionOverruns: Long = 0,
        renderOverruns: Long = 0,
        framesSkipped: Long = 0,
        lastFrameMicros: Long = 8_000,
        meanFrameMicros: Long = 8_200,
        maxFrameMicros: Long = 15_000,
        frameRateHz: Int = 30,
        taskStacks: List<Int> = listOf(512, 480, 600, 320, 700),
        imus: List<DiagSensor> = listOf(DiagSensor(), DiagSensor()),
        batteryPercent: Int? = 78,
        batteryMillivolts: Int = 3_900,
        batteryLevel: Int = 1,
        encoders: List<DiagSensor> = listOf(DiagSensor(), DiagSensor(), DiagSensor(), DiagSensor()),
        rehomeCount: Int = 0,
        motors: List<DiagMotor> = listOf(DiagMotor(), DiagMotor(), DiagMotor(), DiagMotor()),
        driverLostWrites: Long = 0,
        driverReadFailures: Long = 0
    ): ByteArray {
        val w = Writer()
        w.u8(formatVersion)
        w.u32(uptimeSeconds)
        w.u32(freeHeapBytes)
        w.u32(minFreeHeapBytes)
        w.u32(stallCount)
        w.u32(commandQueueDropped)
        w.u32(motionOverruns)
        w.u32(renderOverruns)
        w.u32(framesSkipped)
        w.u32(lastFrameMicros)
        w.u32(meanFrameMicros)
        w.u32(maxFrameMicros)
        w.u8(frameRateHz)
        w.u8(taskStacks.size)
        taskStacks.forEach { w.u16(it) }
        w.u8(imus.size)
        imus.forEach { w.u16(it.failures).bool(it.disabled) }
        w.u8(batteryPercent ?: Protocol.BATTERY_PERCENT_UNKNOWN)
        w.u16(batteryMillivolts)
        w.u8(batteryLevel)
        // The trailing blocks are append-only: an older firmware simply stops
        // after the core, which is exactly what a lower formatVersion reproduces.
        if (formatVersion >= 2) {
            w.u8(encoders.size)
            encoders.forEach { w.u16(it.failures).bool(it.disabled) }
            w.u16(rehomeCount)
        }
        if (formatVersion >= 3) {
            w.u8(motors.size)
            motors.forEach { w.u8(it.faults) }
            w.u32(driverLostWrites)
            w.u32(driverReadFailures)
        }
        return w.toByteArray()
    }

    /** One FF0D parameter record, before it is padded out to its fixed 26 bytes. */
    data class ParamRecord(
        val paramId: Int,
        val unitCode: Int = 0x00,
        val name: String = "param",
        val min: Float = 0f,
        val max: Float = 1f,
        val default: Float = 0.5f
    )

    /**
     * FF0D parameter descriptors, mirroring
     * `ConfigManager::build_param_descriptors` and `write_param_record`.
     *
     * `[kind][id][status][count]` then `count` fixed 26-byte records. The
     * firmware writes **no records at all** unless the status is OK, which is why
     * the count slot is stamped after the loop device-side; that behaviour is
     * reproduced here rather than assumed, so a `status != OK` payload is the
     * bare 4-byte header a real device sends.
     */
    fun paramDescriptors(
        kind: Int = 0x01,
        entityId: Int = 0x06,
        status: Int = 0x00,
        params: List<ParamRecord> = emptyList()
    ): ByteArray {
        val w = Writer()
        w.u8(kind).u8(entityId).u8(status)
        if (status != 0x00) {
            w.u8(0)
            return w.toByteArray()
        }
        w.u8(params.size)
        params.forEach { record ->
            w.u8(record.paramId).u8(record.unitCode)
            // Fixed-width and NUL-padded, so a reader can seek to record N. A
            // name longer than the field is truncated device-side, not rejected.
            val nameBytes = record.name.toByteArray(Charsets.UTF_8)
            for (i in 0 until Protocol.PARAM_NAME_MAX) {
                w.u8(if (i < nameBytes.size) nameBytes[i].toInt() else 0)
            }
            w.f32(record.min).f32(record.max).f32(record.default)
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
