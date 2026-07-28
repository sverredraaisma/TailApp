package com.tailapp.beat

/**
 * The tempo bounds the beat tier agrees on.
 *
 * There used to be three, and they disagreed by accident: [TempoEstimator]
 * searched 60-200, [ParticleFilterBeatDecoder] represented 55-215, and
 * [OctaveBias]'s target slider offered 60-200. A tempo one component can
 * represent and another cannot is not a tuning difference, it is a decoder that
 * silently cannot agree with itself.
 *
 * There are now two, and the difference is deliberate and measured.
 *
 * [MIN_BPM]/[MAX_BPM] are what the tier can **represent** — BeatNet's bounds,
 * which is where the particle filter's came from. 215 is load-bearing: it is
 * what makes 128 BPM's double unrepresentable.
 *
 * [SEARCH_MIN_BPM]/[SEARCH_MAX_BPM] are what [TempoEstimator]'s autocorrelation
 * can actually **resolve**, and they are narrower. The estimator scores a
 * three-harmonic comb weighted by a log-normal prior centred at 120 BPM; both
 * are tuned for this band, and widening the search to the representable bounds
 * measurably loses the lock — the whole of `BeatTrackerTest` fails at 55-215,
 * including a clean 128 BPM grid, because the extra lags reshape the
 * peak-to-average ratio the acquisition gate reads. Widening this band is a
 * comb-and-prior redesign, not a constant change, and it needs the ±2 BPM
 * suites re-derived rather than relaxed.
 *
 * The estimator's band edges are still interpolable despite being edges, because
 * it scores one guard lag either side (see `lowGuardLag`/`highGuardLag`) — that
 * is what fixed the original complaint that 60 BPM sat exactly on the longest
 * lag where peak interpolation refuses to run.
 */
object TempoRange {
    /** Slowest tempo the tier can represent. */
    const val MIN_BPM = 55f

    /** Fastest tempo the tier can represent. */
    const val MAX_BPM = 215f

    /** Slowest tempo [TempoEstimator] can resolve. See the class KDoc. */
    const val SEARCH_MIN_BPM = 60f

    /** Fastest tempo [TempoEstimator] can resolve. See the class KDoc. */
    const val SEARCH_MAX_BPM = 200f
}
