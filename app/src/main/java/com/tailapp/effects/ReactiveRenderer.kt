package com.tailapp.effects

import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.drop.SectionStateUpdate
import com.tailapp.led.LedCoord
import com.tailapp.led.LedLayout
import com.tailapp.led.PixelBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Turns analysis events into pixels, according to the active [EffectProfile].
 *
 * The renderer is a pure function of (events so far, current time): every call
 * to [render] rebuilds the frame from the stored event timestamps rather than
 * accumulating state frame to frame. That is what makes the whole look testable
 * with a fake clock, and it means a dropped or late frame changes nothing about
 * where the animation is — it just skips ahead, exactly like the audio did.
 *
 * The layout comes from the same [LedLayout] map the firmware uses, so an effect
 * that runs "along the tail" here runs along the tail on the device too.
 *
 * Not thread-safe: one renderer belongs to one render loop.
 */
class ReactiveRenderer(private val sparkleSeed: Int = 0x7A11) {

    var profile: EffectProfile = EffectProfiles.DEFAULT
        set(value) {
            field = value
            // Nothing to invalidate: the frame is rebuilt from timestamps, so the
            // next render picks the new profile up whole.
        }

    private var coords: List<LedCoord> = emptyList()
    private var currentLedsPerRing: List<Int>? = null
    private var ringOfLed: IntArray = IntArray(0)
    private var ringCount: Int = 0
    private var buffer = PixelBuffer(0)

    private var lastBeat: BeatEvent? = null
    private var beatCounter: Int = 0

    private var lastDrop: DropEvent? = null

    private var section: SectionStateUpdate =
        SectionStateUpdate(SectionState.UNKNOWN, 0f, 0L, 0f)

    /** LEDs the current layout holds. */
    val ledCount: Int get() = coords.size

    /**
     * Rebuilds the coordinate map. Cheap to call with an unchanged layout — the
     * device re-reports its ring configuration once a second and most of those
     * reports say nothing new.
     */
    fun setLayout(ledsPerRing: List<Int>) {
        if (ledsPerRing == currentLedsPerRing) return
        currentLedsPerRing = ledsPerRing
        coords = LedLayout.coordsFor(ledsPerRing)
        ringCount = ledsPerRing.size
        ringOfLed = IntArray(coords.size)
        var index = 0
        ledsPerRing.forEachIndexed { ring, count ->
            repeat(count) {
                if (index < ringOfLed.size) ringOfLed[index] = ring
                index++
            }
        }
        buffer = PixelBuffer(coords.size)
    }

    fun onBeat(event: BeatEvent) {
        lastBeat = event
        beatCounter++
    }

    fun onDrop(event: DropEvent) {
        if (profile.drop == DropBehaviour.NONE) return
        lastDrop = event
    }

    fun onSection(update: SectionStateUpdate) {
        section = update
    }

    fun reset() {
        lastBeat = null
        lastDrop = null
        beatCounter = 0
        section = SectionStateUpdate(SectionState.UNKNOWN, 0f, 0L, 0f)
    }

    /**
     * Renders the frame for [nowNanos].
     *
     * @return the renderer's own buffer — valid until the next call. Outputs that
     *   keep a frame must copy it.
     */
    fun render(nowNanos: Long): PixelBuffer {
        if (coords.isEmpty()) return buffer

        val beatLevel = beatEnvelope(nowNanos)
        val dropLevel = dropEnvelope(nowNanos)
        val timeSeconds = nowNanos / NANOS_PER_SECOND

        // Slow modulation applies to the whole frame: a build-up strobes it, a
        // breakdown dims it. Both are section-driven, so they ramp rather than
        // switch.
        val sectionGain = sectionGain(timeSeconds)
        val globalGain = profile.brightness * sectionGain

        for (i in coords.indices) {
            val coord = coords[i]
            val idle = idleColour(coord, timeSeconds)
            val beat = beatColour(i, coord, beatLevel)

            var r = channel(idle, 16) + channel(beat, 16)
            var g = channel(idle, 8) + channel(beat, 8)
            var b = channel(idle, 0) + channel(beat, 0)

            if (dropLevel > 0f) {
                val dropped = dropColour(i, coord, dropLevel, r, g, b)
                r = (dropped shr 16) and 0xFF
                g = (dropped shr 8) and 0xFF
                b = dropped and 0xFF
            }

            buffer.set(
                i,
                (r * globalGain).toInt(),
                (g * globalGain).toInt(),
                (b * globalGain).toInt()
            )
        }
        return buffer
    }

