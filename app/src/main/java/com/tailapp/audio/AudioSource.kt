package com.tailapp.audio

/**
 * A running mono PCM source the analysis pipeline drains at its own pace.
 *
 * Implementations must never block inside their producer (an Oboe audio callback
 * or an `AudioRecord` reader thread): they copy into a ring buffer and return.
 * [read] is therefore non-blocking and may return fewer samples than asked for.
 *
 * Samples are normalised floats in `[-1, 1]`.
 */
interface AudioSource {
    /** Sample rate of the samples handed out by [read]. */
    val sampleRate: Int

    /** True between a successful [start] and the next [stop]. */
    val isRunning: Boolean

    /**
     * Samples the producer had to discard because the consumer fell behind.
     * Monotonic across a session; reset by [start].
     */
    val overrunCount: Long

    /** Best-effort input latency in milliseconds, or 0 when the source cannot report it. */
    val latencyMillis: Float

    /**
     * Opens the capture stream.
     *
     * @throws SecurityException when `RECORD_AUDIO` has not been granted.
     * @throws IllegalStateException when the underlying stream cannot be opened.
     */
    fun start()

    /** Closes the capture stream. Safe to call when not running. */
    fun stop()

    /**
     * Copies at most [count] pending samples into [out], returning how many were
     * written. Returns 0 when no new audio is available — callers poll.
     */
    fun read(out: FloatArray, count: Int = out.size): Int
}
