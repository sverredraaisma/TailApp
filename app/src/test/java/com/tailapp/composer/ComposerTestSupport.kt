package com.tailapp.composer

import com.tailapp.beat.BeatEvent
import com.tailapp.drop.DropEvent
import com.tailapp.drop.SectionState
import com.tailapp.genre.GenreState
import com.tailapp.model.BlendMode

/**
 * Builds a [ReactiveContext] with everything defaulted to "nothing is happening",
 * so a test states only the inputs it cares about.
 *
 * This is what makes the effect library testable without a microphone: an effect
 * reads the context and nothing else, so a hand-built one is a complete stand-in
 * for the whole analysis pipeline.
 */
internal fun testContext(
    nowNanos: Long = 0L,
    timeSeconds: Float = 0f,
    dtSeconds: Float = 0f,
    bpm: Float = 0f,
    lastBeat: BeatEvent? = null,
    beatCount: Int = 0,
    secondsSinceBeat: Float = ReactiveContext.NO_EVENT_SECONDS,
    beatPhase: Float = 0f,
    barPhase: Float = 0f,
    onDownbeat: Boolean = false,
    level: Float = 0f,
    rms: Float = 0f,
    bass: Float = 0f,
    mid: Float = 0f,
    high: Float = 0f,
    bands: FloatArray = FloatArray(0),
    lastDrop: DropEvent? = null,
    secondsSinceDrop: Float = ReactiveContext.NO_EVENT_SECONDS,
    section: SectionState = SectionState.UNKNOWN,
    sectionRamp: Float = 0f,
    genre: GenreState = GenreState.unknown(),
    // "Nobody has touched it and it is hanging still" — the tail equivalent of
    // silence, so an effect test states only the body state it cares about.
    lastTap: TapEvent? = null,
    secondsSinceTap: Float = ReactiveContext.NO_EVENT_SECONDS,
    tapCount: Int = 0,
    tail: TailTelemetry = TailTelemetry.AT_REST
): ReactiveContext = ReactiveContext(
    nowNanos = nowNanos,
    timeSeconds = timeSeconds,
    dtSeconds = dtSeconds,
    bpm = bpm,
    lastBeat = lastBeat,
    beatCount = beatCount,
    secondsSinceBeat = secondsSinceBeat,
    beatPhase = beatPhase,
    barPhase = barPhase,
    onDownbeat = onDownbeat,
    level = level,
    rms = rms,
    bass = bass,
    mid = mid,
    high = high,
    bands = bands,
    lastDrop = lastDrop,
    secondsSinceDrop = secondsSinceDrop,
    section = section,
    sectionRamp = sectionRamp,
    genre = genre,
    lastTap = lastTap,
    secondsSinceTap = secondsSinceTap,
    tapCount = tapCount,
    tail = tail
)

/** A tap that landed [secondsAgo] on [end], with the matching elapsed time. */
internal fun tappedContext(
    end: TailEnd = TailEnd.BASE,
    secondsAgo: Float = 0f,
    tail: TailTelemetry = TailTelemetry.AT_REST,
    nowNanos: Long = 0L
): ReactiveContext = testContext(
    nowNanos = nowNanos,
    lastTap = TapEvent(end, nowNanos - (secondsAgo * 1_000_000_000f).toLong()),
    secondsSinceTap = secondsAgo,
    tapCount = 1,
    tail = tail
)

/** A solid-colour layer — the one effect whose output is exactly predictable. */
internal fun solidLayer(
    id: String,
    colour: Int,
    blend: BlendMode = BlendMode.OVERWRITE,
    opacity: Float = 1f,
    enabled: Boolean = true
) = EffectLayer(
    id = id,
    name = "Solid $id",
    effectId = "solid",
    params = mapOf(
        "color" to colour.toFloat(),
        "brightness" to 1f,
        "levelBoost" to 0f
    ),
    blendMode = blend,
    opacity = opacity,
    enabled = enabled
)

/**
 * A modulator that emits a constant grey, independent of the audio: a
 * [com.tailapp.composer.effects.VolumeDimmerEffect] whose floor *is* the output
 * when the level is zero.
 */
internal fun dimmerLayer(
    id: String,
    floor: Float,
    blend: BlendMode = BlendMode.MULTIPLY
) = EffectLayer(
    id = id,
    name = "Dimmer $id",
    effectId = "volume_dimmer",
    params = mapOf("source" to 0f, "gain" to 1f, "floor" to floor, "invert" to 0f),
    blendMode = blend
)
