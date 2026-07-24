package com.tailapp.led

/**
 * Kotlin mirror of `FftBuffer` (`main/config/fft_buffer.h` / `.cpp`): the
 * audio-reactive LED effects read loudness/bin data through this, the same
 * way the firmware effects read `FftBuffer::instance()`.
 *
 * The firmware double-buffers `fft_data_t` and flips a single-byte read index
 * so the BLE task (writer) and LED render task (reader) never tear a read.
 * On the JVM, atomically swapping a single immutable snapshot reference gives
 * the same guarantee - a reader either sees the old frame or the new one in
 * full, never a mix - with less code than a literal two-slot buffer, so
 * that's the implementation here rather than a port of the index-flip trick.
 *
 * Pure JVM: no `android.*` imports, so this is usable from unit tests and
 * production code alike. [clock] defaults to [System.nanoTime] but is
 * injectable so tests can move time deterministically without real delays.
 */
class AudioLevelSource(private val clock: () -> Long = System::nanoTime) {

    private class Snapshot(
        val loudness: Int,
        val bins: ByteArray,
        val timestampNanos: Long,
        val beat: BeatInfo? = null
    )

    @Volatile
    private var snapshot = Snapshot(loudness = 0, bins = EMPTY_BINS, timestampNanos = 0L)

    // The firmware treats `timestamp_us == 0` as "never written". A fake clock
    // in tests can legitimately start at 0 too, so a written flag is used
    // instead of trusting the timestamp's zero-ness as the sentinel.
    @Volatile
    private var hasWritten = false

    /** Records a new FFT frame. Mirrors `FftBuffer::write`. */
    fun write(loudness: Int, bins: ByteArray, beat: BeatInfo? = null) {
        val numBins = bins.size.coerceAtMost(MAX_FFT_BINS)
        // uint8_t loudness truncates on assignment in the firmware; masking
        // reproduces that instead of silently accepting an out-of-range value.
        snapshot = Snapshot(
            loudness = loudness and 0xFF,
            bins = bins.copyOf(numBins),
            timestampNanos = clock(),
            beat = beat
        )
        if (beat != null) {
            // Latched, and taken once: the firmware's motion loop polls at
            // 100 Hz against a 30 fps stream, so a beat left set would retrigger
            // on every cycle until the next frame arrived.
            if (beat.onBeat) beatPending = true
            if (beat.onDownbeat) downbeatPending = true
        }
        hasWritten = true
    }

    /**
     * Beat information the app streams alongside the spectrum, mirroring
     * `fft_beat_t`. Null when no trailer was sent — which the effects must read
     * as "no beat known" rather than "a beat at phase 0".
     */
    class BeatInfo(
        val phase: Float,
        val bpm: Float,
        val onBeat: Boolean,
        val onDownbeat: Boolean,
        val onDrop: Boolean
    )

    @Volatile
    private var beatPending = false

    @Volatile
    private var downbeatPending = false

    /** Mirrors `FftBuffer::has_beat_info`. */
    val hasBeatInfo: Boolean
        get() = isFresh && snapshot.beat != null

    /** Mirrors `FftBuffer::get_beat_phase`. */
    val beatPhase: Float
        get() = if (hasBeatInfo) snapshot.beat!!.phase else 0f

    /** Mirrors `FftBuffer::get_bpm`. */
    val bpm: Float
        get() = if (hasBeatInfo) snapshot.beat!!.bpm else 0f

    /** Mirrors `FftBuffer::take_beat` — one-shot, cleared on read. */
    fun takeBeat(): Boolean {
        val v = beatPending
        beatPending = false
        return v
    }

    /** Mirrors `FftBuffer::take_downbeat`. */
    fun takeDownbeat(): Boolean {
        val v = downbeatPending
        downbeatPending = false
        return v
    }

    /** Mirrors `FftBuffer::is_fresh`: within the 200ms staleness window. */
    val isFresh: Boolean
        get() = hasWritten && (clock() - snapshot.timestampNanos) < STALE_THRESHOLD_NANOS

    /** Raw loudness (0-255). 0 if stale. Mirrors `FftBuffer::get_loudness`. */
    val loudness: Int
        get() = if (isFresh) snapshot.loudness else 0

    /** Mirrors `FftBuffer::get_loudness_normalized`. */
    val loudnessNormalized: Float
        get() = loudness / 255.0f

    /**
     * Bin count of the last frame. Mirrors `FftBuffer::get_num_bins` -
     * including its asymmetry with [bin]/[loudness]: this does *not* gate on
     * freshness in the firmware, so it doesn't here either. That's harmless
     * in practice because every firmware effect only reaches this from inside
     * an `is_fresh()`-guarded block already (see `audio_freq_bars_effect.cpp`).
     */
    val numBins: Int
        get() = snapshot.bins.size

    /** A frequency bin value (0-255). 0 if out of range or stale. Mirrors `FftBuffer::get_bin`. */
    fun bin(index: Int): Int {
        if (!isFresh) return 0
        val bins = snapshot.bins
        if (index < 0 || index >= bins.size) return 0
        return bins[index].toInt() and 0xFF
    }

    companion object {
        /** `MAX_FFT_BINS` (`config_types.h`). */
        const val MAX_FFT_BINS = 128

        /** `STALE_THRESHOLD_US` (`fft_buffer.cpp`), converted to nanoseconds. */
        private const val STALE_THRESHOLD_NANOS = 200_000_000L

        private val EMPTY_BINS = ByteArray(0)
    }
}
