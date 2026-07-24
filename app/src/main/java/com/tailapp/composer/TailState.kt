package com.tailapp.composer

/** Which end of the tail an IMU tap came from. */
enum class TailEnd { BASE, TIP }

/**
 * A tap detected by one of the tail's IMUs, as reported on FF07.
 *
 * @property end which IMU fired.
 * @property timestampNanos when the app observed it, on the same clock as
 *   [ReactiveContext.nowNanos]. This is the *arrival* time, not the moment of
 *   impact: the device detects a tap at 100 Hz and the notify then crosses BLE,
 *   so it lags the physical tap by some tens of milliseconds. Good enough to
 *   trigger a visual, not good enough to sequence against the beat grid.
 */
data class TapEvent(
    val end: TailEnd,
    val timestampNanos: Long
)

/**
 * A snapshot of where the tail physically is, derived from the FF02 motion state
 * the device notifies at ~20 Hz.
 *
 * Effects read the normalised [deflectionX]/[deflectionY]/[wagSpeed] rather than
 * raw degrees, so a look behaves the same on a tail configured for +/-90 degrees
 * of travel as on one limited to +/-30.
 *
 * @property gravityX gravity direction from the base IMU, in g. Points "down" in
 *   the tail's own frame, so it says how the wearer is oriented.
 * @property deflectionX left/right deflection as `-1..1` of the axis's travel.
 * @property deflectionY up/down deflection as `-1..1` of the axis's travel.
 * @property wagSpeed how fast the tail is currently moving, `0..1`, where `1` is
 *   roughly a full sweep per second.
 */
data class TailTelemetry(
    val gravityX: Float = 0f,
    val gravityY: Float = 0f,
    val gravityZ: Float = 1f,
    val deflectionX: Float = 0f,
    val deflectionY: Float = 0f,
    val wagSpeed: Float = 0f
) {
    companion object {
        /**
         * A tail hanging still and level. Used when no device is connected, so
         * an effect that reads the body still renders something sensible in the
         * preview rather than special-casing "no tail".
         */
        val AT_REST = TailTelemetry()
    }
}
