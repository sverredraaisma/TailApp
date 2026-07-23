package com.tailapp.beat

import com.tailapp.audio.FeatureFrame

/**
 * Per-frame likelihood that a beat is happening.
 *
 * @param beat `0..1` — how strongly this frame looks like a beat.
 * @param downbeat `0..1` — how strongly it looks like the *first* beat of a bar.
 *   Used to pick the bar phase, not to detect beats.
 */
data class BeatActivation(val beat: Float, val downbeat: Float)

/**
 * Turns feature frames into beat activations — the front half of beat tracking,
 * kept behind an interface because it is the half a neural model replaces.
 *
 * [SpectralFluxActivationSource] is the one implementation, and the fallback
 * whenever the CRNN is not running.
 *
 * **[CrnnActivationSource] deliberately does not implement this.** It produces
 * the same [BeatActivation], but from a
 * [com.tailapp.audio.BeatNetFrame] rather than a [FeatureFrame]: the model was
 * trained on a different spectrogram (136 unit-area bands from a 1411-sample
 * centred window, stacked with their positive difference) and feeding it this
 * one was measured to produce half-time beats and a collapsed downbeat channel.
 * Making it fit this interface would mean making that mistake type-check. Both
 * kinds of activation reach the decoders the same way — through
 * [BeatDecoder.process] with an explicit activation — so [TempoEstimator] and
 * [BeatTracker] still need no changes to run on either.
 *
 * Implementations are stateful and frame-ordered: one instance per session,
 * driven strictly in frame order.
 */
interface ActivationSource {
    fun activation(frame: FeatureFrame): BeatActivation

    fun reset()
}
