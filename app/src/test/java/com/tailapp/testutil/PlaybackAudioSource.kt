package com.tailapp.testutil

import com.tailapp.audio.AudioSource

/**
 * An [AudioSource] that plays back a fixed buffer, handing out at most
 * [maxSamplesPerRead] samples per call.
 *
 * Deliberately not real-time: it returns audio as fast as it is asked for, so a
 * test can push a whole track through the pipeline in milliseconds. The chunk
 * limit is what makes it realistic in the way that matters — the pipeline must
 * cope with arbitrary read sizes that do not line up with the analysis hop.
 */
class PlaybackAudioSource(
    private val samples: FloatArray,
    override val sampleRate: Int,
    private val maxSamplesPerRead: Int = 512
) : AudioSource {

    private var position = 0
    private var started = false

    override val isRunning: Boolean get() = started
    override val overrunCount: Long = 0L
    override val latencyMillis: Float = 0f

    /** True once the whole buffer has been handed out. */
    val isExhausted: Boolean get() = position >= samples.size

    override fun start() {
        started = true
        position = 0
    }

    override fun stop() {
        started = false
    }

    override fun read(out: FloatArray, count: Int): Int {
        if (!started) return 0
        val n = minOf(count, out.size, maxSamplesPerRead, samples.size - position)
        if (n <= 0) return 0
        samples.copyInto(out, 0, position, position + n)
        position += n
        return n
    }
}
