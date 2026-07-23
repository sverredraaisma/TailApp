package com.tailapp.audio

import com.tailapp.audio.dsp.LogFilterbank
import com.tailapp.testutil.BeatReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The shared front-end against BeatNet's, measured rather than assumed.
 *
 * The project plan makes numeric validation of the feature extractor a gate on
 * any model work, and [FeatureConfig]'s KDoc claims its defaults "match BeatNet's
 * `log_spect.py`". `tools/dump_beat_reference.py` runs BeatNet's *own* extractor
 * — madmom's `LogarithmicFilteredSpectrogram`, unmodified — on a deterministic
 * signal, and this diffs against that.
 *
 * **The claim does not hold, and this file is where that is written down.** Two
 * of eight properties match. The rest are pinned below with the measured size of
 * each gap, so the divergence is a number in the repository rather than a
 * paragraph in a document nobody re-checks:
 *
 * | Property | BeatNet | `FeatureConfig()` | |
 * |---|---|---|---|
 * | sample rate | 22050 | 22050 | match |
 * | hop | 441 | 441 | match |
 * | window | 1411 | 2048 | differ |
 * | bands | 136 | 205 | differ |
 * | lowest centre | 46.85 Hz | 30.00 Hz | differ |
 * | filter scaling | unit area | unit peak | differ |
 * | frame alignment | centred | window-end | differ |
 * | model input | bands ‖ positive diff | bands | differ |
 *
 * These tests therefore assert the *divergence*. Each one fails the day someone
 * makes the two front-ends agree — which is the day `CrnnActivationSource` can be
 * switched on, and the day `docs/beat-model.md` needs rewriting. That is the
 * intended failure, not a false alarm.
 *
 * `FeatureConfig`'s defaults are deliberately left alone: the DSP beat tracker,
 * the transient tier and their tests are all calibrated against 205 bands from a
 * 2048-sample window, and moving them to chase a model that is not wired in yet
 * would break three working tiers to enable none.
 *
 * The parity tests skip (rather than fail) when the dump is absent; regenerating
 * it costs a PyTorch install. The structural comparisons that need no dump always
 * run.
 */
class BeatNetFrontEndParityTest {

    private val config = FeatureConfig()

    private fun filterbank() = LogFilterbank(
        sampleRate = config.sampleRate,
        frameSize = config.frameSize,
        bandsPerOctave = config.bandsPerOctave,
        fMin = config.fMin,
        fMax = config.fMax
    )

    // --- what does match ---------------------------------------------------------

    @Test
    fun `sample rate and hop match BeatNet`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        assertEquals("sample rate", reference.sampleRate, config.sampleRate)
        assertEquals("hop size", reference.hopSize, config.hopSize)
        assertEquals("frames per second", 50f, config.framesPerSecond, 1e-4f)

