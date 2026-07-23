package com.tailapp.effects

/**
 * The built-in lighting profiles and the genre labels that select them.
 *
 * Matching is by lowercase substring against whatever the classifier reports, so
 * these work with Discogs-style compound labels (`Electronic---Hardstyle`) as
 * well as with a plain genre name. The first profile whose matchers hit wins,
 * which is why the list is ordered from most specific to most general —
 * `hardstyle` has to be tested before `hard`, and everything before [DEFAULT].
 *
 * Profiles are data. Adding a look means adding an entry here (or, later, a row
 * the user edits in the profile editor); it never means touching the renderer.
 */
object EffectProfiles {

    /** Used when nothing matches, and before the classifier has said anything. */
    val DEFAULT = EffectProfile(
        id = "default",
        displayName = "Default",
        genreMatchers = emptyList(),
        palette = listOf(0x00A0FF, 0xFF00C8, 0xFFFFFF),
        idle = IdleAnimation.PALETTE_CYCLE,
        beatShape = BeatShape.FULL,
        beatDecaySeconds = 0.30f,
        downbeatBoost = 1.35f,
        drop = DropBehaviour.WHITE_STROBE,
        dropSeconds = 2.0f,
        buildupStrobeStartHz = 2f,
        buildupStrobeEndHz = 12f,
        breakdownBrightness = 0.4f,
        brightness = 1f
    )

    /** Long build-ups, melodic breakdowns: motion along the tail rather than flashes. */
    val TRANCE = EffectProfile(
        id = "trance",
        displayName = "Trance",
        genreMatchers = listOf("trance", "psytrance", "goa"),
        palette = listOf(0x1040FF, 0x00D0FF, 0x8020FF, 0xFFFFFF),
        idle = IdleAnimation.GRADIENT_DRIFT,
        beatShape = BeatShape.RING_CHASE,
        beatDecaySeconds = 0.35f,
        downbeatBoost = 1.4f,
        drop = DropBehaviour.PALETTE_BURST,
        dropSeconds = 2.5f,
        buildupStrobeStartHz = 1.5f,
        buildupStrobeEndHz = 12f,
        breakdownBrightness = 0.35f,
        brightness = 1f
    )

    /** Hard kicks, short envelopes, no idle glow to dilute the hit. */
    val HARDSTYLE = EffectProfile(
        id = "hardstyle",
        displayName = "Hardstyle",
        genreMatchers = listOf("hardstyle", "hardcore", "gabber", "hard dance", "rawstyle"),
        palette = listOf(0xFF2000, 0xFF8000, 0xFFFFFF),
        idle = IdleAnimation.OFF,
        beatShape = BeatShape.FULL,
        beatDecaySeconds = 0.12f,
        downbeatBoost = 1.6f,
        drop = DropBehaviour.WHITE_STROBE,
        dropSeconds = 2.0f,
        buildupStrobeStartHz = 4f,
        buildupStrobeEndHz = 18f,
        breakdownBrightness = 0.25f,
        brightness = 1f
    )

    /** Steady four-to-the-floor: alternating rings read as a groove, not a strobe. */
    val HOUSE_TECHNO = EffectProfile(
        id = "house_techno",
        displayName = "House / Techno",
        genreMatchers = listOf("house", "techno", "tech house", "electro", "disco"),
        palette = listOf(0xFF6000, 0xFF00A0, 0x30FFC0),
        idle = IdleAnimation.PALETTE_CYCLE,
        beatShape = BeatShape.ALTERNATE_RINGS,
        beatDecaySeconds = 0.25f,
        downbeatBoost = 1.3f,
        drop = DropBehaviour.PALETTE_BURST,
        dropSeconds = 1.8f,
        buildupStrobeStartHz = 2f,
        buildupStrobeEndHz = 10f,
        breakdownBrightness = 0.4f,
        brightness = 1f
    )

    /** Syncopated and fast: a sweep tracks the break better than a full flash. */
    val BASS = EffectProfile(
        id = "bass",
        displayName = "Drum & Bass / Dubstep",
        genreMatchers = listOf("drum n bass", "drum and bass", "jungle", "dubstep", "breakbeat", "bass"),
        palette = listOf(0x40FF00, 0x00FFA0, 0xFFFFFF),
        idle = IdleAnimation.GRADIENT_DRIFT,
        beatShape = BeatShape.BAR_SWEEP,
        beatDecaySeconds = 0.18f,
        downbeatBoost = 1.5f,
        drop = DropBehaviour.INVERT,
        dropSeconds = 1.5f,
        buildupStrobeStartHz = 3f,
        buildupStrobeEndHz = 14f,
        breakdownBrightness = 0.3f,
        brightness = 1f
    )

    /** Quiet material: slow, dim, and no drop response worth the name. */
    val AMBIENT = EffectProfile(
        id = "ambient",
        displayName = "Ambient / Downtempo",
        genreMatchers = listOf("ambient", "downtempo", "chill", "classical", "folk", "jazz"),
        palette = listOf(0x2060FF, 0x00C0A0, 0x8040FF),
        idle = IdleAnimation.BREATHE,
        beatShape = BeatShape.SPARKLE,
        beatDecaySeconds = 0.9f,
        downbeatBoost = 1.15f,
        drop = DropBehaviour.NONE,
        dropSeconds = 1f,
        buildupStrobeStartHz = 0.5f,
        buildupStrobeEndHz = 3f,
        breakdownBrightness = 0.6f,
        brightness = 0.7f
    )

    /** Ordered most specific first — [forLabel] returns the first match. */
    val ALL: List<EffectProfile> = listOf(HARDSTYLE, TRANCE, BASS, HOUSE_TECHNO, AMBIENT, DEFAULT)

    /** The profile a genre label selects, or [DEFAULT] when nothing matches. */
    fun forLabel(label: String): EffectProfile =
        ALL.firstOrNull { it.genreMatchers.isNotEmpty() && it.matches(label) } ?: DEFAULT

    /** Looks a profile up by [EffectProfile.id], for restoring a manual override. */
    fun byId(id: String): EffectProfile? = ALL.firstOrNull { it.id == id }
}
