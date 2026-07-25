package com.tailapp.model

/**
 * The FF0C diagnostics snapshot (SYS-3) — the one read a support screen is built
 * on: how long the device has been up, how much heap and stack it has left, and
 * the fault counters the firmware already keeps but never otherwise publishes.
 *
 * **Version-tolerant by construction.** The block is append-only: [formatVersion]
 * says how many of the trailing blocks the device actually wrote, and everything
 * appended after the version-1 core is nullable. A field beyond the payload's
 * length is *absent*, not zero — an older firmware simply sends a shorter block,
 * and "this firmware does not report encoder health" must never render as "every
 * encoder is fine". That distinction is the whole reason [encoders], [motors] and
 * their siblings are nullable rather than defaulted.
 */
data class Diagnostics(
    /** `3` on the current firmware. Bumped whenever a block is appended. */
    val formatVersion: Int,

    // --- health ---
    val uptimeSeconds: Long,
    val freeHeapBytes: Long,
    /**
     * Smallest the free heap has ever been. The number that actually matters: a
     * device surviving on a few KB of headroom is one fragmenting allocation away
     * from a reset that the instantaneous [freeHeapBytes] never shows.
     */
    val minFreeHeapBytes: Long,
    /** StallGuard trips since boot. Counts events, not time spent latched off. */
    val stallCount: Long,
    /** Commands rejected for a full queue; each was answered `RESULT_BUSY`. */
    val commandQueueDropped: Long,
    /** Motion cycles that took over 1.5x their 10 ms period. */
    val motionOverruns: Long,
    /** LED frames that took longer than the frame budget. */
    val renderOverruns: Long,
    /** LED frames dropped to resync after falling behind. */
    val framesSkipped: Long,

    // --- render ---
    val lastFrameMicros: Long,
    /** Mean over the last 32 frames. */
    val meanFrameMicros: Long,
    /** Worst frame since boot. */
    val maxFrameMicros: Long,
    /** Configured LED render rate (FF03 `0x0C`). */
    val frameRateHz: Int,

    /** Per-task stack headroom, in the firmware's order. See [TaskStackHealth]. */
    val tasks: List<TaskStackHealth>,

    /** Per-IMU read-failure counts and disabled flags, base then tip. */
    val imus: List<SensorHealth>,

    // --- battery ---
    /**
     * 0-100, or null when the device reports its unknown sentinel (`0xFF`). Unknown
     * is a real state — a board with no sense divider knows nothing about the pack —
     * and rendering it as 0 would say the tail is flat when it is not.
     */
    val batteryPercent: Int?,
    /** Pack voltage; `0` when unknown. */
    val batteryMillivolts: Int,
    val batteryLevel: DiagnosticsBatteryLevel,

    // --- v2: encoder health (null on firmware that predates format_version 2) ---
    /** Per-encoder read-failure counts and disabled flags, or null if not reported. */
    val encoders: List<SensorHealth>?,
    /** Positions adopted from the encoders since boot, or null if not reported. */
    val rehomeCount: Int?,

    // --- v3: TMC2209 driver health (null on firmware that predates format_version 3) ---
    /** Per-motor DRV_STATUS fault masks, or null if not reported. */
    val motors: List<MotorHealth>?,
    /** Write datagrams the drivers' `IFCNT` says never arrived, or null if not reported. */
    val driverLostWrites: Long?,
    /** Driver UART reads that timed out or failed CRC, or null if not reported. */
    val driverReadFailures: Long?
) {
    /** True once the device published the format_version 2 encoder block. */
    val hasEncoderHealth: Boolean get() = encoders != null

    /** True once the device published the format_version 3 driver block. */
    val hasDriverHealth: Boolean get() = motors != null

    companion object {
        /** Names for the tasks the current firmware reports, in payload order. */
        val TASK_NAMES = listOf("motion", "led_rend", "config", "nimble_host", "led_stat")
    }
}

/**
 * One FreeRTOS task's stack headroom, in words (the unit the kernel keeps it in).
 *
 * A figure of `0` means the task has **not registered itself yet**, not that it
 * ran out — a task that genuinely exhausted its stack has already crashed. So a
 * `0` on a running device is a task that has not reached its first line, which is
 * why it is worth showing rather than hiding as "empty".
 */
data class TaskStackHealth(
    val name: String,
    val freeWords: Int
) {
    val notRegistered: Boolean get() = freeWords == 0
}

/**
 * A sensor's health as the diagnostics block reports it for IMUs and encoders
 * alike: a saturating read-failure count and whether repeated failures have
 * disabled the part. The same shape for both because "this sensor stopped
 * answering" is the same question wherever the sensor lives.
 */
data class SensorHealth(
    /** Read failures since boot, saturating at `0xFFFF`. */
    val failures: Int,
    /** True while the part is disabled after repeated failures. */
    val disabled: Boolean
) {
    val isHealthy: Boolean get() = !disabled && failures == 0
}

/**
 * One TMC2209 driver's latest DRV_STATUS faults, as a raw mask plus the labels
 * it decodes to. The mask is kept because it is the wire truth; the labels exist
 * because a raw bitfield tells a user nothing about whether a motor is cooking or
 * miswired.
 */
data class MotorHealth(
    /** The raw `STEPPER_FAULT_*` bit mask; `0` when the driver is healthy. */
    val faults: Int
) {
    val isHealthy: Boolean get() = faults == 0

    /** The set faults, decoded to human-readable conditions. */
    val decoded: List<DriverFault> get() = DriverFault.decode(faults)
}

/**
 * The TMC2209 DRV_STATUS fault bits (`STEPPER_FAULT_*` in TailFirmware
 * `main/stepper.h`), each with the condition it names. Decoding the mask is not
 * cosmetic: the FF07 driver events can only say *what kind* of fault crossed the
 * shared bus, so the per-motor mask here is the only place an app can say it is
 * motor 2 that is overheating, and the raw byte means nothing to a user.
 */
enum class DriverFault(val bit: Int, val label: String) {
    OVERTEMP_WARNING(0x01, "Overtemperature warning"),
    OVERTEMP_SHUTDOWN(0x02, "Overtemperature shutdown"),
    SHORT_TO_GROUND_A(0x04, "Short to ground (phase A)"),
    SHORT_TO_GROUND_B(0x08, "Short to ground (phase B)"),
    OPEN_LOAD_A(0x10, "Open load (phase A)"),
    OPEN_LOAD_B(0x20, "Open load (phase B)");

    companion object {
        /** The faults set in [mask], in bit order. */
        fun decode(mask: Int): List<DriverFault> = entries.filter { mask and it.bit != 0 }
    }
}

/**
 * The `battery_level` byte from the diagnostics block. Distinct from
 * [BatteryPolicy] because this field carries an explicit [UNKNOWN] the policy
 * enum deliberately omits — the diagnostics read is the one place the device
 * says "I cannot measure this pack" as a value rather than as silence.
 */
enum class DiagnosticsBatteryLevel(val code: Int) {
    UNKNOWN(0),
    NORMAL(1),
    LOW(2),
    CRITICAL(3);

    companion object {
        /** An unrecognised code reads as [UNKNOWN] rather than throwing. */
        fun fromCode(code: Int): DiagnosticsBatteryLevel =
            entries.find { it.code == code } ?: UNKNOWN
    }
}
