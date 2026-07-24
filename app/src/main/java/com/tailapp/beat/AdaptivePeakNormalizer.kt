package com.tailapp.beat

import kotlin.math.exp
import kotlin.math.max

/**
 * Rescales a stream so its recent peaks map to ~1, whatever their absolute level.
 *
 * A neural beat model is confident on the material it was trained on and hedges
 * on everything else. On a phone mic — a quiet, reverberant, out-of-distribution
 * signal — BeatNet's CRNN was measured to peak at only ~0.15 above a ~0.07 floor,
 * a 2:1 contrast the beat decoders cannot concentrate on. The *periodicity* is
 * still there; only the amplitude is missing. Dividing by a slow-decaying peak
 * restores the contrast — the strongest recent beat maps to 1, the lulls to
 * near 0 — which is what the spectral-flux activation already does for itself
 * through its adaptive z-score, and is why that path never needed this.
 *
 * Genuine silence normalises to a flat, structureless ~1 rather than to zero, and
 * that is harmless: a flat activation gives every beat phase the same weight, so
 * the posterior never concentrates and nothing locks. Erring toward passing the
 * signal through is the safe direction — the failure that mattered was a live
 * beat too faint to track.
 *
 * @param halfLifeSeconds how fast the reference peak forgets a loud moment. Long
 *   enough to bridge a bar so a soft beat is still measured against a recent
 *   strong one; short enough to follow the track's own dynamics.
 * @param framesPerSecond the rate [normalize] is called at.
 */
class AdaptivePeakNormalizer(
    halfLifeSeconds: Float = 2.5f,
    framesPerSecond: Float = 50f
) {
    private val decay: Float = exp(-1f / (halfLifeSeconds * framesPerSecond))
    private var peak = 0f

    /** Peak the last [normalize] measured against, for diagnostics. */
    val currentPeak: Float get() = peak

    fun normalize(value: Float): Float {
        val v = if (value > 0f) value else 0f
        peak = max(v, peak * decay)
        if (peak < EPSILON) return 0f
        val normalized = v / peak
        return if (normalized > 1f) 1f else normalized
    }

    fun reset() {
        peak = 0f
    }

    private companion object {
        /** Below this the reference is treated as no signal at all. */
        const val EPSILON = 1e-5f
    }
}
