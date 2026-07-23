package com.tailapp.genre

import com.tailapp.audio.dsp.Fft
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10

/**
 * The mel front-end Discogs-EffNet was trained on.
 *
 * The model is a frozen graph: it accepts one thing, a `[patches, 128, 96]`
 * tensor of log-compressed mel bands, and it will happily produce confident
 * nonsense from bands computed even slightly differently. So this is not "a"
 * mel spectrogram — every constant here is transcribed from the two Essentia
 * algorithms that produced the training features, and the numbers matter:
 *
 * - `TensorflowInputMusiCNN` (essentia `src/algorithms/spectral/`) fixes the
 *   512-sample frame, 96 bands, Slaney mel warping, linear triangle weighting,
 *   `unit_tri` normalisation and the `log10(10000*x + 1)` compression.
 * - `TensorflowPredictEffnetDiscogs` (essentia `src/algorithms/machinelearning/`)
 *   fixes the 256-sample hop and the 128-frame patch.
 *
 * Three Essentia defaults are load-bearing and easy to miss:
 * 1. `MelBands.type` defaults to **power** — the filterbank multiplies squared
 *    magnitudes, not magnitudes.
 * 2. `MelBands.normalize = "unit_tri"` divides each triangle by its *theoretical*
 *    area `(fstep1 + fstep2) / 2`, not by the summed bin weights.
 * 3. `weighting = "linear"` means the triangle *slopes* are linear in Hz even
 *    though the corner frequencies are spaced on the mel scale. Getting this
 *    wrong (the obvious reading is mel-linear slopes, which is what librosa
 *    does) tilts every band.
 *
 * Two deliberate divergences from Essentia, neither observable in the output:
 * - Essentia's `Windowing` defaults to `zeroPhase = true`, a circular rotation
 *   by half the frame. That changes phase only, and only magnitudes are used.
 * - Essentia's `FrameCutter` zero-pads a final partial frame; this stops at the
 *   last complete one. Padded tail frames are never part of a patch we keep, and
 *   dropping them makes [frameCount] a closed form on both sides of the port.
 *
 * Pure JVM: no `android.*`, so `EffnetMelSpectrogramTest` can diff it against
 * `tools/dump_reference.py` on the host.
 *
 * Not thread-safe — it owns scratch buffers and an [Fft]. One instance per
 * calling thread, same rule as [Fft] itself.
 */
class EffnetMelSpectrogram {

    private val fft = Fft(FRAME_SIZE)

