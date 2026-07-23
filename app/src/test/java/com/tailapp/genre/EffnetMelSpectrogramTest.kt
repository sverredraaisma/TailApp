package com.tailapp.genre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * The Kotlin front-end against the Python one.
 *
 * A neural front-end has no self-evident correct answer to assert — the only
 * meaningful check is "does this produce the same numbers as the implementation
 * the model was trained with". `tools/dump_reference.py` transcribes Essentia's
 * algorithms in numpy and dumps its output; this diffs against that.
 *
 * The parity tests skip (rather than fail) when the dump is absent, because
 * regenerating it means installing TensorFlow. The structural tests below always
 * run and would catch the mistakes that are easy to make while editing the
 * front-end — wrong frame count, wrong band count, wrong patch slicing.
 */
class EffnetMelSpectrogramTest {

    /**
     * Absolute tolerance on the log-compressed mel bands.
     *
     * The two implementations differ only in precision: numpy computes the FFT
     * and the filterbank in float64, [com.tailapp.audio.dsp.Fft] is float32 (the
     * band sum and the compression are double on both sides). Measured max
     * absolute deviation over all 187x96 bands of the reference signal is
     * **5.5e-6** on a value range of 0..5.8, i.e. about 1e-6 relative; the bound
     * here leaves an order of magnitude for a different JVM's rounding. See
     * docs/genre-model.md.
     */
    private val melTolerance = 5e-5f

    @Test
    fun `mel spectrogram matches the python reference`() {
        val reference = GenreReference.load()
        assumeTrue(GenreReference.SKIP_REASON, reference != null)
        reference!!

        assertEquals(EffnetMelSpectrogram.SAMPLE_RATE, reference.sampleRate)
        assertEquals(EffnetMelSpectrogram.NUM_BANDS, reference.numBands)

        val mel = EffnetMelSpectrogram().compute(reference.signal)

        assertEquals(
            "frame count",
            reference.melFrames * reference.numBands,
            mel.size
        )

        var worst = 0f
        var worstAt = -1
        for (i in mel.indices) {
            val delta = abs(mel[i] - reference.mel[i])
            if (delta > worst) {
                worst = delta
                worstAt = i
            }
        }
        val frame = worstAt / reference.numBands
        val band = worstAt % reference.numBands
        // Printed so the achieved parity is visible in the test report, not just
        // the fact that it cleared the bar.
        println("mel parity: max |kotlin - python| = $worst (frame $frame, band $band)")
        assertTrue(
            "max |kotlin - python| = $worst at frame $frame band $band " +
                "(kotlin ${mel[worstAt]}, python ${reference.mel[worstAt]}), tolerance $melTolerance",
            worst <= melTolerance
        )
    }

    @Test
    fun `patch slicing matches the python reference`() {
        val reference = GenreReference.load()
        assumeTrue(GenreReference.SKIP_REASON, reference != null)
        reference!!

        val front = EffnetMelSpectrogram()
        val mel = front.compute(reference.signal)
        val patches = front.patches(mel)
        val patchValues = EffnetMelSpectrogram.PATCH_FRAMES * EffnetMelSpectrogram.NUM_BANDS

        // Python and Kotlin agree on how many patches a 3 s window yields...
        assertEquals(reference.patchCount, patches.size / patchValues)
        assertEquals(reference.patchCount * patchValues, patches.size)
        // ...and patch 0 is the leading PATCH_FRAMES rows of the spectrogram,
        // copied verbatim. Exact equality here, because this step is a memcpy;
        // the float64-vs-float32 tolerance belongs to the parity test above.
        for (i in 0 until patchValues) {
            assertEquals(mel[i], patches[i], 0f)
        }
    }

    @Test
    fun `frame count follows the hop math`() {
        // startFromZero = false prepends frameSize/2 zeros, so a signal of
        // exactly one patch's worth of samples yields exactly one patch.
        assertEquals(0, EffnetMelSpectrogram.frameCount(0))
        assertEquals(1, EffnetMelSpectrogram.frameCount(EffnetMelSpectrogram.FRAME_SIZE / 2))
        assertEquals(2, EffnetMelSpectrogram.frameCount(EffnetMelSpectrogram.FRAME_SIZE / 2 + 256))

        val onePatch = EffnetMelSpectrogram.MIN_SAMPLES_PER_PATCH
        assertEquals(32768, onePatch)
        assertEquals(EffnetMelSpectrogram.PATCH_FRAMES, EffnetMelSpectrogram.frameCount(onePatch))
        assertEquals(1, EffnetMelSpectrogram.patchCount(EffnetMelSpectrogram.frameCount(onePatch)))
        assertEquals(0, EffnetMelSpectrogram.patchCount(EffnetMelSpectrogram.frameCount(onePatch - 1)))

        // 3 s at 16 kHz, the classifier's default window.
        assertEquals(187, EffnetMelSpectrogram.frameCount(48_000))
        assertEquals(1, EffnetMelSpectrogram.patchCount(187))
    }

