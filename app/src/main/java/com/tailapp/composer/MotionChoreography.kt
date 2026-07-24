package com.tailapp.composer

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Turns the analysis into tail movement, the same way the composer turns it into
 * light.
 *
 * The device can wag on its own — it has motion patterns — but it cannot know
 * where the beat is, how loud the room is, or that a drop just landed. This
 * reads the same [ReactiveContext] the effects read and streams the resulting
 * targets over FF0B, so the tail dances to the music rather than to a timer.
 *
 * Everything here is a **pure function of the context**, for the same reason the
 * effects are: position comes from `secondsSinceBeat` and `beatPhase` rather
 * than being integrated frame to frame, so a dropped or late frame moves the
 * tail to where it should be instead of falling behind. The one exception is
 * the drop flick's decay, which is derived from `secondsSinceDrop` and so is
 * also closed-form.
 *
 * Targets are in degrees, in the order FF02 reports positions: axis 0 first and
 * second half, then axis 1. The device clamps them to its own axis limits and
 * shapes them through its jerk-limited profiles, so nothing here has to know the
 * mechanism's travel or how fast it can move.
 */
class MotionChoreography(var config: Config = Config()) {

    /**
     * @property mode what drives the motion.
     * @property amplitudeDegrees peak deflection of the side-to-side motion.
     * @property restY where the up/down axis sits when nothing is happening.
     * @property loudnessGain how much the amplitude grows with level; `0` keeps
     *   it constant regardless of how loud the room is.
     * @property dropFlickDegrees extra deflection thrown on a detected drop.
     */
    data class Config(
        val mode: Mode = Mode.BEAT_WAG,
        val amplitudeDegrees: Float = 35f,
        val restY: Float = 0f,
        val loudnessGain: Float = 0.6f,
        val dropFlickDegrees: Float = 25f
    )

    enum class Mode(val displayName: String, val description: String) {
        /** Side to side, phase-locked to the tracked beat. */
        BEAT_WAG("Beat wag", "Wags in time with the music"),

        /** Amplitude follows loudness; no tempo needed. */
        LOUDNESS("Follow volume", "Sways more the louder it gets"),

        /** Still, except for a flick on each drop. */
        DROP_ONLY("Drops only", "Still, but flicks on a drop")
    }

    /** Filled in place each frame; motion streaming must not allocate. */
    private val targets = FloatArray(4)

    /**
     * @return four half-axis targets in degrees, valid until the next call.
     */
    fun targetsFor(ctx: ReactiveContext): FloatArray {
        val c = config
        val level = ctx.level.coerceIn(0f, 1f)
        val amplitude = c.amplitudeDegrees * (1f - c.loudnessGain + c.loudnessGain * level)

        val x = when (c.mode) {
            Mode.BEAT_WAG -> {
                // One full swing per bar rather than per beat: a wag at 128 BPM
                // would otherwise be four sweeps a second, which the mechanism
                // cannot follow and which reads as vibration rather than dance.
                if (ctx.bpm > 0f) sin(ctx.barPhase * TWO_PI) * amplitude else 0f
            }
            Mode.LOUDNESS -> {
                // No tempo needed: a slow sway whose reach grows with the room.
                sin(ctx.timeSeconds * SWAY_RADIANS_PER_SECOND) * amplitude
            }
            Mode.DROP_ONLY -> 0f
        }

        // A drop throws the tail to one side and lets it spring back. Derived
        // from the elapsed time rather than integrated, so a late frame lands it
        // where it belongs instead of stretching the flick.
        val flick = if (ctx.secondsSinceDrop in 0f..FLICK_WINDOW_SECONDS) {
            val decay = exp(-ctx.secondsSinceDrop / FLICK_DECAY_SECONDS)
            // Alternate direction per drop so consecutive drops do not all throw
            // the tail the same way.
            val direction = if (ctx.beatCount % 2 == 0) 1f else -1f
            c.dropFlickDegrees * decay * direction
        } else {
            0f
        }

        val total = x + flick
        targets[0] = total
        // The second half trails the first by a quarter swing, which is what
        // makes it read as a tail rather than a rigid rod - the same trick the
        // firmware's Wagging pattern uses.
        targets[1] = total * TRAIL_FACTOR
        targets[2] = c.restY
        targets[3] = c.restY
        return targets
    }

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()

        /** Roughly one sway every four seconds. */
        const val SWAY_RADIANS_PER_SECOND = 1.6f

        const val FLICK_WINDOW_SECONDS = 1.2f
        const val FLICK_DECAY_SECONDS = 0.35f

        /** How much of the first half's deflection the second half follows with. */
        const val TRAIL_FACTOR = 0.7f
    }
}
