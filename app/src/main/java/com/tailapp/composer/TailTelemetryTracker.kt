package com.tailapp.composer

import com.tailapp.model.MotionState
import kotlin.math.abs

/**
 * Turns the FF02 motion state into the normalised [TailTelemetry] effects read.
 *
 * Two jobs the raw payload does not do:
 *
 * - **Normalise.** FF02 reports degrees from zero, and the usable range depends
 *   on the axis limits the user configured. An effect written against raw
 *   degrees would look right on a tail with +/-90 degrees of travel and barely
 *   move on one limited to +/-30. Deflection is reported as a fraction of the
 *   configured travel instead.
 * - **Differentiate.** Wag speed is not in the payload at all; it comes from how
 *   fast deflection is changing. That needs the previous sample and the real
 *   interval between them, because FF02 notifies at a nominal ~20 Hz that
 *   jitters with BLE scheduling — dividing by an assumed period would turn
 *   connection hiccups into phantom wags.
 *
 * Not thread-safe: drive it from one collector and publish the result through
 * [CompositionScene.onTailTelemetry].
 */
class TailTelemetryTracker {

    private var lastX = 0f
    private var lastY = 0f
    private var lastNanos = Long.MIN_VALUE
    private var smoothedSpeed = 0f

    fun reset() {
        lastX = 0f
        lastY = 0f
        lastNanos = Long.MIN_VALUE
        smoothedSpeed = 0f
    }

    /**
     * @param state the latest FF02 payload.
     * @param nowNanos arrival time, on the same clock the renderer uses.
     */
    fun update(state: MotionState, nowNanos: Long): TailTelemetry {
        val deflectionX = normalisedDeflection(
            state.encoderPositions.getOrNull(0), state.encoderPositions.getOrNull(1),
            state.xAxisMin, state.xAxisMax
        )
        val deflectionY = normalisedDeflection(
            state.encoderPositions.getOrNull(2), state.encoderPositions.getOrNull(3),
            state.yAxisMin, state.yAxisMax
        )

        val speed = if (lastNanos == Long.MIN_VALUE) {
            0f
        } else {
            val dt = (nowNanos - lastNanos) / NANOS_PER_SECOND
            if (dt <= MIN_INTERVAL_SECONDS) {
                // Two samples closer together than this say more about BLE
                // scheduling than about the tail; reusing the previous speed
                // beats dividing by a near-zero interval.
                smoothedSpeed
            } else {
                val dx = deflectionX - lastX
                val dy = deflectionY - lastY
                // Full-scale sweeps per second, so 1.0 is a brisk wag.
                val instantaneous = (abs(dx) + abs(dy)) / dt / FULL_SWEEP
                smooth(smoothedSpeed, instantaneous.coerceIn(0f, 1f))
            }
        }

        lastX = deflectionX
        lastY = deflectionY
        lastNanos = nowNanos
        smoothedSpeed = speed

        return TailTelemetry(
            gravityX = state.gravityX,
            gravityY = state.gravityY,
            gravityZ = state.gravityZ,
            deflectionX = deflectionX,
            deflectionY = deflectionY,
            wagSpeed = speed
        )
    }

    /**
     * Averages an axis's two halves and expresses the result as `-1..1` of the
     * configured travel. Returns 0 for a degenerate window rather than dividing
     * by it — firmware normalises those away, but an older device can still
     * report `[0, 0]`.
     */
    private fun normalisedDeflection(
        first: Float?,
        second: Float?,
        min: Float,
        max: Float
    ): Float {
        if (first == null && second == null) return 0f
        val positions = listOfNotNull(first, second)
        val average = positions.sum() / positions.size
        val halfRange = (max - min) / 2f
        if (halfRange <= MIN_TRAVEL_DEGREES) return 0f
        val centre = (max + min) / 2f
        return ((average - centre) / halfRange).coerceIn(-1f, 1f)
    }

    private fun smooth(current: Float, target: Float): Float {
        // Asymmetric like the audio envelopes: a wag should light up as it
        // starts and fade out as it stops, not flicker with sample noise.
        val coefficient = if (target > current) ATTACK else RELEASE
        return current + (target - current) * coefficient
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MIN_INTERVAL_SECONDS = 0.001f
        const val MIN_TRAVEL_DEGREES = 1f

        /**
         * Deflection change per second that counts as full speed. One full
         * corner-to-corner sweep (2.0 of normalised range) in a second is about
         * as fast as the mechanism moves.
         */
        const val FULL_SWEEP = 2f

        const val ATTACK = 0.6f
        const val RELEASE = 0.12f
    }
}
