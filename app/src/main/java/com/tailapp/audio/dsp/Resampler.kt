package com.tailapp.audio.dsp

import kotlin.math.floor

/**
 * Linear-interpolation sample-rate converter, stateful across chunks.
 *
 * A mic capture callback (or a test) hands audio to [resample] in whatever
 * pieces it happens to arrive in — one giant block, or dozens of tiny ones.
 * The output must not depend on that chunking: a click at every chunk
 * boundary would show up as spurious broadband energy in every downstream
 * feature, right where it matters least (onset detection is exactly the
 * thing that would false-trigger on it). This class buys that guarantee by
 * carrying two pieces of state between calls — the fractional read position
 * ([position]) and the last sample of the previous chunk ([previousSample])
 * — and treating each new chunk as a continuation of the same virtual index
 * space rather than resetting per call. See [resample] for the mechanics.
 *
 * **Why linear, and what it costs.** Linear interpolation is a first-order
 * hold between samples: one multiply-add per output sample, no lookahead
 * table, nothing beyond the two state fields above — deliberately the
 * cheapest resampler that can still be made click-free across chunks, which
 * is the MVP bar for this front end. The cost is aliasing: viewed as a
 * filter, linear interpolation's frequency response rolls off well before
 * Nyquist and its stopband rejection is poor, so input content above
 * roughly 0.4x the *output* Nyquist frequency folds back into the passband
 * as audible/measurable artefacts rather than being cleanly discarded. For
 * a beat-tracking front end whose features (onset flux, bass/mid/high
 * split, low-band-heavy spectral centroid) live well under that line this
 * is an acceptable trade; a polyphase or windowed-sinc resampler would be
 * the fix if this ever has to feed something that cares about the top
 * octave.
 *
 * @param inputSampleRate rate of the audio passed to [resample].
 * @param outputSampleRate rate of the audio [resample] writes into `out`.
 */
class Resampler(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int
) {
    init {
        require(inputSampleRate > 0) { "inputSampleRate must be positive" }
        require(outputSampleRate > 0) { "outputSampleRate must be positive" }
    }

    /** Input samples advanced per output sample. */
    private val step: Double = inputSampleRate.toDouble() / outputSampleRate.toDouble()

    // Position of the next output sample, in a virtual index space local to
    // "the current call's input array extended one sample into the past":
    // index -1 is previousSample, indices 0 until count are this call's
    // input. Carrying this across calls (instead of zeroing it per chunk) is
    // the entire trick: the sequence of `position += step` steps taken is
    // identical whether the audio arrives as one block or as many, because
    // each call simply picks up mid-sequence where the last one left off.
    private var position: Double = 0.0
    private var previousSample: Float = 0f

    /** Restarts the stream: the next call behaves as if audio starts from silence. */
    fun reset() {
        position = 0.0
        previousSample = 0f
    }

    /**
     * Resamples `input[0 until count]`, writing output samples into [out]
     * starting at index 0 until either [out] is full or the input runs out
     * (an output sample needs the input sample *after* its position, so the
     * last fractional sample of a chunk is always held back for next time —
     * that's what [previousSample] is for).
     *
     * @return how many samples were written to [out].
     */
    fun resample(input: FloatArray, count: Int = input.size, out: FloatArray): Int {
        require(count in 0..input.size) { "count ($count) out of range for input of size ${input.size}" }

        var written = 0
        while (written < out.size) {
            val idx = floor(position).toInt()
            if (idx >= count - 1) break // input[idx + 1] hasn't arrived yet.
            val frac = (position - idx).toFloat()
            val a = if (idx < 0) previousSample else input[idx]
            val b = input[idx + 1]
            out[written] = a + (b - a) * frac
            written++
            position += step
        }

        if (count > 0) previousSample = input[count - 1]
        // Rebase into the next call's virtual index space: this chunk's
        // `count` samples are about to become "the past" (index < 0) for it.
        position -= count
        return written
    }
}
