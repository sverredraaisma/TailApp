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
 * The DSP implementation ([SpectralFluxActivationSource]) is what ships today.
 * A BeatNet CRNN exported to ONNX is the intended second implementation: it
 * consumes the same [FeatureFrame] stream (which is why the front-end geometry
 * matches BeatNet's `log_spect.py` exactly) and produces the same two
 * probabilities, so [TempoEstimator] and [BeatTracker] need no changes to run on
 * top of it.
 *
 * Implementations are stateful and frame-ordered: one instance per session,
 * driven strictly in frame order.
 */
interface ActivationSource {
    fun activation(frame: FeatureFrame): BeatActivation

    fun reset()
}