        // The log compression is the same shape and the same constants, even
        // though it is applied to differently-scaled inputs.
        assertEquals("log multiplier", reference.logMultiplier, config.logMultiplier, 1e-6f)
        assertEquals("log addend", reference.logAdd, config.logAdd, 1e-6f)
        assertEquals("bands per octave", reference.bandsPerOctave, config.bandsPerOctave)
    }

    // --- what does not -----------------------------------------------------------

    @Test
    fun `window length differs from BeatNet's`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        assertEquals("BeatNet's window, for the record", 1411, reference.winLength)
        assertTrue(
            "FeatureConfig.frameSize (${config.frameSize}) now equals BeatNet's " +
                "(${reference.winLength}). If that is deliberate, this test and " +
                "docs/beat-model.md both need updating — and CrnnActivationSource may " +
                "finally be usable.",
            config.frameSize != reference.winLength
        )
        // And it cannot be made equal without new DSP: 1411 = 17 x 83, while
        // FeatureConfig requires a power of two because dsp/Fft is radix-2.
        assertTrue("1411 is not a power of two", Integer.bitCount(reference.winLength) != 1)
    }

    @Test
    fun `band count and band centres differ from BeatNet's filterbank`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        val ours = filterbank()
        assertEquals("BeatNet's filter count, for the record", 136, reference.numFilters)
        assertTrue(
            "band counts now agree (${ours.bandCount}); see the note in `window length differs`",
            ours.bandCount != reference.numFilters
        )

        // madmom quantises its log frequencies to FFT bins and drops duplicates,
        // so its lowest *filter* sits well above the requested fmin. Ours keeps
        // every requested centre, collapsing sub-bin triangles onto one bin.
        val theirCentres = reference.filterCenterHz
        assertEquals("BeatNet's lowest centre", 46.85f, theirCentres.first(), 0.05f)
        assertEquals("our lowest centre", config.fMin, ours.centerFrequenciesHz.first(), 0.01f)
        assertTrue(
            "our lowest band centre (${ours.centerFrequenciesHz.first()} Hz) is below BeatNet's " +
                "(${theirCentres.first()} Hz): our bottom 16 bands have no counterpart at all",
            ours.centerFrequenciesHz.first() < theirCentres.first() - 10f
        )
        assertEquals(
            "bands below BeatNet's lowest filter",
            16,
            ours.centerFrequenciesHz.count { it < theirCentres.first() }
        )

        // The tops nearly agree — both are clamped by the same Nyquist, not by fMax.
        assertEquals("top centre", theirCentres.last(), ours.centerFrequenciesHz.last(), 20f)
        assertTrue("fMax exceeds Nyquist on both sides", reference.fMax > config.sampleRate / 2f)
    }

    @Test
    fun `BeatNet's filters have unit area where ours have unit peak`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        assertTrue("the dump was taken with norm_filters=True", reference.normFilters)
        for (sum in reference.filterSums) {
            assertEquals("every BeatNet filter sums to 1 over its bins", 1f, sum, 1e-5f)
        }
        // A unit-area triangle spanning several bins must peak below 1.
        assertTrue(
            "wide BeatNet filters peak below 1",
            reference.filterPeaks.count { it < 0.5f } > reference.numFilters / 2
        )

        // Ours: a flat unit magnitude spectrum through the filterbank reports each
        // band's total weight. Unit-peak triangles sum to well over 1 wherever a
        // band spans more than a couple of bins.
        val ours = filterbank()
        val flat = FloatArray(ours.binCount) { 1f }
        val weights = FloatArray(ours.bandCount)
        ours.apply(flat, weights)
        val widest = weights.maxOrNull()!!
        assertEquals(
            "our widest band's weights sum to $widest, against 1 for every BeatNet " +
                "filter — the two banks differ by a per-band gain, not a global scale",
            28.3f,
            widest,
            0.5f
        )
        // And at the very bottom, where several band centres land inside one FFT
        // bin, LogFilterbank's sub-bin collapse can leave a band with no weight at
        // all. Recorded because it is another way the two banks are not the same
        // representation, not because it is a defect to fix here.
        assertEquals(
            "our quietest band's weights sum to ${weights.minOrNull()}",
            0f,
            weights.minOrNull()!!,
            1e-6f
        )
    }

    @Test
    fun `the model consumes bands stacked with their positive difference`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        assertEquals("feature width", 2 * reference.numFilters, reference.featureDim)
        assertEquals("difference lag", 1, reference.diffFrames)

        // Prove it, rather than restate it: columns [numFilters, 2*numFilters)
        // are max(0, band[t] - band[t-1]) with a zero first frame.
        val features = reference.features
        val width = reference.featureDim
        val bands = reference.numFilters
        var worst = 0f
        for (t in 1 until reference.frames) {
            for (i in 0 until bands) {
                val expected = (features[t * width + i] - features[(t - 1) * width + i]).coerceAtLeast(0f)
                worst = maxOf(worst, abs(features[t * width + bands + i] - expected))
            }
        }
        assertTrue("stacked difference reproduced to $worst", worst < 1e-6f)
        for (i in 0 until bands) {
            assertEquals("first frame's difference is zero", 0f, features[bands + i], 0f)
        }

        // Our FeatureFrame carries the bands and a *scalar* flux, not a per-band
        // difference vector, so nothing downstream of FeatureExtractor could
        // reconstruct this half without keeping the previous frame itself —
        // which is exactly what CrnnActivationSource does.
        val frames = FeatureExtractor(config).push(
            FloatArray(config.frameSize + config.hopSize),
            endTimestampNanos = 0L
        )
        assertTrue("FeatureFrame exposes bands, not a difference vector", frames.isNotEmpty())
        assertEquals(filterbank().bandCount, frames.first().bands.size)
    }

    /**
     * The headline number: how far our bands are from BeatNet's on the same audio.
     *
     * The two matrices are not the same shape, so this compares each of BeatNet's
     * 136 filters against our nearest band centre, on the best whole-frame
     * alignment available (our window *ends* `frameSize` samples after it starts,
     * BeatNet's is centred, so the offset is 1024/441 = 2.3 frames — not an
     * integer, and that is itself part of the gap).
     *
     * Measured: **mean |diff| 0.210, max 1.954, at a whole-frame alignment of +2**,
     * against a reference whose entire range is 0..1.954. That is not "close with
     * a scale factor", it is a different representation. See `docs/beat-model.md`
     * for what feeding it to the CRNN anyway does to the activations.
     */
    @Test
    fun `bands computed from the reference signal do not match BeatNet's`() {
        val reference = BeatReference.load()
        assumeTrue(BeatReference.SKIP_REASON, reference != null)
        reference!!

        val extractor = FeatureExtractor(config)
        val frames = extractor.push(reference.signal, endTimestampNanos = 0L)
        val ours = filterbank()
        assertTrue("the reference signal produced frames", frames.size > 200)

        val theirBands = reference.bands()
        val theirCentres = reference.filterCenterHz
        val nearest = IntArray(reference.numFilters) { j ->
            var best = 0
            for (i in 0 until ours.bandCount) {
                if (abs(ours.centerFrequenciesHz[i] - theirCentres[j]) <
                    abs(ours.centerFrequenciesHz[best] - theirCentres[j])
                ) {
                    best = i
                }
            }
            best
        }

        // Our frame i's window covers [i*hop, i*hop + frameSize); BeatNet's frame t
        // is centred on t*hop. Try every small shift and keep the best.
        var bestMean = Float.MAX_VALUE
        var bestMax = 0f
        var bestShift = 0
        for (shift in -6..6) {
            var sum = 0.0
            var worst = 0f
            var count = 0
            for (i in frames.indices) {
                val t = i + shift
                if (t < 0 || t >= reference.frames) continue
                for (j in 0 until reference.numFilters) {
                    val delta = abs(frames[i].bands[nearest[j]] - theirBands[t * reference.numFilters + j])
                    sum += delta
                    if (delta > worst) worst = delta
                    count++
                }
            }
            if (count > 0 && (sum / count).toFloat() < bestMean) {
                bestMean = (sum / count).toFloat()
                bestMax = worst
                bestShift = shift
            }
        }

        val range = theirBands.maxOrNull()!!
        val message =
            "our bands vs BeatNet's, best alignment shift $bestShift: " +
                "mean |diff| $bestMean, max $bestMax, over a reference range of 0..$range"

        // Pinned as a divergence. The bound is generous downwards on purpose: if
        // someone builds a real BeatNet front-end this fails and points at the docs.
        assertTrue(
            "$message — the front-ends now agree far better than they did " +
                "(mean was 0.210). Update docs/beat-model.md and consider enabling " +
                "CrnnActivationSource.",
            bestMean > 0.1f
        )
        // And upwards, so a catastrophic regression in our own front-end (all
        // zeros, wrong log, wrong units) still shows up here.
        assertTrue("$message — implausibly large; check FeatureExtractor", bestMean < 1.0f)
    }
}
