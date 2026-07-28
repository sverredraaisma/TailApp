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
 * Position is a **function of the context**, for the same reason the effects
 * are: it comes from `secondsSinceBeat` and `beatPhase` rather than being
 * integrated frame to frame, so a dropped or late frame moves the tail to where
 * it should be instead of falling behind. The drop flick's decay is derived from
 * `secondsSinceDrop` and so is closed-form too.
 *
 * The single piece of retained state is the *sign* of the drop flick, latched
 * when a drop lands and held for the whole of its window — see
 * [latchedDropNanos]. A sign recomputed per frame is a hard mechanical reversal
 * commanded at render rate, which is a different class of bug from a wrong
 * picture.
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

    /**
     * The drop the flick's direction was latched from, and that direction.
     *
     * The one piece of state here, and it exists to protect the mechanism. The
     * sign must be decided **once, when the drop lands**, and hold for the whole
     * flick window: derived per frame from anything that moves (the beat count
     * advances two to three times inside a 1.2 s window at 120 BPM) it would
     * throw the tail to one extreme and then command the opposite extreme half a
     * beat later, at full amplitude and at render rate. That is a hard reversal
     * of a physical linkage, and it is the one thing here that can break
     * hardware.
     */
    private var latchedDropNanos: Long? = null
    private var dropIndex = 0
    private var flickDirection = 1f

    /**
     * @return four half-axis targets in degrees, in a **fresh array the caller
     *   owns**. Use [targetsInto] on the streaming path, where the buffer is
     *   reused frame to frame.
     */
    fun targetsFor(ctx: ReactiveContext): FloatArray =
        FloatArray(TARGET_COUNT).also { targetsInto(it, ctx) }

    /**
     * Fills [out] (at least [TARGET_COUNT] long) with this frame's targets,
     * allocating nothing.
     */
    fun targetsInto(out: FloatArray, ctx: ReactiveContext) {
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

        // Latch the direction the moment a new drop arrives, identified by its
        // own timestamp. Consecutive drops still alternate, but the sign is
        // constant for the whole of one drop's window.
        val drop = ctx.lastDrop
        if (drop != null && drop.timestampNanos != latchedDropNanos) {
            latchedDropNanos = drop.timestampNanos
            dropIndex++
            flickDirection = if (dropIndex % 2 == 1) 1f else -1f
        }

        // A drop throws the tail to one side and lets it spring back. Derived
        // from the elapsed time rather than integrated, so a late frame lands it
        // where it belongs instead of stretching the flick.
        val flick = if (drop != null && ctx.secondsSinceDrop in 0f..FLICK_WINDOW_SECONDS) {
            val decay = exp(-ctx.secondsSinceDrop / FLICK_DECAY_SECONDS)
            c.dropFlickDegrees * decay * flickDirection
        } else {
            0f
        }

        val total = x + flick
        out[0] = total
        // The second half trails the first by a quarter swing, which is what
        // makes it read as a tail rather than a rigid rod - the same trick the
        // firmware's Wagging pattern uses.
        out[1] = total * TRAIL_FACTOR
        out[2] = c.restY
        out[3] = c.restY
    }

    companion object {
        /** Half-axis targets per frame, in the order FF02 reports positions. */
        const val TARGET_COUNT = 4

        private const val TWO_PI = (2.0 * PI).toFloat()

        /** Roughly one sway every four seconds. */
        private const val SWAY_RADIANS_PER_SECOND = 1.6f

        private const val FLICK_WINDOW_SECONDS = 1.2f
        private const val FLICK_DECAY_SECONDS = 0.35f

        /** How much of the first half's deflection the second half follows with. */
        private const val TRAIL_FACTOR = 0.7f
    }
}
