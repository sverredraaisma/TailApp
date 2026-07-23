package com.tailapp.audio

import com.tailapp.audio.dsp.BluesteinFft
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * One analysis hop of BeatNet's front-end: the 272 floats its CRNN consumes.
 *
 * Deliberately *not* a [FeatureFrame]. The two carry different geometry (136
 * unit-area bands from a 1411-sample centred window against 205 unit-peak bands
 * from a 2048-sample trailing one) and different content (a stacked positive
 * difference against a scalar flux plus band statistics). Overloading one type to
 * mean both is how the wrong spectrogram reaches the model, which
 * `docs/beat-model.md` measures the cost of: half-time beats and a collapsed
 * downbeat channel.
 *
 * @param index hop counter since capture started, monotonic.
 * @param timestampNanos [System.nanoTime] of the *last sample of this frame's
 *   window* — the same convention [FeatureFrame.timestampNanos] uses, so the two
 *   streams' timestamps are directly comparable. Note that the window is centred
 *   on `index * hop`, so this is `index * hop + 705` samples, not the reference
 *   sample itself.
 * @param features `bands ‖ max(0, bands - previous bands)`, 272 long. Columns
 *   `0 until 136` are the log-filtered spectrogram, the rest its positive
 *   difference. Owned by the frame; consumers must not mutate it.
 */
class BeatNetFrame(
    val index: Long,
    val timestampNanos: Long,
    val features: FloatArray
) {
    override fun toString(): String = "BeatNetFrame(index=$index, features=${features.size})"
}

/**
 * BeatNet's own front-end, ported from madmom, as a streaming extractor.
 *
 * This is the second, dedicated extractor `docs/beat-model.md` asks for. It
 * exists because [FeatureExtractor]'s geometry is *not* BeatNet's and must not
 * become BeatNet's: the DSP beat tracker, the tempo estimator and the whole
 * transient tier are calibrated against 205 bands from a 2048-sample window and
 * their tests assert numbers, not shapes. So the two run side by side, on the
 * same audio, and only the CRNN sees this one's output.
 *
 * ### What it reproduces
 * BeatNet's `log_spect.py` is madmom's
 * `SignalProcessor -> FramedSignalProcessor -> ShortTimeFourierTransformProcessor
 * -> FilteredSpectrogramProcessor -> LogarithmicSpectrogramProcessor ->
 * SpectrogramDifferenceProcessor`, at 22050 Hz with a 1411-sample window and a
 * 441-sample hop. Each stage, and the detail that is easy to get wrong:
 *
 * | Stage | Here |
 * |---|---|
 * | framing | frame `t` is **centred** on sample `t * hop` — `origin = 0` |
 * | window | `np.hanning(1411)`, i.e. the *symmetric* Hann (denominator `N - 1`) |
 * | transform | 1411-point DFT via [BluesteinFft]; bins `0 until 705` |
 * | bin frequencies | `k * sampleRate / 1410`, madmom's labelling — **not** `k * sampleRate / 1411` |
 * | filterbank | 24 bands/octave from 30 Hz, quantised to bins with duplicates dropped, unit **area** |
 * | compression | `log10(1 * x + 1)` |
 * | output | `bands ‖ max(0, bands - previous bands)` |
 *
 * **Centred framing is the one that hides.** madmom's
 * `FramedSignalProcessor(origin=0)` centres frame `t` on sample `t * hop` and
 * zero-pads the first half window; [FeatureExtractor] emits a frame once its
 * window has *filled*, so its frame `i` ends at `frameSize + i * hop`. Getting
 * this wrong shifts every activation by 1024/441 = 2.3 frames, which is not an
 * integer, cannot be fixed by renumbering, and is invisible in any test that
 * looks at shapes rather than numbers.
 *
 * **136 filters, not 205.** madmom maps its log-spaced frequencies onto FFT bins
 * and then calls `np.unique` on the result, so the 219 requested centres collapse
 * to 138 distinct bins and yield 136 overlapping triangles. That is why its
 * lowest *filter* sits at 46.9 Hz although `fmin` is 30 Hz. Each triangle is then
 * divided by its own sum (`norm_filters=True`), so every filter has unit **area**
 * — [com.tailapp.audio.dsp.LogFilterbank]'s have unit peak, a per-band gain of
 * up to 28x that no global scale factor can undo.
 *
 * **705 bins, not 706.** madmom's `stft` returns `fft_size >> 1` bins and drops
 * what would be the Nyquist bin; 1411 is odd, so there is no exact Nyquist and
 * bin 705 is an ordinary bin that madmom simply never computes. It then labels
 * bin `k` with `k * sampleRate / (2 * 705)`, which is *not* the true frequency of
 * bin `k` of a 1411-point transform. Both quirks are reproduced, because the
 * filterbank is built against those labels and a "corrected" version would put
 * every triangle on a slightly different bin.
 *
 * ### Streaming contract
 * Identical to [FeatureExtractor]'s: [push] takes arbitrary chunk sizes, frame
 * boundaries fall in the same absolute places regardless of how the audio was
 * chopped up, per-frame timestamps are derived by walking back from the chunk's
 * own `endTimestampNanos`, and [reset] returns the extractor to "as if from
 * silence".
 *
 * Frame `t` is emitted once sample `t * hop + 705` has arrived — so the first
 * frame needs only 706 samples, not a full window, because its first 705 samples
 * are the zero padding madmom prepends. The trailing frames madmom produces by
 * zero-padding on the *right* have no streaming equivalent and are not emitted:
 * a live extractor cannot know the signal ended.
 *
 * ### Allocation
 * Everything reusable — the window, the [BluesteinFft] and its tables, the
 * magnitude buffer, the filterbank, the ring buffer, the band scratch — is built
 * once in the constructor. Each *emitted* frame allocates the [BeatNetFrame] and
 * its own 272-float array (the frame owns it, so it cannot be scratch), and
 * [push] allocates the list it returns only when at least one frame completed.
 * Nothing else touches the heap per hop.
 *
 * Not thread-safe: one instance belongs to one capture pipeline.
 */
