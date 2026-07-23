package com.tailapp.drop

/**
 * Tunables for the transient tier.
 *
 * Deliberately a config object rather than constants: a detector tuned on trance
 * over-fires on drum and bass and under-fires on ambient, and the fix for that
 * has to be a slider in the calibration screen, not a rebuild.
 *
 * Every threshold that describes a *level* is a z-score against the detector's
 * own trailing window, so none of them encode an absolute loudness.
 *
 * @param statsRateHz how often the detector evaluates, in Hz. The plan's
 *   100-200 ms resolution; feature frames arrive far faster and are averaged
 *   down to this rate.
 * @param historySeconds trailing window the z-scores are measured against.
 * @param warmupSeconds history required before the detector is allowed to fire.
 * @param dropBroadbandZ how far broadband RMS must exceed its trailing mean.
 * @param dropBassZ same for low-band energy.
 * @param dropBassRiseRatio the low band must jump by this multiple against the
 *   *immediately* preceding fraction of a second. Against a steady baseline even
 *   a gentle swell scores a large z-score within a second, so the z-scores alone
 *   cannot tell a drop from a fade-in; this is what insists on a step.
 * @param dropRefractorySeconds minimum gap between drops.
 * @param dropIntensityRatio how many times its trailing mean the broadband level
 *   must reach to count as a full-intensity drop. Ratios, not z-scores: against
 *   a very steady window the z-score saturates immediately and every drop would
 *   report intensity 1.
 * @param onsetFluxZ spectral-flux z-score that counts a frame as an onset.
 * @param onsetDensityWindowSeconds window the onset rate is measured over.
 * @param buildupRiseRatio energy rise over [buildupRampSeconds] that reads as a
 *   build-up.
 * @param buildupRampSeconds how long a typical build-up runs; also the scale for
 *   [SectionStateUpdate.ramp].
 * @param breakdownRmsZ RMS z-score below which the track reads as broken down.
 * @param dropHoldSeconds how long a drop keeps the section in
 *   [SectionState.DROP] without further evidence.
 * @param sectionDebounceSeconds a candidate section must hold this long before
 *   it is committed — the same anti-flicker rule genre switching uses.
 * @param outroSeconds low energy for this long, after high energy, reads as an
 *   outro rather than a breakdown.
 * @param introSeconds a quiet opening within this long of the session start is
 *   an intro.
 */
data class TransientConfig(
    val statsRateHz: Float = 10f,
    val historySeconds: Float = 45f,
    val warmupSeconds: Float = 8f,
    val dropBroadbandZ: Float = 1.8f,
    val dropBassZ: Float = 1.5f,
    val dropBassRiseRatio: Float = 1.4f,
    val dropRefractorySeconds: Float = 4f,
    val dropIntensityRatio: Float = 8f,
    val onsetFluxZ: Float = 1.2f,
    val onsetDensityWindowSeconds: Float = 2f,
    val buildupRiseRatio: Float = 1.25f,
    val buildupRampSeconds: Float = 16f,
    val breakdownRmsZ: Float = -0.7f,
    val dropHoldSeconds: Float = 8f,
    val sectionDebounceSeconds: Float = 1.5f,
    val outroSeconds: Float = 25f,
    val introSeconds: Float = 30f
) {
    init {
        require(statsRateHz > 0f) { "statsRateHz must be positive" }
        require(historySeconds > warmupSeconds) { "history must be longer than warmup" }
        require(warmupSeconds > 0f) { "warmupSeconds must be positive" }
        require(dropIntensityRatio > 1f) { "dropIntensityRatio must be greater than 1" }
        require(buildupRampSeconds > 0f) { "buildupRampSeconds must be positive" }
    }

    /** Stats-rate samples held in the trailing window. */
    val historySamples: Int get() = (historySeconds * statsRateHz).toInt().coerceAtLeast(2)

    /** Stats-rate samples required before the detector trusts its own z-scores. */
    val warmupSamples: Int get() = (warmupSeconds * statsRateHz).toInt().coerceAtLeast(2)

    /** Stats-rate samples in the onset-density window. */
    val onsetWindowSamples: Int
        get() = (onsetDensityWindowSeconds * statsRateHz).toInt().coerceAtLeast(1)
}
