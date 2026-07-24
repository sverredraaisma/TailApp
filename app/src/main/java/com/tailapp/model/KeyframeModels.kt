package com.tailapp.model

import com.tailapp.ble.protocol.Protocol

/**
 * One authored pose, in the same logical space every motion pattern emits: per
 * segment an X (left/right) and a Y (up/down) in degrees.
 *
 * `base` is the firmware's segment 0 and `tip` its segment 1. The device clamps
 * both to the configured axis limits downstream, so an editor cannot author a
 * pose past the mechanism's travel — but it can author one the axis limits will
 * quietly cut short, which is why the editor draws poses against those limits.
 */
data class TailPose(
    val baseX: Float = 0f,
    val baseY: Float = 0f,
    val tipX: Float = 0f,
    val tipY: Float = 0f
) {
    /**
     * The four motor targets this pose becomes, in the order FF02 reports and
     * FF0B accepts: both segments' X, then both segments' Y
     * (`keyframe_pattern.cpp::update`).
     */
    val motorTargets: FloatArray get() = floatArrayOf(baseX, tipX, baseY, tipY)

    /**
     * This pose as the device will hold it, after the wire's hundredths-of-a-
     * degree int16 has been through it.
     *
     * The preview interpolates between *these* values rather than the authored
     * floats: a slider that reads 12.345° arrives as 12.34°, and a preview that
     * kept the extra digit would drift from the tail over a long ramp.
     */
    fun onWireGrid(): TailPose = TailPose(
        dequantiseDegrees(quantiseDegrees(baseX)),
        dequantiseDegrees(quantiseDegrees(baseY)),
        dequantiseDegrees(quantiseDegrees(tipX)),
        dequantiseDegrees(quantiseDegrees(tipY))
    )

    val angles: List<Float> get() = listOf(baseX, baseY, tipX, tipY)

    companion object {
        /**
         * Widest angle the wire can carry: hundredths of a degree in an int16.
         * Far past the mechanism's travel, so this bound only ever catches a bug
         * — but an angle past it would wrap to the opposite deflection, which is
         * the tail slamming the other way rather than a rejected upload.
         */
        const val MAX_DEGREES = 327.67f

        /** Degrees to the wire's hundredths-of-a-degree int16. */
        fun quantiseDegrees(degrees: Float): Int = Math.round(degrees * 100f)

        /** The inverse, as `keyframe_sequence.cpp` computes it (`* 0.01f`). */
        fun dequantiseDegrees(hundredths: Int): Float = hundredths.toFloat() * 0.01f
    }
}

/** One pose, held at [timeMs] milliseconds from the start of its sequence. */
data class Keyframe(val timeMs: Int, val pose: TailPose) {
    /** The firmware's own time base: `t_ms * 0.001f`, in float seconds. */
    val seconds: Float get() = timeMs.toFloat() * 0.001f
}

/**
 * A timed list of poses, replayed on the device by `PATTERN_KEYFRAME` (MOT-8).
 *
 * The invariants below are the firmware's, not this editor's: they are exactly
 * what `KeyframeSequence::load` refuses to parse, checked here so the user is
 * told which keyframe is wrong instead of watching a finalize come back
 * `BAD_STATE` with nothing to act on.
 */
