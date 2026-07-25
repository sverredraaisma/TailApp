package com.tailapp.ble.protocol

import com.tailapp.model.Diagnostics
import com.tailapp.model.DiagnosticsBatteryLevel
import com.tailapp.model.MotorHealth
import com.tailapp.model.SensorHealth
import com.tailapp.model.TaskStackHealth
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the FF0C diagnostics payload, little-endian, walking forward from the
 * front — the byte layout is documented in TailFirmware
 * `docs/ble-protocol.md` (FF0C) and built by `app_bridge.cpp::build_diagnostics`.
 *
 * ```
 * -- format_version 1 core (68 bytes) --
 * [format_version u8]
 * [uptime u32][free_heap u32][min_free_heap u32]
 * [stall_count u32][command_queue_dropped u32]
 * [motion_overruns u32][render_overruns u32][frames_skipped u32]
 * [last_frame_us u32][mean_frame_us u32][max_frame_us u32]
 * [frame_rate_hz u8]
 * [num_tasks u8][stack_free_words u16 x num_tasks]
 * [num_imus u8] per imu: [failures u16][disabled u8]
 * [battery_percent u8][battery_millivolts u16][battery_level u8]
 * -- format_version 2: encoder health --
 * [num_encoders u8] per encoder: [failures u16][disabled u8]
 * [rehome_count u16]
 * -- format_version 3: TMC2209 driver health --
 * [num_motors u8][faults u8 x num_motors]
 * [driver_lost_writes u32][driver_read_failures u32]
 * ```
 *
 * **Version-tolerant.** Each block appended after the version-1 core is read only
 * when [Diagnostics.formatVersion] says the device published it *and* the payload
 * is long enough to hold it. The version says how far the device meant to write;
 * the length is the safety bound. Either falling short leaves that block — and
 * every block behind it — null, because a field the device did not report has to
 * stay distinguishable from one it reported as zero. Counts (`num_tasks`,
 * `num_encoders`, …) are read from the wire rather than hard-coded, so a future
 * build with more of a thing stays parseable at a different total length.
 */
object DiagnosticsParser {

    /** Bytes of the version-1 core with this build's task/IMU counts (offsets 0-67). */
    const val CORE_V1_SIZE = 68

    fun parse(data: ByteArray): Diagnostics? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // The core is the contract: a payload too short to hold it whole is not a
        // shorter version, it is unreadable, and is rejected rather than guessed at.
        if (buf.remaining() < FIXED_PREFIX) return null
        val formatVersion = buf.u8()
        val uptimeSeconds = buf.u32()
        val freeHeapBytes = buf.u32()
        val minFreeHeapBytes = buf.u32()
        val stallCount = buf.u32()
        val commandQueueDropped = buf.u32()
        val motionOverruns = buf.u32()
        val renderOverruns = buf.u32()
        val framesSkipped = buf.u32()
        val lastFrameMicros = buf.u32()
        val meanFrameMicros = buf.u32()
        val maxFrameMicros = buf.u32()
        val frameRateHz = buf.u8()

        val numTasks = buf.u8()
        if (buf.remaining() < numTasks * TASK_ENTRY_SIZE) return null
        val tasks = List(numTasks) { i ->
            TaskStackHealth(name = Diagnostics.TASK_NAMES.getOrElse(i) { "task$i" }, freeWords = buf.u16())
        }

        if (buf.remaining() < 1) return null
        val numImus = buf.u8()
        if (buf.remaining() < numImus * SENSOR_ENTRY_SIZE) return null
        val imus = List(numImus) { SensorHealth(failures = buf.u16(), disabled = buf.u8() != 0) }

        if (buf.remaining() < BATTERY_BLOCK_SIZE) return null
        val batteryByte = buf.u8()
        val batteryPercent = if (batteryByte == Protocol.BATTERY_PERCENT_UNKNOWN) null else batteryByte
        val batteryMillivolts = buf.u16()
        val batteryLevel = DiagnosticsBatteryLevel.fromCode(buf.u8())

        // Appended blocks. Read strictly in order and only while both the version
        // and the remaining length agree the device wrote them; the first block to
        // fall short stops the walk so nothing behind it is read from misaligned
        // bytes.
        var encoders: List<SensorHealth>? = null
        var rehomeCount: Int? = null
        var motors: List<MotorHealth>? = null
        var driverLostWrites: Long? = null
        var driverReadFailures: Long? = null

        if (formatVersion >= 2 && buf.remaining() >= 1) {
            val numEncoders = buf.u8()
            if (buf.remaining() >= numEncoders * SENSOR_ENTRY_SIZE + REHOME_SIZE) {
                encoders = List(numEncoders) { SensorHealth(failures = buf.u16(), disabled = buf.u8() != 0) }
                rehomeCount = buf.u16()

                if (formatVersion >= 3 && buf.remaining() >= 1) {
                    val numMotors = buf.u8()
                    if (buf.remaining() >= numMotors + DRIVER_COUNTER_SIZE) {
                        motors = List(numMotors) { MotorHealth(faults = buf.u8()) }
                        driverLostWrites = buf.u32()
                        driverReadFailures = buf.u32()
                    }
                }
            }
        }

        return Diagnostics(
            formatVersion = formatVersion,
            uptimeSeconds = uptimeSeconds,
            freeHeapBytes = freeHeapBytes,
            minFreeHeapBytes = minFreeHeapBytes,
            stallCount = stallCount,
            commandQueueDropped = commandQueueDropped,
            motionOverruns = motionOverruns,
            renderOverruns = renderOverruns,
            framesSkipped = framesSkipped,
            lastFrameMicros = lastFrameMicros,
            meanFrameMicros = meanFrameMicros,
            maxFrameMicros = maxFrameMicros,
            frameRateHz = frameRateHz,
            tasks = tasks,
            imus = imus,
            batteryPercent = batteryPercent,
            batteryMillivolts = batteryMillivolts,
            batteryLevel = batteryLevel,
            encoders = encoders,
            rehomeCount = rehomeCount,
            motors = motors,
            driverLostWrites = driverLostWrites,
            driverReadFailures = driverReadFailures
        )
    }

    /**
     * Bytes up to and including `num_tasks`: `format_version` + 11 x u32 +
     * `frame_rate_hz` + `num_tasks`. The shortest read that can begin the walk;
     * below it even the task-array length is unknowable.
     */
    private const val FIXED_PREFIX = 1 + 11 * 4 + 1 + 1

    private const val TASK_ENTRY_SIZE = 2
    private const val SENSOR_ENTRY_SIZE = 3
    private const val BATTERY_BLOCK_SIZE = 4
    private const val REHOME_SIZE = 2
    private const val DRIVER_COUNTER_SIZE = 8

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
    private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL
}