    // --- envelopes ---

    /**
     * Beat brightness, decaying exponentially from the beat instant.
     *
     * A beat whose timestamp is still in the future contributes nothing yet —
     * the tracker predicts beats ahead of time so the BLE round trip can be
     * absorbed, and lighting them early would defeat that.
     */
    private fun beatEnvelope(nowNanos: Long): Float {
        val beat = lastBeat ?: return 0f
        val elapsed = (nowNanos - beat.timestampNanos) / NANOS_PER_SECOND
        if (elapsed < 0f) return 0f
        val decayed = exp(-elapsed / profile.beatDecaySeconds)
        // A downbeat is allowed past 1: the extra headroom clips into white,
        // which is what makes the first beat of a bar read as an accent.
        val boost = if (beat.isDownbeat) profile.downbeatBoost else 1f
        return decayed * boost
    }

    /** Drop brightness, falling linearly over the profile's drop window. */
    private fun dropEnvelope(nowNanos: Long): Float {
        val drop = lastDrop ?: return 0f
        val elapsed = (nowNanos - drop.timestampNanos) / NANOS_PER_SECOND
        if (elapsed < 0f || elapsed > profile.dropSeconds) return 0f
        val remaining = 1f - elapsed / profile.dropSeconds
        return (remaining * (0.5f + 0.5f * drop.intensity)).coerceIn(0f, 1f)
    }

    /** Build-up strobing and breakdown dimming, as one multiplier. */
    private fun sectionGain(timeSeconds: Float): Float = when (section.state) {
        SectionState.BUILDUP -> {
            val hz = profile.buildupStrobeStartHz +
                (profile.buildupStrobeEndHz - profile.buildupStrobeStartHz) * section.ramp
            // Square-ish rather than sinusoidal: a build-up should read as
            // flashes getting faster, not as a wobble getting quicker.
            val phase = (timeSeconds * hz) % 1f
            if (phase < STROBE_DUTY) 1f else STROBE_FLOOR
        }
        SectionState.BREAKDOWN -> profile.breakdownBrightness
        SectionState.OUTRO -> profile.breakdownBrightness * 0.8f
        SectionState.INTRO -> 0.6f
        else -> 1f
    }

    // --- colour layers ---

    private fun idleColour(coord: LedCoord, timeSeconds: Float): Int =
        when (profile.idle) {
            IdleAnimation.OFF -> 0
            IdleAnimation.PALETTE_CYCLE -> {
                val position = (timeSeconds / PALETTE_CYCLE_SECONDS) % 1f
                scale(paletteAt(position), IDLE_LEVEL)
            }
            IdleAnimation.BREATHE -> {
                val breath = 0.35f + 0.65f * (0.5f + 0.5f * sin(timeSeconds * BREATHE_RATE * TWO_PI))
                scale(profile.palette.first(), IDLE_LEVEL * breath)
            }
            IdleAnimation.GRADIENT_DRIFT -> {
                val position = (coord.y + timeSeconds / GRADIENT_DRIFT_SECONDS) % 1f
                scale(paletteAt(if (position < 0f) position + 1f else position), IDLE_LEVEL)
            }
        }

    private fun beatColour(index: Int, coord: LedCoord, level: Float): Int {
        if (level <= 0f) return 0
        val mask = beatMask(index, coord)
        if (mask <= 0f) return 0
        val colour = paletteAt(((beatCounter % profile.palette.size).toFloat()) / profile.palette.size)
        return scale(colour, level * mask)
    }

