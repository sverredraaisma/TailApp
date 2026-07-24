package com.tailapp.led

/**
 * Kotlin mirror of `MotionBus` (`main/motion/motion_bus.h` / `.cpp`): the
 * motion-reactive LED effects read the tail's own body through this, the same
 * way the firmware effects read `MotionBus::instance()`.
 *
 * This is the counterpart to [AudioLevelSource], and exists for the same
 * reason: an effect must read one seam, so the preview and the device can be
 * driven from the same numbers. What differs is where the numbers come from —
 * the firmware's motion task publishes at 100 Hz, while the app assembles the
 * same fields out of FF02 (positions, gravity) and FF07 (taps).
 *
 * Deliberately *not* [com.tailapp.composer.TailTelemetry]: that normalises
 * deflection against the configured travel, and `motion_glow_effect.cpp` maps
 * raw degrees across a fixed +-90 window. Feeding it normalised values would
 * make the preview's hue disagree with the tail's on any device whose limits
 * aren't the default.
 *
 * The firmware double-buffers `motion_snapshot_t` and flips a read index;
 * swapping one immutable snapshot reference gives the same "old frame or new
 * frame, never a mix" guarantee on the JVM, as [AudioLevelSource] does.
 *
 * Pure JVM: no `android.*` imports, so unit tests drive it directly.
 */
class MotionStateSource {

    private class Snapshot(
        val positions: FloatArray,
        val gravityX: Float,
        val gravityY: Float,
        val gravityZ: Float,
        val motionEnergy: Float
    )

    // An unpublished bus reads back as `motion_snapshot_t{}` on the device -
    // all zeros, gravity included. Defaulting gravity to "hanging level"
    // instead would make a disconnected preview claim an orientation the app
    // has not actually been told.
    @Volatile
    private var snapshot = Snapshot(FloatArray(MAX_SERVOS), 0f, 0f, 0f, 0f)

    @Volatile
    private var tapBasePending = false

    @Volatile
    private var tapTipPending = false

    /**
     * Publishes a new snapshot. Mirrors `MotionBus::publish`.
     *
     * @param positions dead-reckoned positions in degrees from zero,
     *   axis-major (axis0 half0, axis0 half1, axis1 half0, axis1 half1) — the
     *   order FF02 reports and `motion_snapshot_t::positions` holds.
     * @param motionEnergy normalised 0-1 measure of how fast the tail is
     *   moving overall.
     */
    fun publish(
        positions: FloatArray,
        gravityX: Float,
        gravityY: Float,
        gravityZ: Float,
        motionEnergy: Float
    ) {
        val copied = FloatArray(MAX_SERVOS)
        positions.copyInto(copied, endIndex = positions.size.coerceAtMost(MAX_SERVOS))
        snapshot = Snapshot(copied, gravityX, gravityY, gravityZ, motionEnergy)
    }

    /**
     * Latches a tap on the base IMU. Latched rather than carried in a
     * snapshot for the same reason the firmware latches it: the render loop is
     * slower than the tap source, so a flag that lived in one frame only would
     * be missed most of the time.
     */
    fun tapBase() {
        tapBasePending = true
    }

    /** Latches a tap on the tip IMU. */
    fun tapTip() {
        tapTipPending = true
    }

    /** Mirrors `MotionBus::take_tap_base` — one-shot, cleared on read. */
    fun takeTapBase(): Boolean {
        val v = tapBasePending
        tapBasePending = false
        return v
    }

    /** Mirrors `MotionBus::take_tap_tip`. */
    fun takeTapTip(): Boolean {
        val v = tapTipPending
        tapTipPending = false
        return v
    }

    /** Position of one motor in degrees from zero; `0f` for an out-of-range index. */
    fun position(index: Int): Float {
        val p = snapshot.positions
        return if (index < 0 || index >= p.size) 0f else p[index]
    }

    /** Gravity direction from the base IMU, in g. */
    val gravityX: Float get() = snapshot.gravityX
    val gravityY: Float get() = snapshot.gravityY
    val gravityZ: Float get() = snapshot.gravityZ

    /** How fast the tail is moving overall, 0-1. */
    val motionEnergy: Float get() = snapshot.motionEnergy

    companion object {
        /** `MAX_SERVOS` (`config_types.h`). */
        const val MAX_SERVOS = 4
    }
}