    @Test
    fun `output shape is frames by bands`() {
        val samples = FloatArray(16_000) { 0.1f }
        val mel = EffnetMelSpectrogram().compute(samples)
        assertEquals(EffnetMelSpectrogram.frameCount(samples.size) * EffnetMelSpectrogram.NUM_BANDS, mel.size)
    }

    @Test
    fun `silence compresses to zero`() {
        // log10(10000 * 0 + 1) == 0 — the compression's floor, and the value a
        // shift/scale ordering mistake would move off.
        val mel = EffnetMelSpectrogram().compute(FloatArray(16_000))
        for (value in mel) assertEquals(0f, value, 0f)
    }

    @Test
    fun `a loud tone lands in the band that contains it`() {
        val rate = EffnetMelSpectrogram.SAMPLE_RATE
        val frequency = 1000.0
        val samples = FloatArray(rate) { i ->
            (0.5 * kotlin.math.sin(2.0 * Math.PI * frequency * i / rate)).toFloat()
        }
        val mel = EffnetMelSpectrogram().compute(samples)
        val bands = EffnetMelSpectrogram.NUM_BANDS

        // Average each band over the interior frames (skip the zero-padded edges).
        val energy = FloatArray(bands)
        val frames = mel.size / bands
        for (frame in 2 until frames - 2) {
            for (band in 0 until bands) energy[band] += mel[frame * bands + band]
        }

        var peak = 0
        for (band in 1 until bands) if (energy[band] > energy[peak]) peak = band

        val edges = EffnetMelSpectrogram.bandEdges()
        assertTrue(
            "peak band $peak spans ${edges[peak]}..${edges[peak + 2]} Hz, expected to contain $frequency",
            frequency in edges[peak]..edges[peak + 2]
        )
    }

    @Test
    fun `band edges are mel-spaced across the full range`() {
        val edges = EffnetMelSpectrogram.bandEdges()
        assertEquals(EffnetMelSpectrogram.NUM_BANDS + 2, edges.size)
        assertEquals(0.0, edges.first(), 1e-9)
        assertEquals(EffnetMelSpectrogram.SAMPLE_RATE / 2.0, edges.last(), 1e-6)

        // Strictly ascending, and mel-spaced means the low end is near-linear
        // (Slaney's scale is linear below 1 kHz) and the top end is not.
        for (i in 1 until edges.size) assertTrue(edges[i] > edges[i - 1])
        val lowStep = edges[2] - edges[1]
        val highStep = edges[edges.size - 1] - edges[edges.size - 2]
        assertTrue("expected geometric spacing at the top", highStep > 3 * lowStep)
    }

    @Test
    fun `slaney mel conversions round-trip`() {
        for (hz in listOf(0.0, 100.0, 999.0, 1000.0, 1001.0, 4000.0, 8000.0)) {
            val roundTripped = EffnetMelSpectrogram.melToHzSlaney(EffnetMelSpectrogram.hzToMelSlaney(hz))
            assertEquals(hz, roundTripped, max(1e-9, hz * 1e-12))
        }
        // The published anchor: the linear/log break sits at 1 kHz = 15 mel.
        assertEquals(15.0, EffnetMelSpectrogram.hzToMelSlaney(1000.0), 1e-12)
    }

    @Test
    fun `compression is log10 of scale times energy plus one`() {
        // Guards the ordering that Essentia's UnaryOperator makes easy to invert:
        // it is log10(10000*x + 1), not log10((x + 1) * 10000).
        val expected = log10(EffnetMelSpectrogram.COMPRESSION_SCALE * 1e-4 + EffnetMelSpectrogram.COMPRESSION_SHIFT)
        assertEquals(log10(2.0), expected, 1e-12)
    }
}