    /** How strongly this LED participates in the current beat, `0..1`. */
    private fun beatMask(index: Int, coord: LedCoord): Float = when (profile.beatShape) {
        BeatShape.FULL -> 1f

        BeatShape.RING_CHASE -> {
            if (ringCount <= 1) 1f else {
                val target = beatCounter % ringCount
                val distance = abs(ringOfLed[index] - target)
                // Neighbouring rings glow faintly so the pulse reads as movement
                // rather than as one ring switching on.
                when (distance) {
                    0 -> 1f
                    1 -> 0.35f
                    else -> 0f
                }
            }
        }

        BeatShape.ALTERNATE_RINGS ->
            if (ringOfLed[index] % 2 == beatCounter % 2) 1f else 0.15f

        BeatShape.SPARKLE ->
            // Seeded on the beat index, so the same beat always lights the same
            // LEDs no matter how many frames it is rendered across.
            if (Random(sparkleSeed + beatCounter * 31 + index).nextFloat() < SPARKLE_DENSITY) 1f else 0f

        BeatShape.BAR_SWEEP -> {
            // The bar grows from the base with each beat of the bar and resets on
            // the downbeat, so a 4/4 bar reads as a rising then restarting sweep.
            val beatInBar = lastBeat?.beatInBar ?: 0
            val height = (beatInBar + 1) / BEATS_PER_BAR
            if (coord.y <= height) 1f else 0f
        }
    }

    private fun dropColour(index: Int, coord: LedCoord, level: Float, r: Int, g: Int, b: Int): Int =
        when (profile.drop) {
            DropBehaviour.NONE -> pack(r, g, b)

            DropBehaviour.WHITE_STROBE -> {
                val white = (255 * level).toInt()
                pack(maxOf(r, white), maxOf(g, white), maxOf(b, white))
            }

            DropBehaviour.PALETTE_BURST -> {
                // A wavefront leaving the base: at the moment of the drop it is at
                // the tip, and it collapses back over the drop window.
                val front = 1f - level
                val distance = abs(coord.y - front)
                val intensity = (1f - distance * BURST_SHARPNESS).coerceIn(0f, 1f) * level
                val burst = scale(paletteAt(level), intensity)
                pack(
                    maxOf(r, (burst shr 16) and 0xFF),
                    maxOf(g, (burst shr 8) and 0xFF),
                    maxOf(b, burst and 0xFF)
                )
            }

            DropBehaviour.INVERT -> {
                // Cross-fade towards the inverse so the drop is a jolt of contrast
                // rather than a brightness change.
                val inverse = 1f - level
                pack(
                    (r * inverse + (255 - r) * level).toInt(),
                    (g * inverse + (255 - g) * level).toInt(),
                    (b * inverse + (255 - b) * level).toInt()
                )
            }
        }

    // --- helpers ---

    /** Samples the palette as a looping gradient at `0..1`. */
    private fun paletteAt(position: Float): Int {
        val palette = profile.palette
        if (palette.size == 1) return palette[0]
        val wrapped = ((position % 1f) + 1f) % 1f
        val scaled = wrapped * palette.size
        val index = scaled.toInt() % palette.size
        val next = (index + 1) % palette.size
        return lerpColour(palette[index], palette[next], scaled - scaled.toInt())
    }

    private fun lerpColour(from: Int, to: Int, t: Float): Int = pack(
        (channel(from, 16) + (channel(to, 16) - channel(from, 16)) * t).toInt(),
        (channel(from, 8) + (channel(to, 8) - channel(from, 8)) * t).toInt(),
        (channel(from, 0) + (channel(to, 0) - channel(from, 0)) * t).toInt()
    )

    private fun scale(colour: Int, factor: Float): Int = pack(
        (channel(colour, 16) * factor).toInt(),
        (channel(colour, 8) * factor).toInt(),
        (channel(colour, 0) * factor).toInt()
    )

    private fun channel(colour: Int, shift: Int): Int = (colour shr shift) and 0xFF

    private fun pack(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val TWO_PI = (2 * PI).toFloat()

        /** How bright the between-beats animation sits under the beat flashes. */
        const val IDLE_LEVEL = 0.28f

        const val PALETTE_CYCLE_SECONDS = 12f
        const val GRADIENT_DRIFT_SECONDS = 6f
        const val BREATHE_RATE = 0.18f

        /** Fraction of a strobe period spent lit. */
        const val STROBE_DUTY = 0.45f

        /** Strobes dip rather than blacking out, so the tail never looks switched off. */
        const val STROBE_FLOOR = 0.12f

        const val SPARKLE_DENSITY = 0.25f
        const val BEATS_PER_BAR = 4f

        /** How tightly the palette burst's wavefront is focused. */
        const val BURST_SHARPNESS = 3f
    }
}
