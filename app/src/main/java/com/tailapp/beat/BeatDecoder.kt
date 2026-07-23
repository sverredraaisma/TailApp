package com.tailapp.beat

import com.tailapp.audio.FeatureFrame

/**
 * The back half of beat tracking: activation curve in, [BeatEvent]s out.
 *
 * Two implementations satisfy this contract and they are meant to be swapped
 * without anything downstream noticing:
 *
 * - [BeatTracker] — the shipped MVP. A phase-locked predictor: one tempo, one
 *   phase, corrected towards nearby onsets.
 * - [ParticleFilterBeatDecoder] — a Kotlin port of BeatNet's two-stage cascade
 *   particle filter, which carries hundreds of competing tempo/phase hypotheses
 *   instead of one.
 *
 * ### Why there are two `process` overloads
 *
 * The activation source has to be owned *somewhere*, and each option costs
 * something:
 *
 * - If the decoder owns it, a harness that wants to compare two decoders on
 *   *identical* input cannot guarantee it: both would run their own
 *   [ActivationSource] instance, and any statefulness in the source (all of them
 *   are stateful — [SpectralFluxActivationSource] carries adaptive statistics)
 *   makes "the same signal" an assumption rather than a fact.
 * - If something above owns it, every call site has to be rewritten, including
 *   `LightingEngine`, which belongs to a different part of the codebase and has
 *   no reason to learn about activations.
 *
 * So both: [process] with an explicit [BeatActivation] is the real contract — it
 * is what the comparison harness and every decoder test drive, and it makes the
 * decoder a pure function of the activation curve. [process] with a frame alone
 * is the convenience the pipeline uses; a decoder implements it by feeding the
 * frame through the [ActivationSource] it was constructed with. `LightingEngine`
 * therefore keeps its one-argument call site unchanged.
 *
 * Implementations are stateful and frame-ordered: one instance per session,
 * driven strictly in frame order, never concurrently.
 *
 * ### The forward-prediction contract
 *
 * A [BeatEvent] may be emitted *before* the instant it describes, and is stamped
 * with that instant rather than with "now". Lighting has to cross a BLE link and
 * a 30 fps render loop, so a beat reported at the moment it happens is already
 * late. Every implementation must honour this — downstream code schedules against
 * `timestampNanos` and a negative trigger offset is expected to be able to fire
 * genuinely early.
 */
interface BeatDecoder {

    /**
     * Feeds one frame together with the activation computed for it.
     *
     * @return the beats this frame produced — usually empty, occasionally one.
     */
    fun process(frame: FeatureFrame, activation: BeatActivation): List<BeatEvent>

    /**
     * Feeds one frame, computing the activation with the decoder's own
     * [ActivationSource].
     */
    fun process(frame: FeatureFrame): List<BeatEvent>

    /** Drops all state, including the activation source's. */
    fun reset()

    /** Current tempo estimate in BPM, or 0 before the decoder has locked on. */
    val bpm: Float

    /** `0..1` — how strongly the decoder believes its own beat phase. */
    val confidence: Float

    /** Wall-clock time of the next predicted beat, or null when not locked on. */
    val nextBeatTimestampNanos: Long?
}