class BeatNetFeatureExtractor {

    private val fft = BluesteinFft(WINDOW_SIZE)
    private val filterbank = MadmomLogFilterbank(
        binCount = SPECTRUM_BINS,
        sampleRate = SAMPLE_RATE,
        bandsPerOctave = BANDS_PER_OCTAVE,
        fMin = F_MIN,
        fMax = F_MAX
    )

    init {
        // A geometry regression would otherwise surface as a silently mis-shaped
        // tensor at the model boundary.
        check(filterbank.bandCount == BAND_COUNT) {
            "filterbank resolved to ${filterbank.bandCount} bands, BeatNet's CRNN needs $BAND_COUNT"
        }
    }

    /** Filter centre frequencies, low to high, resolved the way madmom resolves them. */
    val centerFrequenciesHz: FloatArray get() = filterbank.centerFrequenciesHz

    // np.hanning: the *symmetric* window (denominator N - 1), which is what
    // madmom's ShortTimeFourierTransform defaults to. FeatureExtractor's
    // periodic Hann (denominator N) is a different window and would show up as a
    // small, frequency-dependent gain error in every band.
    private val window = FloatArray(WINDOW_SIZE) { n ->
        (0.5 - 0.5 * cos(2.0 * PI * n / (WINDOW_SIZE - 1))).toFloat()
    }

    // The trailing WINDOW_SIZE samples, oldest at [ringPos]. 1411 is prime-ish
    // (17 x 83) rather than a power of two, so wraparound is an explicit branch
    // instead of FeatureExtractor's mask.
    private val ring = FloatArray(WINDOW_SIZE)
    private var ringPos = 0

    private var totalSamples = 0L
    private var nextFrameEnd = FIRST_FRAME_END.toLong()
    private var frameIndex = 0L

    private val windowed = FloatArray(WINDOW_SIZE)
    private val magnitudes = FloatArray(fft.binCount)
    private val linearBands = FloatArray(BAND_COUNT)

    // The previous frame's log bands, copied rather than referenced: the array
    // handed out in a BeatNetFrame is owned by that frame.
    private val previousBands = FloatArray(BAND_COUNT)
    private var hasPreviousFrame = false