data class KeyframeSequence(
    val keyframes: List<Keyframe> = listOf(Keyframe(0, TailPose())),
    val loop: Boolean = false
) {
    val durationMs: Int get() = keyframes.lastOrNull()?.timeMs ?: 0

    /** Total length in the firmware's float seconds; zero for a single held pose. */
    val durationSeconds: Float get() = keyframes.lastOrNull()?.seconds ?: 0f

    val encodedSize: Int
        get() = Protocol.SEQUENCE_HEADER_SIZE + keyframes.size * Protocol.SEQUENCE_KEYFRAME_SIZE

    /** Everything the device would refuse this sequence for, in keyframe order. */
    fun validate(): List<SequenceProblem> {
        val problems = mutableListOf<SequenceProblem>()
        if (keyframes.isEmpty()) return listOf(SequenceProblem.Empty)
        if (keyframes.size > Protocol.MAX_SEQUENCE_KEYFRAMES) {
            problems += SequenceProblem.TooManyKeyframes(keyframes.size)
        }
        if (keyframes.first().timeMs != 0) {
            problems += SequenceProblem.FirstNotAtZero(keyframes.first().timeMs)
        }
        keyframes.forEachIndexed { index, keyframe ->
            if (keyframe.timeMs < 0) {
                problems += SequenceProblem.NegativeTime(index, keyframe.timeMs)
            } else if (index > 0) {
                val previous = keyframes[index - 1].timeMs
                if (keyframe.timeMs == previous) {
                    problems += SequenceProblem.DuplicateTime(index, keyframe.timeMs)
                } else if (keyframe.timeMs < previous) {
                    problems += SequenceProblem.OutOfOrder(index, keyframe.timeMs, previous)
                }
            }
            keyframe.pose.angles.forEach { angle ->
                if (!angle.isFinite() || angle < -TailPose.MAX_DEGREES || angle > TailPose.MAX_DEGREES) {
                    problems += SequenceProblem.AngleOutOfRange(index, angle)
                }
            }
        }
        return problems
    }

    val isValid: Boolean get() = validate().isEmpty()

    /**
     * The pose at [seconds] from the start, interpolated the way the device
     * interpolates it — linearly between the bracketing keyframes, on the
     * quantised angles, holding the nearest keyframe outside the sequence and
     * wrapping instead when [loop] is set.
     *
     * This is a port of `KeyframeSequence::sample`, expression for expression,
     * including the step from the last keyframe back to the first at a loop
     * wrap. Smoothing that step (or easing the spans) would make the editor
     * show a motion the tail cannot produce, which is worse than showing none.
     *
     * Callers keep [seconds] inside the sequence: the loop wrap subtracts a
     * duration at a time, as the firmware does, so a time thousands of cycles
     * out would spin.
     */
    fun sample(seconds: Float): TailPose {
        if (keyframes.isEmpty()) return TailPose()
        val duration = durationSeconds
        if (keyframes.size == 1 || duration <= 0f) return keyframes[0].pose.onWireGrid()
        if (!seconds.isFinite()) return keyframes[0].pose.onWireGrid()

        var t = seconds
        if (loop) {
            while (t >= duration) t -= duration
            while (t < 0f) t += duration
        } else {
            if (t <= 0f) return keyframes.first().pose.onWireGrid()
            if (t >= duration) return keyframes.last().pose.onWireGrid()
        }

        var i = 0
        while (i + 1 < keyframes.size && keyframes[i + 1].seconds <= t) i++
        if (i + 1 >= keyframes.size) return keyframes.last().pose.onWireGrid()

        val a = keyframes[i]
        val b = keyframes[i + 1]
        val span = b.seconds - a.seconds
        val u = if (span > 0f) (t - a.seconds) / span else 0f
        val from = a.pose.onWireGrid()
        val to = b.pose.onWireGrid()
        return TailPose(
            lerp(from.baseX, to.baseX, u),
            lerp(from.baseY, to.baseY, u),
            lerp(from.tipX, to.tipX, u),
            lerp(from.tipY, to.tipY, u)
        )
    }

    /** [sample] addressed in the milliseconds the editor works in. */
    fun sampleAtMillis(timeMs: Int): TailPose = sample(timeMs.toFloat() * 0.001f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/**
 * A reason the device would refuse a sequence.
 *
 * Each one mirrors a rejection in `KeyframeSequence::load` or a bound
 * `MCMD_BEGIN_SEQUENCE` checks, so the editor can refuse an upload for the same
 * reasons and name the keyframe responsible.
 */
sealed class SequenceProblem {
    /** Which keyframe is at fault, where that is meaningful. */
    abstract val keyframeIndex: Int?
    abstract val message: String

    data object Empty : SequenceProblem() {
        override val keyframeIndex: Int? = null
        override val message = "A sequence needs at least one keyframe."
    }

    data class TooManyKeyframes(val count: Int) : SequenceProblem() {
        override val keyframeIndex: Int? = null
        override val message =
            "$count keyframes — the device stores at most ${Protocol.MAX_SEQUENCE_KEYFRAMES}."
    }

    data class FirstNotAtZero(val timeMs: Int) : SequenceProblem() {
        override val keyframeIndex = 0
        override val message = "The first keyframe has to sit at 0 s, not ${timeMs / 1000f} s."
    }

    data class DuplicateTime(override val keyframeIndex: Int, val timeMs: Int) : SequenceProblem() {
        override val message =
            "Two keyframes at ${timeMs / 1000f} s — the device cannot interpolate a zero-length span."
    }

    data class OutOfOrder(
        override val keyframeIndex: Int,
        val timeMs: Int,
        val previousMs: Int
    ) : SequenceProblem() {
        override val message =
            "Keyframe $keyframeIndex runs backwards: ${timeMs / 1000f} s after ${previousMs / 1000f} s."
    }

    data class NegativeTime(override val keyframeIndex: Int, val timeMs: Int) : SequenceProblem() {
        override val message = "Keyframe $keyframeIndex has a negative time."
    }

    data class AngleOutOfRange(override val keyframeIndex: Int, val degrees: Float) : SequenceProblem() {
        override val message =
            "Keyframe $keyframeIndex has an angle of $degrees°, past the ±${TailPose.MAX_DEGREES}° the wire carries."
    }
}
