package com.tailapp.beat

import kotlin.math.exp
import kotlin.math.ln

/**
 * A configurable preference over which tempo *octave* the beat decoders settle
 * on, to steer the half/double ambiguity that no beat tracker can resolve from
 * the audio alone.
 *
 * It is a **prior, not a rule**. It multiplies each candidate tempo's likelihood
 * by a bell curve centred on [targetBpm], so among octaves the audio finds
 * equally plausible — 90 vs 180, say — the one nearer the target wins, while a
 * tempo the audio supports *clearly* still overrides it. That is what lets a
 * 180-BPM set stay at 180 without dragging a genuinely 80-BPM track up to 160:
 * the slow track's evidence for 80 is unambiguous and beats the prior, but an
 * ambiguous fast track's tie is broken toward the target.
 *
 * A mutable holder rather than an immutable value: the decoders read it every
 * frame, and the UI writes it live, so a change to the setting takes effect
 * without restarting the session. When [enabled] is false, [weight] is always 1
 * and every decoder behaves exactly as it did before this existed.
 *
 * **The three settings move together.** They used to be three separate
 * `@Volatile` fields, which meant a decoder reading them mid-update could see a
 * new `targetBpm` against an old `strength` — or, worse, `enabled` flipping true
 * before `targetBpm` had landed, applying a sharp prior centred on the previous
 * target. They are held in one immutable snapshot behind one volatile reference
 * instead, so [weight] always reads a self-consistent set.
 *
 * @param enabled whether the bias is applied at all.
 * @param targetBpm the tempo octave to prefer.
 * @param strength `0`..`1` — how sharply the preference peaks. Low is a broad
 *   nudge that only breaks near-ties; high pulls harder and will override
 *   weaker (but real) evidence, so it is the knob to reach for only when a set
 *   keeps landing on the wrong octave.
 */
class OctaveBias(
    enabled: Boolean = false,
    targetBpm: Float = DEFAULT_TARGET_BPM,
    strength: Float = DEFAULT_STRENGTH
) {
    private data class Settings(val enabled: Boolean, val targetBpm: Float, val strength: Float)

    @Volatile
    private var settings = Settings(enabled, targetBpm, strength)

    var enabled: Boolean
        get() = settings.enabled
        set(value) { settings = settings.copy(enabled = value) }

    var targetBpm: Float
        get() = settings.targetBpm
        set(value) { settings = settings.copy(targetBpm = value) }

    var strength: Float
        get() = settings.strength
        set(value) { settings = settings.copy(strength = value) }

    /**
     * Multiplier for a candidate tempo's likelihood — `1` at the target, falling
     * off by octave distance. Always `1` when disabled, so callers can multiply
     * unconditionally.
     */
    fun weight(bpm: Float): Float {
        // One read of the volatile: every field below comes from the same set.
        val current = settings
        if (!current.enabled || bpm <= 0f || current.targetBpm <= 0f) return 1f
        val width = MAX_WIDTH_OCTAVES +
            (MIN_WIDTH_OCTAVES - MAX_WIDTH_OCTAVES) * current.strength.coerceIn(0f, 1f)
        val octaves = (ln(bpm / current.targetBpm) / LN_2) / width
        return exp(-0.5f * octaves * octaves)
    }

    /** Applies every setting from [other] as one atomic change. */
    fun copyFrom(other: OctaveBias) {
        settings = other.settings
    }

    /** Applies all three settings at once; the only way to change more than one atomically. */
    fun set(enabled: Boolean, targetBpm: Float, strength: Float) {
        settings = Settings(enabled, targetBpm, strength)
    }

    companion object {
        const val DEFAULT_TARGET_BPM = 128f
        const val DEFAULT_STRENGTH = 0.5f

        /**
         * Tempo range the target may be set to — the same [TempoRange] the
         * decoders can represent. Offering a target outside it would be a slider
         * position no decoder could ever settle on.
         */
        const val MIN_TARGET_BPM = TempoRange.MIN_BPM
        const val MAX_TARGET_BPM = TempoRange.MAX_BPM

        /** Prior width at strength 0 (a gentle nudge) and strength 1 (a firm pull), in octaves. */
        private const val MAX_WIDTH_OCTAVES = 1.5f
        private const val MIN_WIDTH_OCTAVES = 0.35f

        private val LN_2 = ln(2f)
    }
}