    /**
     * Feeds [count] samples from [samples] (default: the whole array), all at
     * [SAMPLE_RATE]. Returns one [BeatNetFrame] per hop boundary crossed.
     *
     * @param endTimestampNanos [System.nanoTime] of the instant the *last* sample
     *   of this chunk arrived.
     */
    fun push(samples: FloatArray, count: Int = samples.size, endTimestampNanos: Long): List<BeatNetFrame> {
        require(count in 0..samples.size) { "count ($count) out of range for samples of size ${samples.size}" }
        if (count == 0) return emptyList()

        val chunkEndTotal = totalSamples + count
        var frames: MutableList<BeatNetFrame>? = null

        var offset = 0
        var remaining = count
        while (remaining > 0) {
            val untilBoundary = (nextFrameEnd - totalSamples).toInt()
            val take = minOf(remaining, untilBoundary)

            writeToRing(samples, offset, take)
            totalSamples += take
            offset += take
            remaining -= take

            if (totalSamples == nextFrameEnd) {
                val samplesAfter = chunkEndTotal - totalSamples
                val timestamp = endTimestampNanos - (samplesAfter * NANOS_PER_SECOND) / SAMPLE_RATE
                val list = frames ?: ArrayList<BeatNetFrame>(4).also { frames = it }
                list.add(emitFrame(timestamp))
                nextFrameEnd += HOP_SIZE
            }
        }
        return frames ?: emptyList()
    }

    /** Drops all buffered audio and history; the next [push] starts as if from silence. */
    fun reset() {
        ring.fill(0f)
        ringPos = 0
        totalSamples = 0L
        nextFrameEnd = FIRST_FRAME_END.toLong()
        frameIndex = 0L
        previousBands.fill(0f)
        hasPreviousFrame = false
    }

    private fun writeToRing(src: FloatArray, offset: Int, count: Int) {
        // At most one wrap: `count` is bounded by the distance to the next frame
        // boundary, which is FIRST_FRAME_END (706) once and HOP_SIZE (441) after
        // that — both well under the ring's 1411.
        val firstRun = minOf(count, ring.size - ringPos)
        System.arraycopy(src, offset, ring, ringPos, firstRun)
        val remainder = count - firstRun
        if (remainder > 0) {
            System.arraycopy(src, offset + firstRun, ring, 0, remainder)
        }
        ringPos += count
        if (ringPos >= ring.size) ringPos -= ring.size
    }

    private fun emitFrame(timestampNanos: Long): BeatNetFrame {
        // Once full, [ringPos] is both "where the next sample lands" and "the
        // oldest sample held", so reading forward from there yields the window
        // oldest-to-newest. Before it has filled — which is only frame 0, needing
        // 706 of 1411 samples — the untouched slots are still the zeros [reset]
        // left, and they sit exactly where madmom's leading zero padding goes.
        val split = ring.size - ringPos
        for (n in 0 until split) {
            windowed[n] = ring[ringPos + n] * window[n]
        }
        for (n in split until WINDOW_SIZE) {
            windowed[n] = ring[n - split] * window[n]
        }

        fft.magnitudeSpectrum(windowed, magnitudes)
        filterbank.apply(magnitudes, linearBands)

        val features = FloatArray(FEATURE_SIZE)
        for (i in 0 until BAND_COUNT) {
            features[i] = log10(LOG_MULTIPLIER * linearBands[i] + LOG_ADD)
        }

        // madmom's SpectrogramDifferenceProcessor(diff_ratio=0.5,
        // positive_diffs=True, stack_diffs=np.hstack) resolves to a one-frame lag
        // at this window and hop (the reference dump records diffFrames = 1), and
        // pads the first frame's difference with zeros rather than differencing
        // against silence.
        if (hasPreviousFrame) {
            for (i in 0 until BAND_COUNT) {
                val delta = features[i] - previousBands[i]
                features[BAND_COUNT + i] = if (delta > 0f) delta else 0f
            }
        } else {
            hasPreviousFrame = true
        }
        System.arraycopy(features, 0, previousBands, 0, BAND_COUNT)

        val frame = BeatNetFrame(frameIndex, timestampNanos, features)
        frameIndex++
        return frame
    }

