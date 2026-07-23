package com.tailapp.effects

/** What a beat does to the strip. */
enum class BeatShape {
    /** Every LED flashes together. */
    FULL,

    /** A pulse travels along the tail, one ring per beat. */
    RING_CHASE,

    /** Odd and even rings alternate on successive beats. */
    ALTERNATE_RINGS,

    /** Random LEDs light and decay. */
    SPARKLE,

    /** A bar grows from the base on each beat and falls back. */
    BAR_SWEEP
}

/** What the strip does between beats. */
enum class IdleAnimation {
    /** Slowly cycles through the profile palette. */
    PALETTE_CYCLE,

    /** Holds the palette's first colour and breathes its brightness. */
    BREATHE,

    /** A palette gradient drifts along the tail. */
    GRADIENT_DRIFT,

    /** Dark between beats. */
    OFF
}

/** What a drop does. */
enum class DropBehaviour {
    /** Full-strip white strobe, decaying over the drop window. */
    WHITE_STROBE,

    /** Saturated palette burst travelling outwards from the base. */
    PALETTE_BURST,

    /** Palette inverts for the drop window. */
    INVERT,

    /** Drops are ignored by this profile. */
    NONE
}

/**
 * A tunable, purely declarative lighting profile.
 *
 * Profiles are data, not code: `ReactiveRenderer` interprets them, so a new look
 * is a new [EffectProfile] value rather than a new render path. `EffectController`
 * picks one by matching [genreMatchers] against the debounced genre label.
 *
 * @param id stable key, also used for persistence.
 * @param displayName label shown in the UI.
 * @param genreMatchers case-insensitive substrings matched against the genre
 *   label; the first profile with a match wins. Empty means "never auto-selected"
 *   (manual override only).
 * @param palette packed `0xRRGGBB` colours the profile draws from, at least one.
 * @param idle animation between beats.
 * @param beatShape what a beat looks like.
 * @param beatDecaySeconds time for a beat flash to fall back to the idle level.
 * @param downbeatBoost multiplier applied to a downbeat's brightness, `>= 1`.
 * @param drop what a drop looks like.
 * @param dropSeconds how long a drop response lasts.
 * @param buildupStrobeStartHz strobe rate at the start of a build-up.
 * @param buildupStrobeEndHz strobe rate at the top of a build-up; the renderer
 *   ramps between the two using [com.tailapp.drop.SectionStateUpdate.ramp].
 * @param breakdownBrightness brightness multiplier during a breakdown, `0..1`.
 * @param brightness overall brightness multiplier, `0..1`.
 */
data class EffectProfile(
    val id: String,
    val displayName: String,
    val genreMatchers: List<String>,
    val palette: List<Int>,
    val idle: IdleAnimation,
    val beatShape: BeatShape,
    val beatDecaySeconds: Float,
    val downbeatBoost: Float,
    val drop: DropBehaviour,
    val dropSeconds: Float,
    val buildupStrobeStartHz: Float,
    val buildupStrobeEndHz: Float,
    val breakdownBrightness: Float,
    val brightness: Float
) {
    init {
        require(palette.isNotEmpty()) { "profile $id needs at least one palette colour" }
        require(beatDecaySeconds > 0f) { "beatDecaySeconds must be positive" }
        require(downbeatBoost >= 1f) { "downbeatBoost must be >= 1" }
    }

    /** True when [label] selects this profile. */
    fun matches(label: String): Boolean {
        val lower = label.lowercase()
        return genreMatchers.any { lower.contains(it.lowercase()) }
    }
}