    /**
     * Symmetric Hann, `0.5 - 0.5*cos(2*pi*i/(N-1))`, held in double because it
     * is folded into the frame before the (single-precision) FFT sees it.
     * Essentia configures `normalized = false`, so there is no `2/sum(w)` here.
     */
    private val window = DoubleArray(FRAME_SIZE) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / (FRAME_SIZE - 1.0))
    }

    /**
     * The filterbank, stored sparsely: band `b` touches spectrum bins
     * `bandBegin[b] .. bandEnd[b]` inclusive, with weights `bandWeights[b]`
     * indexed from `bandBegin[b]`. Dense 96x257 would be 96% zeros.
     */
    private val bandBegin = IntArray(NUM_BANDS)
    private val bandEnd = IntArray(NUM_BANDS)
    private val bandWeights: Array<DoubleArray>

    private val frameBuffer = FloatArray(FRAME_SIZE)
    private val magnitudes = FloatArray(FRAME_SIZE / 2 + 1)

    init {
        val edges = bandEdges()
        val spectrumSize = FRAME_SIZE / 2 + 1
        // Essentia's TriangularBands::compute uses (sampleRate/2)/(bins-1), i.e.
        // the last bin sits exactly on Nyquist.
        val frequencyScale = (SAMPLE_RATE / 2.0) / (spectrumSize - 1)

        bandWeights = Array(NUM_BANDS) { band ->
            val low = edges[band]
            val centre = edges[band + 1]
            val high = edges[band + 2]
            val stepUp = centre - low
            val stepDown = high - centre

            val begin = ceil(low / frequencyScale).toInt()
            val end = floor(high / frequencyScale).toInt()
            require(end < spectrumSize) { "band $band ends above Nyquist" }
            bandBegin[band] = begin
            bandEnd[band] = end

            // unit_tri: normalise by the triangle's theoretical area rather than
            // by the weights that actually landed on bins.
            val area = (stepUp + stepDown) / 2.0
            DoubleArray(end - begin + 1) { offset ->
                val binFrequency = (begin + offset) * frequencyScale
                val raw = if (binFrequency < centre) {
                    (binFrequency - low) / stepUp
                } else {
                    (high - binFrequency) / stepDown
                }
                raw / area
            }
        }
    }

    /**
     * Computes the log-mel spectrogram of [count] samples of [samples].
     *
     * @return row-major `[frameCount(count) * NUM_BANDS]`; empty when the input
     *   is shorter than one frame.
     */
    fun compute(samples: FloatArray, count: Int = samples.size): FloatArray {
        require(count >= 0 && count <= samples.size) { "count out of range: $count" }

        val frames = frameCount(count)
        val out = FloatArray(frames * NUM_BANDS)

        for (frame in 0 until frames) {
            // startFromZero = false: frame f covers [f*hop - frameSize/2, ...),
            // so the first half-frame reads from before the signal and is zero.
            val start = frame * HOP_SIZE - FRAME_SIZE / 2
            for (i in 0 until FRAME_SIZE) {
                val index = start + i
                val sample = if (index in 0 until count) samples[index].toDouble() else 0.0
                frameBuffer[i] = (sample * window[i]).toFloat()
            }
            fft.magnitudeSpectrum(frameBuffer, magnitudes)

            val rowOffset = frame * NUM_BANDS
            for (band in 0 until NUM_BANDS) {
                val weights = bandWeights[band]
                val begin = bandBegin[band]
                var energy = 0.0
                for (offset in weights.indices) {
                    // type = "power": the filterbank sees squared magnitudes.
                    val magnitude = magnitudes[begin + offset].toDouble()
                    energy += magnitude * magnitude * weights[offset]
                }
                out[rowOffset + band] =
                    log10(COMPRESSION_SCALE * energy + COMPRESSION_SHIFT).toFloat()
            }
        }
        return out
    }

    /**
     * Cuts a spectrogram from [compute] into model-sized patches.
     *
     * @return row-major `[patchCount * PATCH_FRAMES * NUM_BANDS]`, ready to hand
     *   to ONNX Runtime as a `[patches, 128, 96]` tensor. Empty when [mel] holds
     *   fewer than [PATCH_FRAMES] frames.
     */
    fun patches(mel: FloatArray): FloatArray {
        val frames = mel.size / NUM_BANDS
        val patches = patchCount(frames)
        if (patches == 0) return FloatArray(0)

        val patchValues = PATCH_FRAMES * NUM_BANDS
        val out = FloatArray(patches * patchValues)
        for (patch in 0 until patches) {
            System.arraycopy(
                mel, patch * PATCH_HOP * NUM_BANDS,
                out, patch * patchValues,
                patchValues
            )
        }
        return out
    }

    /** Convenience: [compute] then [patches]. */
    fun computePatches(samples: FloatArray, count: Int = samples.size): FloatArray =
        patches(compute(samples, count))

    companion object {
        /** Rate the model was trained at; anything else must be resampled first. */
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 512
        const val HOP_SIZE = 256
        const val NUM_BANDS = 96

        /** Frames the embedding model consumes per patch. */
        const val PATCH_FRAMES = 128

        /** Frames between consecutive patches — Essentia's default is no overlap. */
        const val PATCH_HOP = 128

        /** `log10(10000 * energy + 1)`, from musicnn's training preprocessing. */
        const val COMPRESSION_SCALE = 10000.0
        const val COMPRESSION_SHIFT = 1.0

        /** Shortest input that yields a single patch: 2.048 s at 16 kHz. */
        const val MIN_SAMPLES_PER_PATCH = (PATCH_FRAMES - 1) * HOP_SIZE + FRAME_SIZE - FRAME_SIZE / 2

        /** Frames [compute] will emit for [sampleCount] samples. */
        fun frameCount(sampleCount: Int): Int {
            val padded = sampleCount + FRAME_SIZE / 2
            if (padded < FRAME_SIZE) return 0
            return 1 + (padded - FRAME_SIZE) / HOP_SIZE
        }

        /** Patches [patches] will emit for [frames] frames. */
        fun patchCount(frames: Int): Int =
            if (frames < PATCH_FRAMES) 0 else 1 + (frames - PATCH_FRAMES) / PATCH_HOP

        // --- Slaney mel scale, from essentia/src/essentia/essentiamath.h -------

        private const val LIN_SLOPE = 3.0 / 200.0
        private const val MIN_LOG_HZ = 1000.0
        private const val MIN_LOG_MEL = MIN_LOG_HZ * LIN_SLOPE
        private val LOG_STEP = ln(6.4) / 27.0

        internal fun hzToMelSlaney(hz: Double): Double =
            if (hz < MIN_LOG_HZ) hz * LIN_SLOPE
            else MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) / LOG_STEP

        internal fun melToHzSlaney(mel: Double): Double =
            if (mel < MIN_LOG_MEL) mel / LIN_SLOPE
            else MIN_LOG_HZ * exp((mel - MIN_LOG_MEL) * LOG_STEP)

        /**
         * The 98 triangle corners, equally spaced in mel between 0 Hz and Nyquist.
         *
         * Accumulates the increment rather than computing `low + i * increment`,
         * because that is what `MelBands::calculateFilterFrequencies` does and
         * the two disagree in the last bits — enough to move a `ceil()` bin
         * boundary by one.
         */
        internal fun bandEdges(): DoubleArray {
            val low = hzToMelSlaney(0.0)
            val high = hzToMelSlaney(SAMPLE_RATE / 2.0)
            val increment = (high - low) / (NUM_BANDS + 1)
            var mel = low
            return DoubleArray(NUM_BANDS + 2) {
                val hz = melToHzSlaney(mel)
                mel += increment
                hz
            }
        }
    }
}