    companion object {
        /** Analysis rate the CRNN was trained at. */
        const val SAMPLE_RATE = 22050

        /** 20 ms — 50 activation frames per second. */
        const val HOP_SIZE = 441

        /** `int(64 * 0.001 * 22050)`, from `BeatNet.py`. 17 x 83, hence [BluesteinFft]. */
        const val WINDOW_SIZE = 1411

        /** madmom's `stft` returns `fft_size >> 1` bins and drops the Nyquist bin. */
        const val SPECTRUM_BINS = WINDOW_SIZE / 2

        const val BANDS_PER_OCTAVE = 24
        const val F_MIN = 30f

        /** Above Nyquist on purpose — BeatNet passes 17000 and madmom simply clamps. */
        const val F_MAX = 17000f

        const val LOG_MULTIPLIER = 1f
        const val LOG_ADD = 1f

        /** What madmom's unique-bin filterbank resolves to at this geometry. */
        const val BAND_COUNT = 136

        /** `bands ‖ positive difference` — the CRNN's input width. */
        const val FEATURE_SIZE = 2 * BAND_COUNT

        /**
         * Samples that must have arrived before frame 0 can be emitted.
         *
         * Frame `t` covers `[t*hop - 705, t*hop + 706)`, so its last sample is at
         * absolute index `t*hop + 705` and it completes at `t*hop + 706`. The
         * leading 705 samples of frame 0 are madmom's zero padding, which is why
         * this is 706 and not a full window.
         */
        const val FIRST_FRAME_END = WINDOW_SIZE - WINDOW_SIZE / 2

        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * madmom's `LogarithmicFilterbank`, ported.
 *
 * Not a variant of [com.tailapp.audio.dsp.LogFilterbank] and not a replacement
 * for it — a different filterbank that happens to share a name. The two disagree
 * on three things that each change every band's value:
 *
 * 1. **Bin quantisation with dedup.** madmom rounds each log-spaced frequency to
 *    the nearest FFT bin and then takes `np.unique`, so centres that share a bin
 *    become *one* filter. `LogFilterbank` keeps every requested centre and
 *    collapses sub-bin triangles onto the nearest bin, duplicating it.
 * 2. **Triangles are built in bin space**, from consecutive triples of those
 *    unique bins, with the rising edge `linspace(0, 1, c, endpoint=False)` and
 *    the falling edge `linspace(1, 0, n - c, endpoint=False)` — so the peak bin
 *    carries exactly 1 and the *start* bin carries exactly 0.
 * 3. **Unit area, not unit peak.** Each triangle is divided by its own sum.
 *
 * The filters are stored sparsely (a start bin and a short run of weights per
 * band), because a dense 705 x 136 matrix at these settings is 98% zeros.
 *
 * @param binCount bins in the spectra passed to [apply] — 705 for BeatNet.
 * @param sampleRate rate those spectra were computed at.
 * @param bandsPerOctave filterbank resolution.
 * @param fMin lowest requested centre, in Hz. The lowest *filter* lands higher,
 *   wherever bin quantisation puts it.
 * @param fMax highest requested centre, in Hz; may exceed Nyquist, in which case
 *   the bin range does the clamping, exactly as madmom's does.
 */
internal class MadmomLogFilterbank(
    val binCount: Int,
    val sampleRate: Int,
    val bandsPerOctave: Int,
    val fMin: Float,
    val fMax: Float
) {
    /**
     * `np.fft.fftfreq(2 * binCount, 1 / sampleRate)[:binCount]`.
     *
     * Note the `2 * binCount`, not the transform length: madmom labels bin `k` of
     * a 1411-point transform with `k * sampleRate / 1410`. Reproducing the
     * labelling matters more than it being right, because it is what the
     * filterbank the model was trained with was built against.
     */
    private val binFrequencies = DoubleArray(binCount) { k ->
        k / (2.0 * binCount * (1.0 / sampleRate))
    }

    /** Unique FFT bins the log-spaced centres quantise to; `bandCount + 2` of them. */
    private val bandBins: IntArray = quantise(logFrequencies())

    val bandCount: Int = (bandBins.size - 2).coerceAtLeast(0)

    private val startBin = IntArray(bandCount)
    private val weights = Array(bandCount) { FloatArray(0) }

    init {
        require(bandCount > 0) { "not enough distinct bins for a filterbank" }
        for (j in 0 until bandCount) {
            var start = bandBins[j]
            var center = bandBins[j + 1]
            var stop = bandBins[j + 2]
            // madmom's "consistently handle too-small filters": a triangle
            // narrower than two bins becomes a single bin at full weight.
            if (stop - start < 2) {
                center = start
                stop = start + 1
            }
            val length = stop - start
            val peak = center - start

            val data = DoubleArray(length)
            for (i in 0 until peak) data[i] = i.toDouble() / peak
            for (i in peak until length) data[i] = 1.0 - (i - peak).toDouble() / (length - peak)

            var sum = 0.0
            for (v in data) sum += v
            val w = FloatArray(length)
            for (i in 0 until length) w[i] = (data[i] / sum).toFloat()

            startBin[j] = start
            weights[j] = w
        }
    }

    /**
     * Centre frequency per band, resolved madmom's way: the peak bin of the
     * filter, or the middle of its support when the filter is flat.
     */
    val centerFrequenciesHz: FloatArray = FloatArray(bandCount) { j ->
        val w = weights[j]
        var lowest = -1
        var highest = -1
        for (i in w.indices) {
            if (w[i] != 0f) {
                if (lowest < 0) lowest = i
                highest = i
            }
        }
        val center = when {
            lowest < 0 -> 0
            w[lowest] == w[highest] -> lowest + (highest - lowest) / 2
            else -> {
                var best = lowest
                for (i in lowest until highest) if (w[i] > w[best]) best = i
                best
            }
        }
        binFrequencies[startBin[j] + center].toFloat()
    }

    /**
     * `bandsOut[j] = sum(magnitudes[startBin[j]..] * weights[j])`.
     *
     * [magnitudes] may be *longer* than [binCount] — [BluesteinFft.magnitudeSpectrum]
     * returns `size / 2 + 1` bins where madmom keeps `size / 2` — and the extra
     * bins are ignored rather than being an error.
     */
    fun apply(magnitudes: FloatArray, bandsOut: FloatArray) {
        require(magnitudes.size >= binCount) { "magnitudes must have at least $binCount bins" }
        require(bandsOut.size == bandCount) { "bandsOut must have length $bandCount" }
        for (j in 0 until bandCount) {
            val w = weights[j]
            val base = startBin[j]
            var sum = 0f
            for (i in w.indices) sum += magnitudes[base + i] * w[i]
            bandsOut[j] = sum
        }
    }

    /**
     * madmom's `log_frequencies`: `fref * 2^(k / bandsPerOctave)` over the
     * `floor`/`ceil` bracket of `[fMin, fMax]`, then trimmed back to that range.
     */
    private fun logFrequencies(): DoubleArray {
        val left = floor(ln(fMin / A4) / LN2 * bandsPerOctave).toInt()
        val right = ceil(ln(fMax / A4) / LN2 * bandsPerOctave).toInt()
        val out = ArrayList<Double>(right - left)
        for (k in left until right) {
            val f = A4 * 2.0.pow(k.toDouble() / bandsPerOctave)
            if (f >= fMin && f <= fMax) out.add(f)
        }
        return out.toDoubleArray()
    }

    /**
     * madmom's `frequencies2bins(..., unique_bins=True)`: nearest bin per
     * frequency, ties going to the higher bin, duplicates dropped.
     *
     * The dedup is where 219 requested centres become 138 bins and therefore 136
     * filters. Below ~600 Hz the 24-per-octave spacing is finer than the
     * 15.6 Hz bin grid, so most of those centres collide.
     */
    private fun quantise(frequencies: DoubleArray): IntArray {
        val bins = ArrayList<Int>(frequencies.size)
        var previous = -1
        for (f in frequencies) {
            // searchsorted(side='left'): first bin whose frequency is >= f.
            var lo = 0
            var hi = binCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (binFrequencies[mid] < f) lo = mid + 1 else hi = mid
            }
            var index = lo.coerceIn(1, binCount - 1)
            // np.clip then "index -= f - left < right - f": the lower bin wins
            // only when it is strictly nearer.
            if (f - binFrequencies[index - 1] < binFrequencies[index] - f) index -= 1
            if (index != previous) {
                bins.add(index)
                previous = index
            }
        }
        return bins.toIntArray()
    }

    private companion object {
        /** madmom's tuning reference; the log grid is anchored to it, not to `fMin`. */
        const val A4 = 440.0
        val LN2 = ln(2.0)
    }
}
