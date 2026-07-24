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
 * @param enabled whether the bias is applied at all.
 * @param targetBpm the tempo octave to prefer.
 * @param strength `0`..`1` — how sharply the preference peaks. Low is a broad
 *   nudge that only breaks near-ties; high pulls harder and will override
 *   weaker (but real) evidence, so it is the knob to reach for only when a set
 *   keeps landing on the wrong octave.
 */
class OctaveBias(
    @Volatile var enabled: Boolean = false,
    @Volatile var targetBpm: Float = DEFAULT_TARGET_BPM,
    @Volatile var strength: Float = DEFAULT_STRENGTH
) {
    /**
     * Multiplier for a candidate tempo's likelihood — `1` at the target, falling
     * off by octave distance. Always `1` when disabled, so callers can multiply
     * unconditionally.
     */
    fun weight(bpm: Float): Float {
        if (!enabled || bpm <= 0f || targetBpm <= 0f) return 1f
        val width = MAX_WIDTH_OCTAVES + (MIN_WIDTH_OCTAVES - MAX_WIDTH_OCTAVES) * strength.coerceIn(0f, 1f)
        val octaves = (ln(bpm / targetBpm) / LN_2) / width
        return exp(-0.5f * octaves * octaves)
    }

    fun copyFrom(other: OctaveBias) {
        enabled = other.enabled
        targetBpm = other.targetBpm
        strength = other.strength
    }

    companion object {
        const val DEFAULT_TARGET_BPM = 128f
        const val DEFAULT_STRENGTH = 0.5f

        /** Tempo range the target may be set to. */
        const val MIN_TARGET_BPM = 60f
        const val MAX_TARGET_BPM = 200f

        /** Prior width at strength 0 (a gentle nudge) and strength 1 (a firm pull), in octaves. */
        private const val MAX_WIDTH_OCTAVES = 1.5f
        private const val MIN_WIDTH_OCTAVES = 0.35f

        private val LN_2 = ln(2f)
    }
}
