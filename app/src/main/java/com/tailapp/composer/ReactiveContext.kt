package com.tailapp.composer

import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.genre.GenreState
import kotlin.math.exp

/**
 * The analysis snapshot every effect sees for one rendered frame.
 *
 * This is the single answer to "let the beat, BPM, volume and FFT data be used
 * by every effect": [CompositionScene] rebuilds one of these each render frame
 * from the latest beat, transient, audio and genre state, and passes the *same*
 * instance to every layer. Effects read it and nothing else — no mic, no clock,
 * no BLE — which is what keeps the whole effect library testable with a
 * hand-built context and a fake time.
 *
 * All timing is session-relative ([timeSeconds] counts from the first frame),
 * not absolute `nanoTime`, so a `Float` holds it precisely for any real session
 * — the same reason `ReactiveRenderer` used a session-relative clock.
 *
 * Consumers must not retain this object or its [bands] array past the [render]
 * call: both are reused between frames.
 *
 * @property nowNanos raw frame clock, already including the calibration offset.
 * @property timeSeconds seconds since the session's first frame.
 * @property dtSeconds seconds since the previous frame (for state that
 *   integrates, e.g. a decaying trail).
 * @property bpm current tempo estimate, or `0` before one is known.
 * @property lastBeat the most recent tracked beat, or null.
 * @property beatCount beats seen this session, for round-robin colour/ring picks.
 * @property secondsSinceBeat time since [lastBeat] (large when none/future).
 * @property beatPhase `0..1` sawtooth: `0` at the beat instant, climbing to `1`
 *   just before the next beat (derived from [bpm]). `0` when no tempo is known.
 * @property barPhase `0..1` across a four-beat bar, from [BeatEvent.beatInBar]
 *   plus [beatPhase].
 * @property onDownbeat whether [lastBeat] began a bar.
 * @property level smoothed, adaptively-normalised loudness in `0..1` — the
 *   figure an effect should reach for as "how loud is it right now".
 * @property rms the raw broadband RMS, un-normalised.
 * @property bass normalised low-band energy `0..1`.
 * @property mid normalised mid-band energy `0..1`.
 * @property high normalised high-band energy `0..1`.
 * @property bands the adaptively-normalised log spectrum, low to high, each
 *   `0..1`. Longer and higher-resolution than the firmware's 128-bin buffer.
 * @property lastDrop the most recent detected drop, or null.
 * @property secondsSinceDrop time since [lastDrop] (large when none/future).
 * @property section the current coarse song section.
 * @property sectionRamp `0..1` progress through a transitional section (a
 *   build-up), else `0`.
 * @property genre the current genre estimate; effects may tint or gate on it.
 * @property lastTap the most recent IMU tap, or null. Taps are the one input
 *   here that comes from the tail itself rather than the microphone.
 * @property secondsSinceTap time since [lastTap] (large when none).
 * @property tapCount taps seen this session.
 * @property tail where the tail physically is — orientation, deflection and wag
 *   speed. [TailTelemetry.AT_REST] when no device is connected, so an effect
 *   that reads the body still renders in the preview.
 */
class ReactiveContext(
    val nowNanos: Long,
    val timeSeconds: Float,
    val dtSeconds: Float,
    val bpm: Float,
    val lastBeat: BeatEvent?,
    val beatCount: Int,
    val secondsSinceBeat: Float,
    val beatPhase: Float,
    val barPhase: Float,
    val onDownbeat: Boolean,
    val level: Float,
    val rms: Float,
    val bass: Float,
    val mid: Float,
    val high: Float,
    val bands: FloatArray,
    val lastDrop: DropEvent?,
    val secondsSinceDrop: Float,
    val section: SectionState,
    val sectionRamp: Float,
    val genre: GenreState,
    val lastTap: TapEvent? = null,
    val secondsSinceTap: Float = NO_EVENT_SECONDS,
    val tapCount: Int = 0,
    val tail: TailTelemetry = TailTelemetry.AT_REST
) {
    /** How many spectrum bands [band] can index. */
    val bandCount: Int get() = bands.size

    /** Normalised magnitude of spectrum band [i], `0` when out of range. */
    fun band(i: Int): Float = if (i in bands.indices) bands[i] else 0f

    /**
     * Samples the spectrum at [fraction] of its width (`0` = lowest band, `1` =
     * highest), linearly interpolated — for effects that map the spectrum onto a
     * coordinate rather than onto discrete bins.
     */
    fun bandAtFraction(fraction: Float): Float {
        if (bands.isEmpty()) return 0f
        if (bands.size == 1) return bands[0]
        val clamped = fraction.coerceIn(0f, 1f)
        val pos = clamped * (bands.size - 1)
        val i = pos.toInt()
        if (i >= bands.size - 1) return bands[bands.size - 1]
        val t = pos - i
        return bands[i] + (bands[i + 1] - bands[i]) * t
    }

    /**
     * A beat flash's brightness for this frame: `1` at the beat instant, decaying
     * exponentially with time constant [decaySeconds]. `0` when there is no beat
     * yet or the beat is still in the future — the tracker predicts beats ahead
     * of time, and lighting them early would defeat the calibration that absorbs
     * the BLE round trip.
     *
     * @param downbeatBoost multiplier for a downbeat (`>= 1`); the extra headroom
     *   clips brighter, reading as an accent on the first beat of a bar.
     */
    fun beatEnvelope(decaySeconds: Float, downbeatBoost: Float = 1f): Float {
        if (lastBeat == null || secondsSinceBeat < 0f) return 0f
        val decayed = exp(-secondsSinceBeat / decaySeconds.coerceAtLeast(1e-3f))
        return decayed * (if (onDownbeat) downbeatBoost else 1f)
    }

    /**
     * A drop's brightness for this frame: falls linearly from `1` to `0` over
     * [windowSeconds], scaled by the drop's own intensity. `0` when there is no
     * active drop.
     */
    fun dropEnvelope(windowSeconds: Float): Float {
        val drop = lastDrop ?: return 0f
        if (secondsSinceDrop < 0f || secondsSinceDrop > windowSeconds) return 0f
        val remaining = 1f - secondsSinceDrop / windowSeconds.coerceAtLeast(1e-3f)
        return (remaining * (0.5f + 0.5f * drop.intensity)).coerceIn(0f, 1f)
    }

    /**
     * A tap's brightness for this frame: `1` at the tap instant, decaying
     * exponentially with time constant [decaySeconds]. `0` when there has been
     * no tap, or when [end] is given and the last tap came from the other end.
     *
     * Mirrors [beatEnvelope] so a tap-driven effect reads the same way as a
     * beat-driven one.
     */
    fun tapEnvelope(decaySeconds: Float, end: TailEnd? = null): Float {
        val tap = lastTap ?: return 0f
        if (end != null && tap.end != end) return 0f
        if (secondsSinceTap < 0f) return 0f
        return exp(-secondsSinceTap / decaySeconds.coerceAtLeast(1e-3f))
    }

    /**
     * How far the tail is deflected from centre, `0..1`, regardless of
     * direction — the magnitude an effect wants when it is brightening with
     * movement rather than pointing at it.
     */
    val deflectionMagnitude: Float
        get() {
            val x = tail.deflectionX
            val y = tail.deflectionY
            return kotlin.math.sqrt(x * x + y * y).coerceIn(0f, 1f)
        }

    companion object {
        /** Sentinel for "no beat/drop/tap yet" — larger than any real elapsed time. */
        const val NO_EVENT_SECONDS = 1e9f
    }
}
